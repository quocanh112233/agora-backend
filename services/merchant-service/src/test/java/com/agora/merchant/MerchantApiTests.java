package com.agora.merchant;

import com.agora.merchant.application.IdentityPort;
import com.agora.merchant.adapter.outbound.JdbcMerchantStore;
import com.agora.merchant.domain.MerchantFailure;
import java.net.URI;
import java.net.http.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MerchantApiTests.IdentityFixture.class)
class MerchantApiTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
        p.add("agora.identity.token", () -> "merchant-test-internal-token-long-enough");
    }
    @TestConfiguration(proxyBeanMethods = false) static class IdentityFixture {
        @Bean @Primary FakeIdentity fakeIdentity() { return new FakeIdentity(); }
    }
    static class FakeIdentity implements IdentityPort {
        final Map<String, UUID> sessions = new ConcurrentHashMap<>();
        final Map<UUID, Set<String>> roles = new ConcurrentHashMap<>();
        @Override public Principal resolve(String session, String csrf, boolean unsafe) {
            UUID actor = session == null ? null : sessions.get(session);
            if (actor == null) throw new MerchantFailure(401, "UNAUTHENTICATED", "Invalid session.");
            if (unsafe && !"masked-csrf-fixture".equals(csrf))
                throw new MerchantFailure(403, "FORBIDDEN", "Invalid CSRF.");
            return new Principal(actor, roles.getOrDefault(actor, Set.of("USER")));
        }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeIdentity identity;
    @Autowired JdbcMerchantStore store;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    static final String BODY = """
            {"name":"Fresh fish","description":"Local catch","contactPhone":"0901234567",
             "pickupAddress":{"countryCode":"VN","provinceCode":"opaque-P","provinceName":"Province",
             "wardCode":"opaque-W","wardName":"Ward","streetAddress":"12 Street"}}
            """;
    String session() {
        String raw = "raw+opaque/" + UUID.randomUUID() + "==";
        identity.sessions.put(raw, UUID.randomUUID());
        return raw;
    }
    HttpResponse<String> request(String method, String path, String body, String session, String key, String csrf)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").header("X-User-Id", UUID.randomUUID().toString());
        if (session != null) b.header("Cookie", "AGORA_SESSION=" + session);
        if (key != null) b.header("Idempotency-Key", key);
        if (csrf != null) b.header("X-CSRF-TOKEN", csrf);
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> mutate(String method, String path, String body, String session, String key) throws Exception {
        return request(method, path, body, session, key, "masked-csrf-fixture");
    }
    HttpResponse<String> create(String session, String key) throws Exception {
        return mutate("POST", "/api/v1/shops", BODY, session, key);
    }
    String key() { return UUID.randomUUID().toString(); }
    JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }
    String shopPath(HttpResponse<String> r) { return "/api/v1/shops/" + tree(r).path("id").asString(); }
    String admin() {
        String s = session();
        identity.roles.put(identity.sessions.get(s), Set.of("USER", "ADMIN"));
        return s;
    }
    String reviewPath(HttpResponse<String> shop) { return "/api/v1/admin/shops/" + tree(shop).path("id").asString() + "/reviews"; }
    HttpResponse<String> submit(HttpResponse<String> shop, String owner, long version, String key) throws Exception {
        return mutate("POST", shopPath(shop) + "/submissions", "{\"expectedVersion\":" + version + "}", owner, key);
    }
    HttpResponse<String> review(HttpResponse<String> shop, String admin, String body, String key) throws Exception {
        return mutate("POST", reviewPath(shop), body, admin, key);
    }

    @Test void rejectsUsersAndAnonymousFromAdminRoutesBeforeClaimingKeys() throws Exception {
        String owner = session(), user = session();
        var shop = create(owner, key());
        assertThat(request("GET", "/api/v1/admin/shops", null, null, null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/v1/admin/shops", null, user, null, null).statusCode()).isEqualTo(403);
        assertThat(review(shop, user, "{\"decision\":\"APPROVE\",\"expectedVersion\":0}", key()).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from merchant.idempotency_records where actor_id=?", Integer.class,
                identity.sessions.get(user))).isZero();
    }

    @Test void rejectedShopCanBeEditedAndResubmittedThenApprovedWithCorrectAuditActors() throws Exception {
        String owner = session(), admin = admin();
        UUID adminId = identity.sessions.get(admin);
        var shop = create(owner, key());
        var pending = submit(shop, owner, 0, key());
        assertThat(pending.statusCode()).isEqualTo(200);
        assertThat(tree(pending).path("status").asString()).isEqualTo("PENDING_REVIEW");
        assertThat(tree(pending).path("version").asLong()).isEqualTo(1);
        assertThat(mutate("PATCH", shopPath(shop), "{\"expectedVersion\":1,\"name\":\"Blocked edit\"}", owner, key()).statusCode()).isEqualTo(409);
        String reason = "Please correct pickup address";
        var rejected = review(shop, admin, "{\"decision\":\"REJECT\",\"expectedVersion\":1,\"reason\":\"" + reason + "\"}", key());
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(tree(rejected).path("status").asString()).isEqualTo("REJECTED");
        assertThat(tree(rejected).path("lastReviewReason").asString()).isEqualTo(reason);
        assertThat(submit(shop, owner, 2, key()).statusCode()).isEqualTo(409);
        var edited = mutate("PATCH", shopPath(shop), "{\"expectedVersion\":2,\"name\":\"Corrected shop\"}", owner, key());
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(tree(edited).path("status").asString()).isEqualTo("DRAFT");
        assertThat(tree(edited).path("version").asLong()).isEqualTo(3);
        assertThat(submit(shop, owner, 3, key()).statusCode()).isEqualTo(200);
        var active = review(shop, admin, "{\"decision\":\"APPROVE\",\"expectedVersion\":4}", key());
        assertThat(active.statusCode()).isEqualTo(200);
        assertThat(tree(active).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(tree(active).path("version").asLong()).isEqualTo(5);
        assertThat(tree(active).path("lastReviewReason").isNull()).isTrue();
        assertThat(submit(shop, owner, 5, key()).statusCode()).isEqualTo(409);
        assertThat(mutate("PATCH", shopPath(shop), "{\"expectedVersion\":5,\"name\":\"Blocked edit\"}", owner, key()).statusCode()).isEqualTo(409);
        UUID id = UUID.fromString(tree(shop).path("id").asString());
        assertThat(jdbc.queryForList("select reviewed_version from merchant.shop_reviews where shop_id=? order by reviewed_version",
                Long.class, id)).containsExactly(1L, 4L);
        assertThat(jdbc.queryForList("select actor_account_id from merchant.audit_entries where shop_id=? and action='REVIEW_SHOP'",
                UUID.class, id)).containsExactly(adminId, adminId);
        assertThat(jdbc.queryForObject("select count(*) from merchant.audit_entries where shop_id=?", Integer.class, id)).isEqualTo(6);
    }

    @Test void submitAndReviewValidateShapeReasonLengthsAndCsrf() throws Exception {
        String owner = session(), admin = admin(); var shop = create(owner, key());
        for (String body : List.of("{}", "{\"expectedVersion\":-1}", "{\"expectedVersion\":0.5}",
                "{\"expectedVersion\":0,\"status\":\"PENDING_REVIEW\"}", "{\"expectedVersion\":\"0\"}"))
            assertThat(mutate("POST", shopPath(shop) + "/submissions", body, owner, key()).statusCode()).isEqualTo(422);
        assertThat(submit(shop, owner, 0, key()).statusCode()).isEqualTo(200);
        for (String body : List.of("{}", "{\"decision\":\"OTHER\",\"expectedVersion\":1}",
                "{\"decision\":\"REJECT\",\"expectedVersion\":1}",
                "{\"decision\":\"REJECT\",\"expectedVersion\":1,\"reason\":null}",
                "{\"decision\":\"REJECT\",\"expectedVersion\":1,\"reason\":\"123456789\"}",
                "{\"decision\":\"REJECT\",\"expectedVersion\":1,\"reason\":\"" + "x".repeat(1001) + "\"}",
                "{\"decision\":\"APPROVE\",\"expectedVersion\":1,\"reason\":\"short\"}",
                "{\"decision\":\"APPROVE\",\"expectedVersion\":1,\"reason\":null}",
                "{\"decision\":\"APPROVE\",\"expectedVersion\":1,\"role\":\"ADMIN\"}"))
            assertThat(review(shop, admin, body, key()).statusCode()).as(body).isEqualTo(422);
        assertThat(review(shop, admin, "{broken", key()).statusCode()).isEqualTo(400);
        assertThat(request("POST", reviewPath(shop), "{\"decision\":\"APPROVE\",\"expectedVersion\":1}", admin, key(), null)
                .statusCode()).isEqualTo(403);
        for (int length : new int[]{10, 1000}) {
            String o = session(); var sh = create(o, key()); submit(sh, o, 0, key());
            assertThat(review(sh, admin, "{\"decision\":\"REJECT\",\"expectedVersion\":1,\"reason\":\"" + "x".repeat(length) + "\"}", key())
                    .statusCode()).isEqualTo(200);
        }
    }

    @Test void submissionsConcealOwnershipAndReplayOriginalStateWithStaleAndMismatchGuards() throws Exception {
        String owner = session(), other = session(), k = key(); var shop = create(owner, key());
        assertThat(submit(shop, other, 0, key()).statusCode()).isEqualTo(404);
        assertThat(submit(shop, owner, 8, key()).statusCode()).isEqualTo(409);
        var first = submit(shop, owner, 0, k);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(tree(submit(shop, owner, 0, k))).isEqualTo(tree(first));
        assertThat(submit(shop, owner, 1, k).statusCode()).isEqualTo(422);
        assertThat(submit(shop, owner, 1, key()).statusCode()).isEqualTo(409);
        assertThat(mutate("POST", "/api/v1/shops/" + UUID.randomUUID() + "/submissions", "{\"expectedVersion\":0}", owner, k)
                .statusCode()).isEqualTo(422);
        identity.sessions.remove(owner);
        assertThat(submit(shop, owner, 0, k).statusCode()).isEqualTo(401);
    }

    @Test void reviewReplayReauthorizesFreshAdminRoleAndDoesNotDuplicateReviews() throws Exception {
        String owner = session(), admin = admin(), k = key(); var shop = create(owner, key());
        submit(shop, owner, 0, key());
        String body = "{\"decision\":\"APPROVE\",\"expectedVersion\":1}";
        var first = review(shop, admin, body, k);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(tree(review(shop, admin, body, k))).isEqualTo(tree(first));
        assertThat(review(shop, admin, "{\"decision\":\"REJECT\",\"expectedVersion\":1,\"reason\":\"Another decision\"}", k)
                .statusCode()).isEqualTo(422);
        assertThat(review(shop, admin, body, key()).statusCode()).isEqualTo(409);
        assertThat(mutate("POST", "/api/v1/admin/shops/" + UUID.randomUUID() + "/reviews", body, admin, k).statusCode()).isEqualTo(422);
        assertThat(mutate("POST", "/api/v1/admin/shops/" + UUID.randomUUID() + "/reviews", body, admin, key()).statusCode()).isEqualTo(404);
        identity.roles.put(identity.sessions.get(admin), Set.of("USER"));
        assertThat(review(shop, admin, body, k).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/v1/admin/shops", null, admin, null, null).statusCode()).isEqualTo(403);
        UUID id = UUID.fromString(tree(shop).path("id").asString());
        assertThat(jdbc.queryForObject("select count(*) from merchant.shop_reviews where shop_id=?", Integer.class, id)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into merchant.shop_reviews(id,shop_id,actor_account_id,decision,reason,reviewed_version) "
                + "select ?,shop_id,actor_account_id,decision,reason,reviewed_version from merchant.shop_reviews where shop_id=?", UUID.randomUUID(), id))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test void twoAdminsReviewingSameVersionHaveExactlyOneWinner() throws Exception {
        String owner = session(), a = admin(), b = admin(); var shop = create(owner, key()); submit(shop, owner, 0, key());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var ra = pool.submit(() -> { gate.await(); return review(shop, a, "{\"decision\":\"APPROVE\",\"expectedVersion\":1}", key()); });
            var rb = pool.submit(() -> { gate.await(); return review(shop, b, "{\"decision\":\"REJECT\",\"expectedVersion\":1,\"reason\":\"Fix this address\"}", key()); });
            gate.countDown();
            assertThat(List.of(ra.get(10, TimeUnit.SECONDS).statusCode(), rb.get(10, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        UUID id = UUID.fromString(tree(shop).path("id").asString());
        assertThat(jdbc.queryForObject("select count(*) from merchant.shop_reviews where shop_id=?", Integer.class, id)).isEqualTo(1);
        UUID winner = jdbc.queryForObject("select actor_account_id from merchant.shop_reviews where shop_id=?", UUID.class, id);
        assertThat(jdbc.queryForObject("select actor_account_id from merchant.audit_entries where shop_id=? and action='REVIEW_SHOP'", UUID.class, id))
                .isEqualTo(winner);
        assertThat(jdbc.queryForObject("select version from merchant.shops where id=?", Long.class, id)).isEqualTo(2);
    }

    @Test void adminPaginationUsesStableTieBreakFilterAndBounds() throws Exception {
        String admin = admin(); var ids = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            String owner = session(); var shop = create(owner, key());
            String id = tree(shop).path("id").asString(); ids.add(id);
            jdbc.update("update merchant.shops set created_at='2035-01-01T00:00:00Z' where id=?", UUID.fromString(id));
        }
        ids.sort(Comparator.reverseOrder());
        var defaults = request("GET", "/api/v1/admin/shops", null, admin, null, null);
        assertThat(defaults.statusCode()).isEqualTo(200);
        assertThat(defaults.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(tree(defaults).path("page").asInt()).isZero();
        assertThat(tree(defaults).path("pageSize").asInt()).isEqualTo(20);
        for (int page = 0; page < 2; page++) {
            var response = request("GET", "/api/v1/admin/shops?page=" + page + "&pageSize=2", null, admin, null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            var actual = tree(response).path("items").valueStream().map(n -> n.path("id").asString()).toList();
            assertThat(actual).containsExactlyElementsOf(ids.subList(page * 2, page * 2 + 2));
        }
        for (String status : List.of("DRAFT", "PENDING_REVIEW", "ACTIVE", "REJECTED", "SUSPENDED")) {
            var result = request("GET", "/api/v1/admin/shops?status=" + status + "&pageSize=100", null, admin, null, null);
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(tree(result).path("totalItems").asLong()).isEqualTo(jdbc.queryForObject("select count(*) from merchant.shops where status=?", Long.class, status));
            assertThat(tree(result).path("items").valueStream().map(n -> n.path("status").asString()).toList()).allMatch(status::equals);
        }
        var empty = request("GET", "/api/v1/admin/shops?page=2147483647&pageSize=100", null, admin, null, null);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(tree(empty).path("items").size()).isZero();
        for (String query : List.of("page=-1", "page=x", "page=0.5", "pageSize=0", "pageSize=101", "status=OTHER", "status="))
            assertThat(request("GET", "/api/v1/admin/shops?" + query, null, admin, null, null).statusCode()).as(query).isEqualTo(422);
    }

    @Test void reviewAuditOrResultFailureRollsBackShopReviewAuditAndKey() throws Exception {
        String owner = session(), admin = admin(); var shop = create(owner, key()); submit(shop, owner, 0, key());
        UUID actor = identity.sessions.get(admin), id = UUID.fromString(tree(shop).path("id").asString());
        for (String boundary : List.of("review", "audit", "result")) {
            String table = switch (boundary) { case "review" -> "shop_reviews"; case "audit" -> "audit_entries"; default -> "idempotency_records"; };
            String rule = boundary.equals("result") ? "actor_id <> '" + actor + "'::uuid or state <> 'COMPLETED'"
                    : "actor_account_id <> '" + actor + "'::uuid";
            jdbc.execute("alter table merchant." + table + " add constraint test_review_failure check (" + rule + ") not valid");
            try {
                var failed = review(shop, admin, "{\"decision\":\"APPROVE\",\"expectedVersion\":1}", key());
                assertThat(failed.statusCode()).as(boundary).isEqualTo(500);
                assertThat(failed.body()).doesNotContain("test_review_failure", "SQLException", "INSERT");
                assertThat(jdbc.queryForObject("select version from merchant.shops where id=?", Long.class, id)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from merchant.shop_reviews where shop_id=?", Integer.class, id)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from merchant.audit_entries where shop_id=?", Integer.class, id)).isEqualTo(2);
                assertThat(jdbc.queryForObject("select count(*) from merchant.idempotency_records where actor_id=?", Integer.class, actor)).isZero();
            } finally { jdbc.execute("alter table merchant." + table + " drop constraint test_review_failure"); }
        }
        assertThat(review(shop, admin, "{\"decision\":\"APPROVE\",\"expectedVersion\":1}", key()).statusCode()).isEqualTo(200);
    }

    @Test void submitAuditOrResultFailureRollsBackShopAuditAndKey() throws Exception {
        String owner = session();
        var shop = create(owner, key());
        UUID actor = identity.sessions.get(owner), id = UUID.fromString(tree(shop).path("id").asString());
        for (String boundary : List.of("audit", "result")) {
            String table = boundary.equals("audit") ? "audit_entries" : "idempotency_records";
            String rule = boundary.equals("audit") ? "actor_account_id <> '" + actor + "'::uuid"
                    : "actor_id <> '" + actor + "'::uuid or state <> 'COMPLETED'";
            jdbc.execute("alter table merchant." + table + " add constraint test_submit_failure check (" + rule + ") not valid");
            String key = key();
            try {
                var failed = submit(shop, owner, 0, key);
                assertThat(failed.statusCode()).as(boundary).isEqualTo(500);
                assertThat(failed.body()).doesNotContain("test_submit_failure", "SQLException", "UPDATE");
                assertThat(tree(request("GET", "/api/v1/me/shop", null, owner, null, null))).isEqualTo(tree(shop));
                assertThat(jdbc.queryForObject("select count(*) from merchant.audit_entries where shop_id=?", Integer.class, id)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from merchant.idempotency_records where actor_id=? and operation='submitShop'",
                        Integer.class, actor)).isZero();
            } finally { jdbc.execute("alter table merchant." + table + " drop constraint test_submit_failure"); }
        }
        assertThat(submit(shop, owner, 0, key()).statusCode()).isEqualTo(200);
    }

    @Test void healthMigrationsAndSeparateDatabaseDefaults() throws Exception {
        assertThat(request("GET", "/actuator/health", null, null, null, null).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select current_setting('server_version')", String.class)).startsWith("16.");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='identity'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from merchant.flyway_schema_history where success and version='1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from merchant.flyway_schema_history where success and version='2'", Integer.class))
                .isEqualTo(1);
    }

    @Test void createsReadsPatchesDraftAndReplaysOriginalSnapshotWithoutDuplicateAudit() throws Exception {
        String session = session(), key = key();
        assertThat(request("GET", "/api/v1/me/shop", null, session, null, null).statusCode()).isEqualTo(404);
        var created = create(session, key);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location")).contains("/api/v1/me/shop");
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        UUID.fromString(created.headers().firstValue("X-Trace-Id").orElseThrow());
        assertThat(tree(created).path("ownerAccountId").asString()).isEqualTo(identity.sessions.get(session).toString());
        assertThat(tree(created).path("status").asString()).isEqualTo("DRAFT");
        assertThat(tree(created).path("version").asLong()).isZero();
        assertThat(created.body()).doesNotContain("verified", "ghn", "session", "csrf");
        String patch = "{\"expectedVersion\":0,\"description\":null,\"name\":\"Updated shop\"}";
        String patchKey = key();
        var changed = mutate("PATCH", shopPath(created), patch, session, patchKey);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(tree(changed).path("version").asLong()).isEqualTo(1);
        assertThat(tree(changed).path("description").isNull()).isTrue();
        assertThat(tree(changed).path("contactPhone").asString()).isEqualTo("0901234567");
        assertThat(tree(request("GET", "/api/v1/me/shop", null, session, null, null))).isEqualTo(tree(changed));
        assertThat(tree(create(session, key))).isEqualTo(tree(created));
        assertThat(tree(mutate("PATCH", shopPath(created), patch, session, patchKey))).isEqualTo(tree(changed));
        assertThat(jdbc.queryForObject("select count(*) from merchant.audit_entries where shop_id=?", Integer.class,
                UUID.fromString(tree(created).path("id").asString()))).isEqualTo(2);
        assertThat(jdbc.queryForObject("select min(expires_at-created_at) >= interval '168 hours' from merchant.idempotency_records where actor_id=?",
                Boolean.class, identity.sessions.get(session))).isTrue();
    }

    @Test void reauthenticatesReplayAndRejectsSpoofingAndMissingCsrf() throws Exception {
        assertThat(request("GET", "/api/v1/me/shop", null, null, null, null).statusCode()).isEqualTo(401);
        String s = session(), k = key();
        assertThat(request("POST", "/api/v1/shops", BODY, s, k, null).statusCode()).isEqualTo(403);
        assertThat(create(s, k).statusCode()).isEqualTo(201);
        assertThat(request("POST", "/api/v1/shops", BODY, s, k, "wrong").statusCode()).isEqualTo(403);
        identity.sessions.remove(s);
        assertThat(create(s, k).statusCode()).isEqualTo(401);
    }

    @Test void strictSchemaBadJsonUuidAndPatchPresence() throws Exception {
        String s = session();
        List<String> invalid = List.of(BODY.replace("Fresh fish", "ab"), BODY.replace("0901234567", "short"),
                BODY.replace("\"VN\"", "\"US\""), BODY.replace("\"Fresh fish\"", "null"),
                BODY.replace("\"name\":", "\"status\":\"ACTIVE\",\"name\":"),
                BODY.replace("\"wardCode\":", "\"ghnVerified\":true,\"wardCode\":"),
                BODY.replace("\"name\":\"Fresh fish\"", "\"name\":123"), "[]", "null");
        for (String body : invalid)
            assertThat(mutate("POST", "/api/v1/shops", body, s, key()).statusCode()).as(body).isEqualTo(422);
        assertThat(mutate("POST", "/api/v1/shops", "{broken", s, key()).statusCode()).isEqualTo(400);
        assertThat(mutate("POST", "/api/v1/shops", BODY + " {}", s, key()).statusCode()).isEqualTo(400);
        assertThat(create(s, "not-uuid").statusCode()).isEqualTo(422);
        assertThat(create(s, null).statusCode()).isEqualTo(422);
        var created = create(s, key());
        for (String body : List.of("{}", "{\"expectedVersion\":0}", "{\"expectedVersion\":0,\"name\":null}",
                "{\"expectedVersion\":0.0,\"name\":\"New\"}", "{\"expectedVersion\":-1,\"name\":\"New\"}",
                "{\"expectedVersion\":0,\"pickupAddress\":null}", "{\"expectedVersion\":0,\"ownerAccountId\":\"x\"}"))
            assertThat(mutate("PATCH", shopPath(created), body, s, key()).statusCode()).as(body).isEqualTo(422);
        assertThat(mutate("PATCH", "/api/v1/shops/not-uuid", "{\"expectedVersion\":0,\"name\":\"New\"}", s, key())
                .statusCode()).isEqualTo(422);
    }

    @Test void concealsOtherOwnersAndGuardsStaleVersionPayloadAndResourceMismatch() throws Exception {
        String a = session(), b = session(), k = key();
        var created = create(a, k);
        assertThat(create(a, key()).statusCode()).isEqualTo(409);
        assertThat(mutate("POST", "/api/v1/shops", BODY.replace("Fresh fish", "Different"), a, k).statusCode()).isEqualTo(422);
        assertThat(create(b, k).statusCode()).isEqualTo(201);
        String patch = "{\"expectedVersion\":0,\"name\":\"New shop\"}", pk = key();
        assertThat(mutate("PATCH", shopPath(created), patch, b, key()).statusCode()).isEqualTo(404);
        assertThat(mutate("PATCH", shopPath(created), patch, a, pk).statusCode()).isEqualTo(200);
        assertThat(mutate("PATCH", shopPath(created), patch, a, key()).statusCode()).isEqualTo(409);
        assertThat(mutate("PATCH", "/api/v1/shops/" + UUID.randomUUID(), patch, a, pk).statusCode()).isEqualTo(422);
        assertThat(mutate("PATCH", "/api/v1/shops/" + UUID.randomUUID(), patch, a, key()).statusCode()).isEqualTo(404);
    }

    @Test void uniqueOwnerAndVersionConstraintsSurviveConcurrentRequests() throws Exception {
        String s = session();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<HttpResponse<String>> create = () -> { gate.await(); return create(s, key()); };
            var a = pool.submit(create); var b = pool.submit(create); gate.countDown();
            var ra = a.get(10, TimeUnit.SECONDS); var rb = b.get(10, TimeUnit.SECONDS);
            assertThat(List.of(ra.statusCode(), rb.statusCode())).containsExactlyInAnyOrder(201, 409);
            String path = shopPath(ra.statusCode() == 201 ? ra : rb);
            var gate2 = new CountDownLatch(1);
            Callable<HttpResponse<String>> patch = () -> { gate2.await(); return mutate("PATCH", path,
                    "{\"expectedVersion\":0,\"name\":\"Concurrent\"}", s, key()); };
            var c = pool.submit(patch); var d = pool.submit(patch); gate2.countDown();
            assertThat(List.of(c.get(10, TimeUnit.SECONDS).statusCode(), d.get(10, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test void sameKeyRaceCreatesExactlyOneResultAndAudit() throws Exception {
        String s = session(), k = key();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<HttpResponse<String>> call = () -> { gate.await(); return create(s, k); };
            var a = pool.submit(call); var b = pool.submit(call); gate.countDown();
            var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
            assertThat(results).allSatisfy(r -> assertThat(r.statusCode()).isIn(201, 409));
            assertThat(jdbc.queryForObject("select count(*) from merchant.shops where owner_account_id=?", Integer.class,
                    identity.sessions.get(s))).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from merchant.audit_entries where actor_account_id=?", Integer.class,
                    identity.sessions.get(s))).isEqualTo(1);
        }
    }

    @Test void inflightKeyReturnsConflictWithoutWaitingAndUniqueScopeIsEnforced() throws Exception {
        String s = session(), k = key();
        UUID actor = identity.sessions.get(s);
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1, actor + ":createShop:" + k); statement.execute();
            }
            var response = create(s, k);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(tree(response).path("code").asString()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
            connection.rollback();
        }
        assertThat(create(s, k).statusCode()).isEqualTo(201);
        assertThatThrownBy(() -> jdbc.update("insert into merchant.idempotency_records select * from merchant.idempotency_records where actor_id=?",
                actor)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test void expiredKeysCanBeReusedButOwnerAndVersionConstraintsStillApply() throws Exception {
        String s = session(), k = key();
        var created = create(s, k);
        jdbc.update("update merchant.idempotency_records set expires_at=now()-interval '1 second' where actor_id=?", identity.sessions.get(s));
        assertThat(create(s, k).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from merchant.shops where owner_account_id=?", Integer.class,
                identity.sessions.get(s))).isEqualTo(1);
        assertThat(mutate("PATCH", shopPath(created), "{\"expectedVersion\":0,\"name\":\"Changed\"}", s, k).statusCode()).isEqualTo(200);
        jdbc.update("update merchant.idempotency_records set expires_at=now()-interval '1 second' where actor_id=?", identity.sessions.get(s));
        store.expireResults();
        assertThat(jdbc.queryForObject("select count(*) from merchant.idempotency_records where actor_id=?", Integer.class,
                identity.sessions.get(s))).isZero();
        assertThat(mutate("PATCH", shopPath(created), "{\"expectedVersion\":0,\"name\":\"Changed\"}", s, k).statusCode()).isEqualTo(409);
    }

    @Test void hashesCanonicalFieldOrderAndKeepsActorOperationScopesSeparate() throws Exception {
        String s = session(), k = key();
        var created = create(s, k);
        String reordered = """
                {"pickupAddress":{"streetAddress":"12 Street","wardName":"Ward","wardCode":"opaque-W",
                 "provinceName":"Province","provinceCode":"opaque-P","countryCode":"VN"},
                 "contactPhone":"0901234567","description":"Local catch","name":"Fresh fish"}
                """;
        var replay = mutate("POST", "/api/v1/shops", reordered, s, k);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(tree(replay)).isEqualTo(tree(created));
        assertThat(mutate("PATCH", shopPath(created), "{\"expectedVersion\":0,\"description\":\"New description\"}", s, k)
                .statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from merchant.idempotency_records where actor_id=?", Integer.class,
                identity.sessions.get(s))).isEqualTo(2);
        store.expireResults();
        assertThat(jdbc.queryForObject("select count(*) from merchant.idempotency_records where actor_id=?", Integer.class,
                identity.sessions.get(s))).isEqualTo(2);
    }

    @Test void failedAuditRollsBackShopAndIdempotencyResult() throws Exception {
        String s = session(), k = key(); UUID actor = identity.sessions.get(s);
        // A test-only PostgreSQL constraint injects a real failure after the shop insert.
        jdbc.execute("alter table merchant.audit_entries add constraint test_audit_failure check (actor_account_id <> '" + actor + "'::uuid)");
        try {
            var response = create(s, k);
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).doesNotContain("test_audit_failure", "INSERT", "postgresql", "SQLException");
            assertThat(jdbc.queryForObject("select count(*) from merchant.shops where owner_account_id=?", Integer.class, actor)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from merchant.idempotency_records where actor_id=?", Integer.class, actor)).isZero();
        } finally { jdbc.execute("alter table merchant.audit_entries drop constraint test_audit_failure"); }
        assertThat(create(s, k).statusCode()).isEqualTo(201);
    }

    @Test void failedResultPersistenceRollsBackPatchAndAudit() throws Exception {
        String s = session(), k = key(); UUID actor = identity.sessions.get(s);
        var original = create(s, key());
        assertThat(original.statusCode()).isEqualTo(201);
        jdbc.execute("alter table merchant.idempotency_records add constraint test_result_failure check (actor_id <> '"
                + actor + "'::uuid or state <> 'COMPLETED') not valid");
        try {
            assertThat(mutate("PATCH", shopPath(original), "{\"expectedVersion\":0,\"name\":\"Must roll back\"}", s, k)
                    .statusCode()).isEqualTo(500);
            assertThat(tree(request("GET", "/api/v1/me/shop", null, s, null, null))).isEqualTo(tree(original));
            assertThat(jdbc.queryForObject("select count(*) from merchant.audit_entries where actor_account_id=?",
                    Integer.class, actor)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from merchant.idempotency_records where actor_id=? and operation='updateShop'",
                    Integer.class, actor)).isZero();
        } finally { jdbc.execute("alter table merchant.idempotency_records drop constraint test_result_failure"); }
        assertThat(mutate("PATCH", shopPath(original), "{\"expectedVersion\":0,\"name\":\"Retry succeeds\"}", s, k)
                .statusCode()).isEqualTo(200);
    }
}
