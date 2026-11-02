package com.agora.identity;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.agora.contracts.identity.v1.ResolveSessionRequest;
import com.agora.contracts.identity.v1.SessionIdentityGrpc;
import com.agora.identity.configuration.IdentityGrpcServer;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.agora.identity.application.IdentityService;
import static org.mockito.Mockito.doAnswer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityApiTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agora.auth.rate-limit.attempts", () -> 1000);
        properties.add("agora.grpc.enabled", () -> true);
        properties.add("agora.grpc.port", () -> 0);
        properties.add("agora.grpc.token", () -> "integration-test-internal-token-not-a-real-secret");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityGrpcServer grpc;
    @MockitoSpyBean IdentityService identity;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void registersOnlyUserWithNormalizedUniqueEmailAndNeverReturnsCredentials() throws Exception {
        Browser browser = new Browser();
        String email = "Account-" + UUID.randomUUID() + "@example.test";
        var created = browser.mutate("POST", "/auth/registrations", registration("  " + email + "  "));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode account = json.readTree(created.body());
        assertThat(account.path("email").asString()).isEqualTo(email.toLowerCase());
        assertThat(account.path("roles").toString()).isEqualTo("[\"USER\"]");
        assertThat(created.body()).doesNotContain("password", "$argon2", "correct-horse");
        String hash = jdbc.queryForObject("SELECT password_hash FROM identity.accounts WHERE email = ?",
                String.class, email.toLowerCase());
        assertThat(hash).startsWith("$argon2id$");
        assertThat(browser.request("GET", "/me", null, null).statusCode()).isEqualTo(401);
        assertThat(browser.mutate("POST", "/auth/registrations", registration(email.toLowerCase())).statusCode())
                .isEqualTo(409);
    }

    @Test
    void rejectsEscalationInvalidInputAndMissingCsrf() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.request("POST", "/auth/registrations", registration("a@example.test"), null)
                .statusCode()).isEqualTo(403);
        assertThat(browser.mutate("POST", "/auth/registrations",
                registration("bad-email")).statusCode()).isEqualTo(422);
        assertThat(browser.mutate("POST", "/auth/registrations",
                registration("role@example.test").replace("}", ",\"roles\":[\"ADMIN\"]}"))
                .statusCode()).isEqualTo(422);
        assertThat(browser.mutate("POST", "/auth/registrations", "{broken").statusCode()).isEqualTo(400);
    }

    @Test
    void buyerAndSellerRegisterSeparatelyAndShareOneEmailNamespace() throws Exception {
        Browser buyer = new Browser();
        String email = "typed-" + UUID.randomUUID() + "@example.test";
        var registeredBuyer = buyer.mutate("POST", "/auth/registrations", registration(email));
        assertThat(registeredBuyer.statusCode()).isEqualTo(201);
        assertThat(json.readTree(registeredBuyer.body()).path("accountType").asString()).isEqualTo("BUYER");
        Browser seller = new Browser();
        assertThat(seller.mutate("POST", "/auth/seller-registrations", registration(email.toUpperCase())).statusCode())
                .isEqualTo(409);
        String sellerEmail = "seller-" + UUID.randomUUID() + "@example.test";
        var registeredSeller = seller.mutate("POST", "/auth/seller-registrations", registration(sellerEmail));
        assertThat(registeredSeller.statusCode()).isEqualTo(201);
        var account = json.readTree(registeredSeller.body());
        assertThat(account.path("accountType").asString()).isEqualTo("SELLER");
        assertThat(account.path("roles").toString()).isEqualTo("[\"USER\"]");
        assertThat(account.path("id").asString()).isNotEqualTo(json.readTree(registeredBuyer.body()).path("id").asString());
        assertThat(seller.request("GET", "/me", null, null).statusCode()).isEqualTo(401);
        var loggedIn = seller.mutate("POST", "/auth/sessions", credentials(sellerEmail, "correct-horse battery"));
        assertThat(loggedIn.statusCode()).isEqualTo(200);
        assertThat(json.readTree(loggedIn.body()).path("accountType").asString()).isEqualTo("SELLER");
        assertThat(json.readTree(seller.request("GET", "/me", null, null).body()).path("accountType").asString())
                .isEqualTo("SELLER");
    }

    @Test
    void registrationsCannotChooseAccountTypeOrAdminRoleAndSellerNeedsCsrf() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.request("POST", "/auth/seller-registrations", registration("seller-csrf@example.test"), null)
                .statusCode()).isEqualTo(403);
        for (String path : java.util.List.of("/auth/registrations", "/auth/seller-registrations")) {
            for (String injected : java.util.List.of(",\"roles\":[\"ADMIN\"]", ",\"accountType\":\"SELLER\"")) {
                assertThat(browser.mutate("POST", path, registration("injected@example.test").replace("}", injected + "}"))
                        .statusCode()).isEqualTo(422);
            }
        }
        assertThat(browser.mutate("POST", "/auth/seller-registrations", registration("invalid-email")).statusCode()).isEqualTo(422);
    }

    @Test
    void unclassifiedLegacyAccountsAndSessionsWithoutTypeStampFailClosed() throws Exception {
        Browser browser = new Browser();
        String email = "legacy-" + UUID.randomUUID() + "@example.test";
        browser.mutate("POST", "/auth/registrations", registration(email));
        browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery"));
        String sessionId = browser.sessionCookie();
        jdbc.update("DELETE FROM identity.spring_session_attributes WHERE attribute_name = 'AGORA_ACCOUNT_TYPE' "
                + "AND session_primary_id IN (SELECT primary_id FROM identity.spring_session WHERE session_id = ?)", sessionId);
        assertThat(browser.request("GET", "/me", null, null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE identity.accounts SET account_type = NULL WHERE email = ?", email);
        assertThat(browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery")).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE identity.accounts SET account_type = 'BUYER' WHERE email = ?", email);
        assertThat(browser.request("GET", "/me", null, null).statusCode()).isEqualTo(401);
        assertThat(browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery")).statusCode()).isEqualTo(200);
    }

    @Test
    void stampedHttpSessionCannotSurviveMissingOrChangedAccountType() throws Exception {
        Browser browser = new Browser();
        String email = "stamp-http-" + UUID.randomUUID() + "@example.test";
        browser.mutate("POST", "/auth/registrations", registration(email));
        browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery"));
        jdbc.update("UPDATE identity.accounts SET account_type = NULL WHERE email = ?", email);
        assertThat(browser.request("GET", "/me", null, null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE identity.accounts SET account_type = 'SELLER' WHERE email = ?", email);
        assertThat(browser.request("GET", "/me", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void grpcResolvesSellerTypeAndRejectsLegacyMissingAndMismatchedStamps() throws Exception {
        Browser browser = new Browser();
        String email = "stamp-rpc-" + UUID.randomUUID() + "@example.test";
        browser.mutate("POST", "/auth/seller-registrations", registration(email));
        browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery"));
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        var metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER),
                "integration-test-internal-token-not-a-real-secret");
        var stub = SessionIdentityGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS);
        var request = ResolveSessionRequest.newBuilder().setSessionId(browser.sessionCookie()).build();
        try {
            assertThat(stub.resolveSession(request).getAccountType().name()).isEqualTo("SELLER");
            jdbc.update("UPDATE identity.accounts SET account_type = NULL WHERE email = ?", email);
            assertThatThrownBy(() -> stub.resolveSession(request)).isInstanceOfSatisfying(StatusRuntimeException.class,
                    e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            jdbc.update("UPDATE identity.accounts SET account_type = 'BUYER' WHERE email = ?", email);
            assertThatThrownBy(() -> stub.resolveSession(request)).isInstanceOfSatisfying(StatusRuntimeException.class,
                    e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            jdbc.update("UPDATE identity.accounts SET account_type = 'SELLER' WHERE email = ?", email);
            jdbc.update("DELETE FROM identity.spring_session_attributes WHERE attribute_name = 'AGORA_ACCOUNT_TYPE' "
                    + "AND session_primary_id IN (SELECT primary_id FROM identity.spring_session WHERE session_id = ?)", browser.sessionCookie());
            assertThatThrownBy(() -> stub.resolveSession(request)).isInstanceOfSatisfying(StatusRuntimeException.class,
                    e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        } finally { channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    @Test
    void privateBatchStatesExposeOnlyRequestedClassifiedAccountsAndEnforceBounds() throws Exception {
        Browser browser = new Browser();
        String email = "states-" + UUID.randomUUID() + "@example.test";
        var created = browser.mutate("POST", "/auth/seller-registrations", registration(email));
        String id = json.readTree(created.body()).path("id").asString();
        var request = com.agora.contracts.identity.v1.GetAccountStatesRequest.newBuilder().addAccountIds(id)
                .addAccountIds(UUID.randomUUID().toString()).build();
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        var metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER),
                "integration-test-internal-token-not-a-real-secret");
        var stub = SessionIdentityGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        try {
            var state = stub.getAccountStates(request);
            assertThat(state.getAccountsCount()).isEqualTo(1);
            assertThat(state.getAccounts(0).getAccountId()).isEqualTo(id);
            assertThat(state.getAccounts(0).getAccountType().name()).isEqualTo("SELLER");
            assertThat(state.getAccounts(0).getIsActive()).isTrue();
            jdbc.update("UPDATE identity.accounts SET status = 'SUSPENDED' WHERE email = ?", email);
            assertThat(stub.getAccountStates(request).getAccounts(0).getIsActive()).isFalse();
            jdbc.update("UPDATE identity.accounts SET account_type = NULL WHERE email = ?", email);
            assertThat(stub.getAccountStates(request).getAccountsCount()).isZero();
            assertThatThrownBy(() -> SessionIdentityGrpc.newBlockingStub(channel).getAccountStates(request))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
            var empty = com.agora.contracts.identity.v1.GetAccountStatesRequest.newBuilder();
            var oversized = empty.clone();
            for (int i = 0; i < 6; i++) oversized.addAccountIds(UUID.randomUUID().toString());
            for (var invalid : java.util.List.of(empty.build(), request.toBuilder().addAccountIds(id).build(),
                    empty.clone().addAccountIds("not-a-uuid").build(), oversized.build())) {
                assertThatThrownBy(() -> stub.getAccountStates(invalid)).isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
            }
        } finally { channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    private String registration(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"correct-horse battery\",\"displayName\":\"Quốc Anh\"}";
    }

    @Test
    void rotatesSessionOnLoginClearsCsrfAndInvalidatesLogoutCookie() throws Exception {
        Browser browser = new Browser();
        String email = "login-" + UUID.randomUUID() + "@example.test";
        assertThat(browser.mutate("POST", "/auth/registrations", registration(email)).statusCode()).isEqualTo(201);
        var csrf = browser.request("GET", "/auth/csrf", null, null);
        String oldToken = json.readTree(csrf.body()).path("token").asString();
        String oldCookie = browser.sessionCookie();
        var login = browser.request("POST", "/auth/sessions", credentials(email, "correct-horse battery"), oldToken);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(browser.sessionCookie()).isNotEqualTo(oldCookie);
        assertThat(login.headers().allValues("set-cookie").toString()).contains("HttpOnly", "SameSite=Lax", "Path=/");
        assertThat(browser.request("GET", "/me", null, null).statusCode()).isEqualTo(200);
        assertThat(browser.request("DELETE", "/auth/sessions/current", null, oldToken).statusCode()).isEqualTo(403);
        String authenticatedCookie = browser.sessionCookie();
        assertThat(browser.mutate("DELETE", "/auth/sessions/current", null).statusCode()).isEqualTo(204);
        assertThat(browser.request("GET", "/me", null, null).statusCode()).isEqualTo(401);
        Browser stolen = new Browser();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Cookie", "AGORA_SESSION=" + authenticatedCookie).GET().build();
        assertThat(stolen.client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void wrongPasswordMissingAccountAndSuspendedAccountReturnSameGeneric401() throws Exception {
        Browser browser = new Browser();
        String email = "disabled-" + UUID.randomUUID() + "@example.test";
        assertThat(browser.mutate("POST", "/auth/registrations", registration(email)).statusCode()).isEqualTo(201);
        var wrong = browser.mutate("POST", "/auth/sessions", credentials(email, "incorrect-password"));
        var missing = browser.mutate("POST", "/auth/sessions", credentials("missing@example.test", "incorrect-password"));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(json.readTree(wrong.body()).path("code")).isEqualTo(json.readTree(missing.body()).path("code"));
        assertThat(browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery")).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE identity.accounts SET status = 'SUSPENDED' WHERE email = ?", email);
        assertThat(browser.request("GET", "/me", null, null).statusCode()).isEqualTo(401);
        assertThat(browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery")).statusCode()).isEqualTo(401);
    }

    private String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void merchantResolvesRealSessionOverGrpcWithFreshRolesAndCsrfAndFailsClosed() throws Exception {
        Browser browser = new Browser();
        String email = "grpc-" + UUID.randomUUID() + "@example.test";
        browser.mutate("POST", "/auth/registrations", registration(email));
        var login = browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery"));
        String accountId = json.readTree(login.body()).path("id").asString();
        String csrf = json.readTree(browser.request("GET", "/auth/csrf", null, null).body()).path("token").asString();
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        try {
            var metadata = new Metadata();
            metadata.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER),
                    "integration-test-internal-token-not-a-real-secret");
            var stub = SessionIdentityGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .withDeadlineAfter(2, java.util.concurrent.TimeUnit.SECONDS);
            var resolve = ResolveSessionRequest.newBuilder().setSessionId(browser.sessionCookie()).setCsrfToken(csrf).setRequireCsrf(true).build();
            assertThat(stub.resolveSession(resolve).getAccountId()).isEqualTo(accountId);
            assertThat(stub.resolveSession(resolve).getRolesList()).containsExactly("USER");
            assertThat(stub.resolveSession(resolve).getAccountType().name()).isEqualTo("BUYER");
            assertThatThrownBy(() -> stub.resolveSession(resolve.toBuilder().setCsrfToken("wrong").build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure ->
                            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
            assertThatThrownBy(() -> SessionIdentityGrpc.newBlockingStub(channel).resolveSession(resolve))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure ->
                            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
            jdbc.update("INSERT INTO identity.account_roles(account_id,role) VALUES (?::uuid,'ADMIN')", accountId);
            assertThat(stub.resolveSession(resolve).getRolesList()).containsExactlyInAnyOrder("USER", "ADMIN");
            browser.mutate("DELETE", "/auth/sessions/current", null);
            assertThatThrownBy(() -> stub.resolveSession(resolve))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure ->
                            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        } finally { channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    @Test
    void simultaneousRegistrationCannotCreateTwoAccountsForSameEmail() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.test";
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> new Browser().mutate("POST", "/auth/registrations", registration(email)).statusCode());
            var second = executor.submit(() -> new Browser().mutate("POST", "/auth/registrations", registration(email.toUpperCase())).statusCode());
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.accounts WHERE email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    @Test
    void grpcRejectsAnonymousSuspendedAndExpiredSessionsAndRefreshesActiveIdleTimeout() throws Exception {
        Browser browser = new Browser();
        String email = "grpc-states-" + UUID.randomUUID() + "@example.test";
        browser.mutate("POST", "/auth/registrations", registration(email));
        String anonymousId = browser.sessionCookie();
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        var metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER),
                "integration-test-internal-token-not-a-real-secret");
        var stub = SessionIdentityGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        try {
            assertThatThrownBy(() -> stub.withDeadlineAfter(2, java.util.concurrent.TimeUnit.SECONDS)
                    .resolveSession(ResolveSessionRequest.newBuilder().setSessionId(anonymousId).build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure ->
                            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery"));
            String cookie = browser.sessionCookie();
            var resolve = ResolveSessionRequest.newBuilder().setSessionId(cookie).build();
            long tenMinutesAgo = java.time.Instant.now().minusSeconds(600).toEpochMilli();
            jdbc.update("UPDATE identity.spring_session SET last_access_time = ?, expiry_time = ? WHERE session_id = ?",
                    tenMinutesAgo, tenMinutesAgo + 1_800_000, cookie);
            stub.withDeadlineAfter(2, java.util.concurrent.TimeUnit.SECONDS).resolveSession(resolve);
            assertThat(jdbc.queryForObject("SELECT last_access_time FROM identity.spring_session WHERE session_id = ?", Long.class, cookie))
                    .isGreaterThan(tenMinutesAgo);
            jdbc.update("UPDATE identity.accounts SET status = 'SUSPENDED' WHERE email = ?", email);
            assertThatThrownBy(() -> stub.withDeadlineAfter(2, java.util.concurrent.TimeUnit.SECONDS).resolveSession(resolve))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure ->
                            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            jdbc.update("UPDATE identity.accounts SET status = 'ACTIVE' WHERE email = ?", email);
            long expired = java.time.Instant.now().minusSeconds(3600).toEpochMilli();
            jdbc.update("UPDATE identity.spring_session SET last_access_time = ?, expiry_time = ? WHERE session_id = ?", expired, expired, cookie);
            assertThatThrownBy(() -> stub.withDeadlineAfter(2, java.util.concurrent.TimeUnit.SECONDS).resolveSession(resolve))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure ->
                            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        } finally { channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    @Test
    void concurrentLoginRotationCannotBeUndoneByAnOlderGrpcRequest() throws Exception {
        Browser browser = new Browser();
        String email = "rotation-race-" + UUID.randomUUID() + "@example.test";
        browser.mutate("POST", "/auth/registrations", registration(email));
        var login = browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery"));
        UUID accountId = UUID.fromString(json.readTree(login.body()).path("id").asString());
        String oldCookie = browser.sessionCookie();
        var loaded = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            loaded.countDown();
            assertThat(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(identity).authenticated(org.mockito.ArgumentMatchers.eq(accountId), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpc.port()).usePlaintext().build();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var metadata = new Metadata();
            metadata.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER),
                    "integration-test-internal-token-not-a-real-secret");
            var stub = SessionIdentityGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS);
            var inFlight = executor.submit(() -> {
                try { stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId(oldCookie).build()); return Status.Code.OK; }
                catch (StatusRuntimeException exception) { return exception.getStatus().getCode(); }
            });
            assertThat(loaded.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(browser.mutate("POST", "/auth/sessions", credentials(email, "correct-horse battery")).statusCode()).isEqualTo(200);
            assertThat(browser.sessionCookie()).isNotEqualTo(oldCookie);
            resume.countDown();
            assertThat(inFlight.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(Status.Code.UNAUTHENTICATED);
            assertThat(browser.request("GET", "/me", null, null).statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.spring_session WHERE session_id = ?", Integer.class, oldCookie)).isZero();
        } finally { resume.countDown(); channel.shutdownNow().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

        String sessionCookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("AGORA_SESSION"))
                    .findFirst().orElseThrow().getValue();
        }

        HttpResponse<String> mutate(String method, String path, String body) throws Exception {
            var csrf = request("GET", "/auth/csrf", null, null);
            assertThat(csrf.statusCode()).isEqualTo(200);
            return request(method, path, body, json.readTree(csrf.body()).path("token").asString());
        }

        HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (token != null) request.header("X-CSRF-TOKEN", token);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
