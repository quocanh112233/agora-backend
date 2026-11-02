package com.agora.identity;

import com.agora.contracts.identity.v1.*;
import com.agora.identity.configuration.IdentityGrpcServer;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "agora.grpc.enabled=true", "agora.grpc.port=0",
        "agora.grpc.token=admin-status-integration-token-not-real-secret", "agora.auth.rate-limit.attempts=1000" })
class AdminAccountStatusTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityGrpcServer grpc;
    @Autowired JdbcIndexedSessionRepository sessions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.agora.identity.application.IdentityService identity;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.springframework.security.crypto.password.PasswordEncoder passwords;
    final JsonMapper json = JsonMapper.builder().build();
    @BeforeEach void reset() { jdbc.execute("TRUNCATE identity.accounts, identity.spring_session CASCADE"); }

    @Test void statusChangeRevokesAllSessionsAndUnlockNeverRevivesThem() throws Exception {
        Browser admin = new Browser(); UUID actor = admin.registerLogin(); grant(actor);
        Browser buyer = new Browser(); UUID target = buyer.registerLogin();
        Browser second = new Browser(); second.email = buyer.email; second.login();
        String cookie = buyer.cookie(), cookie2 = second.cookie();
        org.springframework.session.Session stale = sessions.findById(cookie);
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        try {
            var rpc = rpc(channel);
            assertThat(rpc.resolveSession(ResolveSessionRequest.newBuilder().setSessionId(cookie).build()).getAccountId()).isEqualTo(target.toString());
            var locked = admin.status(target, "SUSPENDED", 0);
            assertThat(locked.statusCode()).as(locked.body()).isEqualTo(200);
            assertThat(tree(locked).path("version").asLong()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.spring_session WHERE principal_name = ?", Integer.class, target.toString())).isZero();
            assertThat(admin.status(target, "ACTIVE", 1).statusCode()).isEqualTo(200);
            // A request loaded before the lock may save late; its old version must remain unusable.
            stale.setAttribute("late-save", "stale");
            sessionStore().save(stale);
            for (String old : List.of(cookie, cookie2)) {
                assertThat(buyer.rawGet("/me", old).statusCode()).isEqualTo(401);
                assertThatThrownBy(() -> rpc.resolveSession(ResolveSessionRequest.newBuilder().setSessionId(old).build()))
                        .isInstanceOfSatisfying(StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            }
            assertThat(buyer.login().statusCode()).isEqualTo(200);
            assertThat(buyer.get("/me").body()).doesNotContain("version", "AGORA_");
            var history = tree(admin.get("/admin/accounts/" + target + "/status-history"));
            assertThat(history.path("items").size()).isEqualTo(2);
            assertThat(history.path("items").get(0).path("version").asLong()).isEqualTo(2);
            assertThat(history.path("items").get(1).path("previousStatus").asString()).isEqualTo("ACTIVE");
            assertThat(history.path("items").get(1).path("actorId").asString()).isEqualTo(actor.toString());
        } finally { channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    @Test void protectedTargetsAuthorizationValidationAndConflictsNeverWriteAudit() throws Exception {
        Browser admin = new Browser(); UUID actor = admin.registerLogin(); grant(actor);
        Browser buyer = new Browser(); UUID target = buyer.registerLogin();
        assertThat(buyer.status(target, "SUSPENDED", 0).statusCode()).isEqualTo(403);
        assertThat(admin.status(actor, "SUSPENDED", 0).statusCode()).isEqualTo(403);
        Browser other = new Browser(); UUID otherAdmin = other.registerLogin(); grant(otherAdmin);
        assertThat(admin.status(otherAdmin, "SUSPENDED", 0).statusCode()).isEqualTo(403);
        assertThat(admin.status(target, "ACTIVE", 0).statusCode()).isEqualTo(409);
        assertThat(admin.status(target, "SUSPENDED", 3).statusCode()).isEqualTo(409);
        assertThat(admin.status(UUID.randomUUID(), "SUSPENDED", 0).statusCode()).isEqualTo(404);
        assertThat(admin.send("PUT", "/admin/accounts/" + target + "/status", body("SUSPENDED", 0, "too short"), admin.csrf()).statusCode()).isEqualTo(422);
        assertThat(admin.send("PUT", "/admin/accounts/" + target + "/status", body("SUSPENDED", 0, "Valid reason here"), null).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.account_status_audit", Integer.class)).isZero();
        jdbc.update("DELETE FROM identity.account_roles WHERE account_id = ? AND role = 'ADMIN'", actor);
        assertThat(admin.status(target, "SUSPENDED", 0).statusCode()).isEqualTo(403);
    }

    @Test void concurrentAdminsHaveOneWinnerAndAuditFailureRollsBackStatusAndSessions() throws Exception {
        Browser first = new Browser(); grant(first.registerLogin());
        Browser second = new Browser(); grant(second.registerLogin());
        Browser buyer = new Browser(); UUID target = buyer.registerLogin();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> first.status(target, "SUSPENDED", 0));
            var b = executor.submit(() -> second.status(target, "SUSPENDED", 0));
            assertThat(List.of(a.get().statusCode(), b.get().statusCode())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.account_status_audit WHERE target_id = ?", Integer.class, target)).isEqualTo(1);
        assertThat(first.status(target, "ACTIVE", 1).statusCode()).isEqualTo(200);
        buyer.login(); String cookie = buyer.cookie();
        jdbc.execute("ALTER TABLE identity.account_status_audit ADD CONSTRAINT reject_test_audit CHECK (reason <> 'Kiểm tra rollback dữ liệu')");
        try {
            var failed = first.send("PUT", "/admin/accounts/" + target + "/status", body("SUSPENDED", 2, "Kiểm tra rollback dữ liệu"), first.csrf());
            assertThat(failed.statusCode()).as(failed.body()).isEqualTo(503);
            assertThat(failed.body()).doesNotContain("INSERT", "reject_test_audit");
            assertThat(tree(first.get("/admin/accounts/" + target)).path("version").asLong()).isEqualTo(2);
            assertThat(buyer.rawGet("/me", cookie).statusCode()).isEqualTo(200);
        } finally { jdbc.execute("ALTER TABLE identity.account_status_audit DROP CONSTRAINT reject_test_audit"); }
    }

    @Test void loginThatSavesAfterSuspendAndUnlockKeepsItsVerifiedOldVersion() throws Exception {
        Browser admin = new Browser(); grant(admin.registerLogin());
        Browser buyer = new Browser(); UUID target = buyer.registerLogin();
        Browser delayed = new Browser(); delayed.email = buyer.email;
        var loaded = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            Object verified = invocation.callRealMethod();
            loaded.countDown();
            assertThat(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return verified;
        }).when(identity).login(org.mockito.ArgumentMatchers.eq(buyer.email), org.mockito.ArgumentMatchers.anyString());
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var login = executor.submit(delayed::login);
            assertThat(loaded.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(admin.status(target, "SUSPENDED", 0).statusCode()).isEqualTo(200);
            assertThat(admin.status(target, "ACTIVE", 1).statusCode()).isEqualTo(200);
            resume.countDown();
            assertThat(login.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(delayed.get("/me").statusCode()).isEqualTo(401);
            var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
            try {
                assertThatThrownBy(() -> rpc(channel).resolveSession(ResolveSessionRequest.newBuilder().setSessionId(delayed.cookie()).build()))
                        .isInstanceOfSatisfying(StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            } finally { channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
        } finally { resume.countDown(); }
    }

    @Test void lockAndUnlockDuringPasswordVerificationCannotBindTheNewVersion() throws Exception {
        Browser admin = new Browser(); grant(admin.registerLogin());
        Browser buyer = new Browser(); UUID target = buyer.registerLogin();
        String hash = jdbc.queryForObject("SELECT password_hash FROM identity.accounts WHERE id = ?", String.class, target);
        var verifying = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            verifying.countDown();
            assertThat(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(passwords).matches(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(hash));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Browser racing = new Browser(); racing.email = buyer.email;
            var login = executor.submit(racing::login);
            assertThat(verifying.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(admin.status(target, "SUSPENDED", 0).statusCode()).isEqualTo(200);
            assertThat(admin.status(target, "ACTIVE", 1).statusCode()).isEqualTo(200);
            resume.countDown();
            assertThat(login.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(401);
            assertThat(racing.get("/me").statusCode()).isEqualTo(401);
        } finally { resume.countDown(); }
    }

    @Test void transactionRechecksAdminAfterWaitingAndOperatorPromotionProtectsTarget() throws Exception {
        Browser admin = new Browser(); UUID actor = admin.registerLogin(); grant(actor);
        Browser buyer = new Browser(); UUID target = buyer.registerLogin();
        var source = jdbc.getDataSource();
        try (var connection = source.getConnection(); var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM identity.accounts WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, target); lock.executeQuery().close();
            }
            String csrf = admin.csrf();
            var pending = executor.submit(() -> admin.send("PUT", "/admin/accounts/" + target + "/status", body("SUSPENDED", 0, "Valid reason here"), csrf));
            awaitWaitingLock();
            try (var promote = connection.prepareStatement("INSERT INTO identity.account_roles(account_id,role) VALUES (?, 'ADMIN')")) {
                promote.setObject(1, target); promote.executeUpdate();
            }
            connection.commit();
            assertThat(pending.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(403);
            assertThat(tree(admin.get("/admin/accounts/" + target)).path("status").asString()).isEqualTo("ACTIVE");
        }
        jdbc.update("DELETE FROM identity.account_roles WHERE account_id = ? AND role = 'ADMIN'", target);
        // Hold the actor row so role revocation commits before the mutation's transactional check.
        try (var connection = source.getConnection(); var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM identity.accounts WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, actor); lock.executeQuery().close();
            }
            String csrf = admin.csrf();
            var pending = executor.submit(() -> admin.send("PUT", "/admin/accounts/" + target + "/status", body("SUSPENDED", 0, "Valid reason here"), csrf));
            awaitWaitingLock();
            try (var revoke = connection.prepareStatement("DELETE FROM identity.account_roles WHERE account_id = ? AND role = 'ADMIN'")) {
                revoke.setObject(1, actor); revoke.executeUpdate();
            }
            connection.commit();
            assertThat(pending.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.account_status_audit", Integer.class)).isZero();
    }
    void awaitWaitingLock() throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'SELECT id FROM identity.accounts%'", Integer.class) > 0) return;
            Thread.sleep(10);
        }
        fail("Mutation did not reach its account lock");
    }

    @Test void missingOrNonLongVersionStampFailsClosedAcrossBothTransports() throws Exception {
        Browser buyer = new Browser(); buyer.registerLogin();
        String cookie = buyer.cookie();
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        try {
            for (Object stamp : java.util.Arrays.asList(null, "0", Integer.valueOf(0), Long.valueOf(-1), Long.valueOf(1))) {
                org.springframework.session.Session loaded = sessions.findById(cookie);
                if (stamp == null) loaded.removeAttribute("AGORA_ACCOUNT_VERSION");
                else loaded.setAttribute("AGORA_ACCOUNT_VERSION", stamp);
                sessionStore().save(loaded);
                assertThat(buyer.rawGet("/me", cookie).statusCode()).isEqualTo(401);
                assertThatThrownBy(() -> rpc(channel).resolveSession(ResolveSessionRequest.newBuilder().setSessionId(cookie).build()))
                        .isInstanceOfSatisfying(StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            }
        } finally { channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    @Test void historyPaginationBindsTargetAndStrictInputRejectsCoercionAndUnknownFields() throws Exception {
        Browser admin = new Browser(); grant(admin.registerLogin());
        Browser buyer = new Browser(); UUID target = buyer.registerLogin();
        for (String invalid : List.of(
                "{\"status\":\"SUSPENDED\",\"expectedVersion\":\"0\",\"reason\":\"Valid reason here\"}",
                "{\"status\":\"SUSPENDED\",\"expectedVersion\":0.5,\"reason\":\"Valid reason here\"}",
                "{\"status\":\"SUSPENDED\",\"expectedVersion\":9007199254740992,\"reason\":\"Valid reason here\"}",
                "{\"status\":\"SUSPENDED\",\"expectedVersion\":null,\"reason\":\"Valid reason here\"}",
                "{\"status\":\"DELETED\",\"expectedVersion\":0,\"reason\":\"Valid reason here\"}",
                "{\"status\":\"SUSPENDED\",\"expectedVersion\":0,\"reason\":\"Valid reason here\",\"actorId\":\"spoof\"}"))
            assertThat(admin.send("PUT", "/admin/accounts/" + target + "/status", invalid, admin.csrf()).statusCode()).as(invalid).isEqualTo(422);
        assertThat(admin.send("PUT", "/admin/accounts/" + target + "/status", body("SUSPENDED", 0, "Valid reason\u0000"), admin.csrf()).statusCode()).isEqualTo(422);
        for (int i = 0; i < 21; i++) assertThat(admin.status(target, i % 2 == 0 ? "SUSPENDED" : "ACTIVE", i).statusCode()).isEqualTo(200);
        var first = tree(admin.get("/admin/accounts/" + target + "/status-history"));
        assertThat(first.path("items").size()).isEqualTo(20);
        String cursor = first.path("nextCursor").asString();
        var second = tree(admin.get("/admin/accounts/" + target + "/status-history?cursor=" + cursor));
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("version").asLong()).isEqualTo(1);
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(admin.get("/admin/accounts/" + UUID.randomUUID() + "/status-history?cursor=" + cursor).statusCode()).isEqualTo(422);
        assertThat(admin.get("/admin/accounts/" + target + "/status-history?cursor=x&cursor=y").statusCode()).isEqualTo(422);
        assertThat(admin.get("/admin/accounts/" + target + "/status-history?unknown=1").statusCode()).isEqualTo(422);
    }

    SessionIdentityGrpc.SessionIdentityBlockingStub rpc(ManagedChannel channel) {
        var headers = new Metadata();
        headers.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER), "admin-status-integration-token-not-real-secret");
        return SessionIdentityGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(20, java.util.concurrent.TimeUnit.SECONDS);
    }
    @SuppressWarnings("unchecked")
    org.springframework.session.SessionRepository<org.springframework.session.Session> sessionStore() {
        return (org.springframework.session.SessionRepository<org.springframework.session.Session>) (org.springframework.session.SessionRepository<?>) sessions;
    }
    void grant(UUID id) { jdbc.update("INSERT INTO identity.account_roles(account_id,role) VALUES (?, 'ADMIN')", id); }
    JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }
    String body(String status, long version, String reason) { return json.writeValueAsString(Map.of("status", status, "expectedVersion", version, "reason", reason)); }
    class Browser {
        String email = "status-" + UUID.randomUUID() + "@example.test";
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> send(String method, String path, String body, String csrf) throws Exception {
            var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).header("Content-Type", "application/json");
            if (csrf != null) b.header("X-CSRF-TOKEN", csrf);
            return client.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        String csrf() throws Exception { return tree(get("/auth/csrf")).path("token").asString(); }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        HttpResponse<String> status(UUID id, String status, long version) throws Exception { return send("PUT", "/admin/accounts/" + id + "/status", body(status, version, "Kiểm tra quản trị tài khoản"), csrf()); }
        UUID registerLogin() throws Exception {
            assertThat(send("POST", "/auth/registrations", json.writeValueAsString(Map.of("email", email, "displayName", "Status fixture", "password", "synthetic-long-password")), csrf()).statusCode()).isEqualTo(201);
            var r = login(); assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            return UUID.fromString(tree(r).path("id").asString());
        }
        HttpResponse<String> login() throws Exception { return send("POST", "/auth/sessions", json.writeValueAsString(Map.of("email", email, "password", "synthetic-long-password")), csrf()); }
        String cookie() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("AGORA_SESSION")).findFirst().orElseThrow().getValue(); }
        HttpResponse<String> rawGet(String path, String cookie) throws Exception {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).header("Cookie", "AGORA_SESSION=" + cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
