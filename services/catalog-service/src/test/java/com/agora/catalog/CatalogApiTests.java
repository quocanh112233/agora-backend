package com.agora.catalog;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
@Import(CatalogApiTests.RemoteConfiguration.class)
class CatalogApiTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
        p.add("agora.internal-token", () -> "catalog-test-internal-token-at-least-32");
    }
    @LocalServerPort int port;
    @TestConfiguration(proxyBeanMethods = false) static class RemoteConfiguration {
        @Bean(destroyMethod = "close") RpcFixture rpcFixture() throws Exception { return new RpcFixture(); }
        @Bean(destroyMethod = "") @Primary @Qualifier("identityChannel")
        io.grpc.ManagedChannel testIdentityChannel(RpcFixture rpc) { return rpc.identityChannel; }
        @Bean(destroyMethod = "") @Primary @Qualifier("merchantChannel")
        io.grpc.ManagedChannel testMerchantChannel(RpcFixture rpc) { return rpc.merchantChannel; }
    }
    @Autowired RpcFixture rpc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.agora.catalog.adapter.outbound.JdbcProductStore store;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    static final String SKU = """
            {"code":"FISH-500","label":"500 g","priceVnd":50000,"netWeightGrams":500,"shippingWeightGrams":600,
             "lengthCm":20,"widthCm":10,"heightCm":5}
            """;
    static final String BODY = """
            {"name":"Fresh fish","description":"Local catch","category":"SEAFOOD","condition":"FRESH",
             "origin":"Viet Nam","producerName":null,"storageInstructions":"Keep cold","skus":[%s]}
            """.formatted(SKU);
    HttpResponse<String> request(String method, String path, String body, String session, String key, String csrf) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (session != null) b.header("Cookie", "AGORA_SESSION=" + session);
        if (key != null) b.header("Idempotency-Key", key);
        if (csrf != null) b.header("X-CSRF-TOKEN", csrf);
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void anonymousCannotReadOrCreateProducts() throws Exception {
        for (String method : List.of("GET", "POST")) {
            var response = request(method, "/api/v1/seller/products", method.equals("POST") ? BODY : null, null, UUID.randomUUID().toString(), null);
            assertThat(response.statusCode()).isEqualTo(401);
            JsonNode error = json.readTree(response.body());
            assertThat(error.path("code").asString()).isEqualTo("UNAUTHENTICATED");
            assertThat(error.path("details").isArray()).isTrue();
        }
    }
    String key() { return UUID.randomUUID().toString(); }
    UUID shop(String session) { return rpc.shops.get(rpc.sessions.get(session)); }
    JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    String path(HttpResponse<String> response) { return "/api/v1/seller/products/" + tree(response).path("id").asString(); }
    String update(String body, long version) { return body.replaceFirst("\\{", "{\"expectedVersion\":" + version + ","); }
    HttpResponse<String> create(String session, String body, String key) throws Exception {
        return request("POST", "/api/v1/seller/products", body, session, key, "masked-csrf-fixture");
    }
    HttpResponse<String> put(String path, String session, String body, String key) throws Exception {
        return request("PUT", path, body, session, key, "masked-csrf-fixture");
    }
    HttpResponse<String> get(String path, String session) throws Exception { return request("GET", path, null, session, null, null); }
    @Test void createsReadsListsReplacesAndReplaysOriginalSnapshotsWithStableSkuIds() throws Exception {
        String session = rpc.seller(), createKey = key(), updateKey = key();
        var original = create(session, BODY.replace(SKU, SKU + "," + SKU.replace("FISH-500", "FISH-1000")), createKey);
        assertThat(original.statusCode()).isEqualTo(201);
        assertThat(original.headers().firstValue("Location")).contains(path(original));
        assertThat(original.headers().firstValue("Cache-Control")).contains("no-store");
        UUID.fromString(original.headers().firstValue("X-Trace-Id").orElseThrow());
        assertThat(tree(original).path("shopId").asString()).isEqualTo(shop(session).toString());
        assertThat(tree(original).path("status").asString()).isEqualTo("DRAFT");
        assertThat(tree(original).path("skus").get(0).path("saleUnit").asString()).isEqualTo("PACKAGE");
        String replacement = update(BODY.replace("\"Local catch\"", "null").replace(SKU,
                SKU.replace("FISH-500", "FISH-1000") + "," + SKU.replace("FISH-500", "FISH-2000")), 0);
        var changed = put(path(original), session, replacement, updateKey);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(tree(changed).path("skus").get(0).path("id")).isEqualTo(tree(original).path("skus").get(1).path("id"));
        assertThat(tree(changed).path("description").isNull()).isTrue();
        assertThat(tree(changed).path("version").asLong()).isEqualTo(1);
        assertThat(tree(get(path(original), session))).isEqualTo(tree(changed));
        var listed = tree(get("/api/v1/seller/products", session));
        assertThat(listed.path("items").get(0)).isEqualTo(tree(changed));
        assertThat(listed.path("page").asInt()).isZero(); assertThat(listed.path("pageSize").asInt()).isEqualTo(20);
        assertThat(listed.path("totalItems").asLong()).isEqualTo(1);
        assertThat(tree(create(session, BODY.replace(SKU, SKU + "," + SKU.replace("FISH-500", "FISH-1000")), createKey))).isEqualTo(tree(original));
        assertThat(tree(put(path(original), session, replacement, updateKey))).isEqualTo(tree(changed));
        UUID product = UUID.fromString(tree(original).path("id").asString());
        assertThat(jdbc.queryForObject("select count(*) from catalog.audit_entries where product_id=?", Integer.class, product)).isEqualTo(2);
        assertThat(jdbc.queryForList("select actor_account_id from catalog.audit_entries where product_id=?", UUID.class, product))
                .containsOnly(rpc.sessions.get(session));
        assertThat(jdbc.queryForObject("select min(expires_at-created_at) >= interval '168 hours' from catalog.idempotency_records where actor_id=?",
                Boolean.class, rpc.sessions.get(session))).isTrue();
    }
    @Test void isolatesShopsAndAllowsTheSameSkuCodeInDifferentShops() throws Exception {
        String a = rpc.seller(), b = rpc.seller();
        var product = create(a, BODY, key());
        assertThat(create(b, BODY, key()).statusCode()).isEqualTo(201);
        assertThat(get(path(product), b).statusCode()).isEqualTo(404);
        assertThat(put(path(product), b, update(BODY, 0), key()).statusCode()).isEqualTo(404);
        var list = tree(get("/api/v1/seller/products", b));
        assertThat(list.path("items").valueStream().map(n -> n.path("shopId").asString()).toList()).containsOnly(shop(b).toString());
        assertThat(get("/api/v1/seller/products/" + UUID.randomUUID(), a).statusCode()).isEqualTo(404);
    }
    @Test void checksSessionCsrfAndActiveOwnershipBeforeEveryOperationAndReplay() throws Exception {
        String s = rpc.seller(), k = key(); var product = create(s, BODY, k);
        String updateKey = key(), replacement = update(BODY, 0);
        assertThat(put(path(product), s, replacement, updateKey).statusCode()).isEqualTo(200);
        for (String csrf : new String[]{null, "wrong"}) {
            assertThat(request("POST", "/api/v1/seller/products", BODY, s, k, csrf).statusCode()).isEqualTo(403);
            assertThat(request("PUT", path(product), update(BODY, 0), s, key(), csrf).statusCode()).isEqualTo(403);
        }
        UUID account = rpc.sessions.get(s), shop = rpc.shops.remove(account);
        assertThat(get("/api/v1/seller/products", s).statusCode()).isEqualTo(403);
        assertThat(get(path(product), s).statusCode()).isEqualTo(403);
        assertThat(create(s, BODY, k).statusCode()).isEqualTo(403);
        assertThat(put(path(product), s, replacement, updateKey).statusCode()).isEqualTo(403);
        rpc.shops.put(account, UUID.randomUUID());
        assertThat(create(s, BODY, k).statusCode()).isEqualTo(404);
        assertThat(put(path(product), s, replacement, updateKey).statusCode()).isEqualTo(404);
        rpc.shops.put(account, shop);
        rpc.merchantFailure.set(io.grpc.Status.UNAVAILABLE);
        try { assertThat(create(s, BODY, k).statusCode()).isEqualTo(503); }
        finally { rpc.merchantFailure.set(null); }
        rpc.sessions.remove(s);
        assertThat(create(s, BODY, k).statusCode()).isEqualTo(401);
    }
    @Test void rejectsUnknownFieldsInvalidJsonIdentifiersAndSkuCardinality() throws Exception {
        String s = rpc.seller();
        for (String invalid : List.of("[]", "null", "{}", BODY.replace("\"name\":", "\"shopId\":\"spoof\",\"name\":"),
                BODY.replace("\"name\":", "\"status\":\"ACTIVE\",\"name\":"),
                BODY.replace("\"code\":", "\"id\":\"spoof\",\"code\":"), BODY.replace("\"code\":", "\"saleUnit\":\"PACKAGE\",\"code\":"),
                BODY.replace(SKU, ""), BODY.replace(SKU, String.join(",", Collections.nCopies(11, SKU))),
                BODY.replace(SKU, SKU + "," + SKU), BODY.replace("SEAFOOD", "OTHER"), BODY.replace("FRESH", "OTHER")))
            assertThat(create(s, invalid, key()).statusCode()).as(invalid).isEqualTo(422);
        assertThat(create(s, "{broken", key()).statusCode()).isEqualTo(400);
        assertThat(create(s, BODY + " {}", key()).statusCode()).isEqualTo(400);
        assertThat(create(s, BODY, "1-1-1-1-1").statusCode()).isEqualTo(422);
        assertThat(create(s, BODY, null).statusCode()).isEqualTo(422);
        assertThat(get("/api/v1/seller/products/not-a-uuid", s).statusCode()).isEqualTo(422);
        var product = create(s, BODY, key());
        for (String invalid : List.of(BODY, update(BODY, -1), update(BODY, 0).replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"),
                update(BODY, 0).replace("\"expectedVersion\":0", "\"expectedVersion\":9007199254740992")))
            assertThat(put(path(product), s, invalid, key()).statusCode()).isEqualTo(422);
        String tenSkus = java.util.stream.IntStream.range(0, 10).mapToObj(i -> SKU.replace("FISH-500", "TEN-" + i))
                .collect(java.util.stream.Collectors.joining(","));
        var maximum = create(s, BODY.replace(SKU, tenSkus), key());
        assertThat(maximum.statusCode()).isEqualTo(201);
        assertThat(tree(maximum).path("skus").size()).isEqualTo(10);
    }
    @Test void validatesRawUnicodeLengthsNonblankTextAndNullableFields() throws Exception {
        for (String name : List.of("🐟".repeat(160), " " + "x".repeat(159))) {
            String s = rpc.seller(); var response = create(s, BODY.replace("Fresh fish", name), key());
            assertThat(response.statusCode()).isEqualTo(201); assertThat(tree(response).path("name").asString()).isEqualTo(name);
        }
        String s = rpc.seller();
        for (String name : List.of("🐟".repeat(161), " " + "x".repeat(160), "   ", "\u00a0\u2003\ufeff", "xy"))
            assertThat(create(s, BODY.replace("Fresh fish", name), key()).statusCode()).isEqualTo(422);
        for (String name : List.of("\\u0000bad", "\\ud800xx"))
            assertThat(create(s, BODY.replace("Fresh fish", name), key()).statusCode()).isEqualTo(422);
        for (String invalid : List.of(BODY.replace("Local catch", "x".repeat(5001)), BODY.replace("\"producerName\":null", "\"producerName\":\"" + "x".repeat(161) + "\""),
                BODY.replace("Viet Nam", " "), BODY.replace("Keep cold", "x".repeat(1001)), BODY.replace("500 g", " "),
                BODY.replace("FISH-500", "lowercase"), BODY.replace("FISH-500", "X".repeat(41))))
            assertThat(create(s, invalid, key()).statusCode()).isEqualTo(422);
        String max = BODY.replace("Local catch", "🐟".repeat(5000)).replace("Viet Nam", "x".repeat(120))
                .replace("Keep cold", "x".repeat(1000)).replace("500 g", "🐟".repeat(100));
        assertThat(create(s, max, key()).statusCode()).isEqualTo(201);
    }
    @Test void rejectsFractionalHugeAndOutOfRangeNumbersAndShippingBelowNetWeight() throws Exception {
        String s = rpc.seller();
        for (var field : Map.of("priceVnd", 1000000000, "netWeightGrams", 50000, "shippingWeightGrams", 50000,
                "lengthCm", 200, "widthCm", 200, "heightCm", 200).entrySet()) {
            for (String value : List.of("0", "-1", "1.5", "1e2", "\"2\"", "null", "999999999999999999999999999", Long.toString((long) field.getValue() + 1))) {
                String invalid = BODY.replaceAll("\"" + field.getKey() + "\":\\d+", "\"" + field.getKey() + "\":" + value);
                assertThat(create(s, invalid, key()).statusCode()).as(field.getKey() + "=" + value).isEqualTo(422);
            }
        }
        assertThat(create(s, BODY.replace("\"shippingWeightGrams\":600", "\"shippingWeightGrams\":499"), key()).statusCode()).isEqualTo(422);
        String max = BODY.replace("\"priceVnd\":50000", "\"priceVnd\":1000000000")
                .replace("\"netWeightGrams\":500", "\"netWeightGrams\":50000").replace("\"shippingWeightGrams\":600", "\"shippingWeightGrams\":50000");
        assertThat(create(s, max, key()).statusCode()).isEqualTo(201);
    }
    @Test void skuCodeConflictsRollbackWholeCreateAndReplacement() throws Exception {
        String s = rpc.seller(); var original = create(s, BODY, key());
        String conflictKey = key();
        assertThat(create(s, BODY, conflictKey).statusCode()).isEqualTo(409);
        var other = create(s, BODY.replace("FISH-500", "OTHER"), key());
        assertThat(put(path(other), s, update(BODY, 0), key()).statusCode()).isEqualTo(409);
        assertThat(tree(get(path(other), s))).isEqualTo(tree(other));
        assertThat(jdbc.queryForObject("select count(*) from catalog.products where shop_id=?", Integer.class, shop(s))).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from catalog.idempotency_records where actor_id=?", Integer.class, rpc.sessions.get(s))).isEqualTo(2);
        assertThat(tree(get(path(original), s))).isEqualTo(tree(original));
    }
    @Test void paginationUsesCreatedAtThenIdAndEnforcesBounds() throws Exception {
        String s = rpc.seller(); var ids = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            var product = create(s, BODY.replace("FISH-500", "SKU-" + i), key());
            String id = tree(product).path("id").asString(); ids.add(id);
            jdbc.update("update catalog.products set created_at='2035-01-01T00:00:00Z' where id=?", UUID.fromString(id));
        }
        ids.sort(Comparator.reverseOrder());
        for (int page = 0; page < 3; page++) {
            var result = tree(get("/api/v1/seller/products?page=" + page + "&pageSize=2", s));
            assertThat(result.path("items").valueStream().map(n -> n.path("id").asString()).toList())
                    .containsExactlyElementsOf(ids.subList(page * 2, Math.min(5, page * 2 + 2)));
            assertThat(result.path("totalItems").asLong()).isEqualTo(5);
        }
        assertThat(tree(get("/api/v1/seller/products?page=1000000&pageSize=100", s)).path("items").size()).isZero();
        for (String query : List.of("page=-1", "page=1000001", "page=x", "page=0.5", "pageSize=0", "pageSize=101", "page="))
            assertThat(get("/api/v1/seller/products?" + query, s).statusCode()).as(query).isEqualTo(422);
    }
    @Test void payloadResourceAndOperationScopesAndCanonicalSkuObjectsArePreserved() throws Exception {
        String s = rpc.seller(), k = key(); var product = create(s, BODY, k);
        String reordered = BODY.replace(SKU, "{\"heightCm\":5,\"widthCm\":10,\"lengthCm\":20,\"shippingWeightGrams\":600,\"netWeightGrams\":500,\"priceVnd\":50000,\"label\":\"500 g\",\"code\":\"FISH-500\"}");
        assertThat(tree(create(s, reordered, k))).isEqualTo(tree(product));
        assertThat(create(s, BODY.replace("Fresh fish", "Changed"), k).statusCode()).isEqualTo(422);
        assertThat(put(path(product), s, update(BODY, 0), k).statusCode()).isEqualTo(200);
        assertThat(put(path(product), s, update(BODY, 1), k).statusCode()).isEqualTo(422);
        assertThat(put("/api/v1/seller/products/" + UUID.randomUUID(), s, update(BODY, 0), k).statusCode()).isEqualTo(422);
        assertThat(put(path(product), s, update(BODY, 0), key()).statusCode()).isEqualTo(409);
    }
    @Test void concurrentVersionsAndSkuCodesHaveOneWinner() throws Exception {
        String s = rpc.seller();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<HttpResponse<String>> create = () -> { gate.await(); return create(s, BODY, key()); };
            var a = pool.submit(create); var b = pool.submit(create); gate.countDown();
            var ra = a.get(10, TimeUnit.SECONDS); var rb = b.get(10, TimeUnit.SECONDS);
            assertThat(List.of(ra.statusCode(), rb.statusCode())).containsExactlyInAnyOrder(201, 409);
            String path = path(ra.statusCode() == 201 ? ra : rb);
            var gate2 = new CountDownLatch(1);
            Callable<HttpResponse<String>> replace = () -> { gate2.await(); return put(path, s, update(BODY, 0), key()); };
            var c = pool.submit(replace); var d = pool.submit(replace); gate2.countDown();
            assertThat(List.of(c.get(10, TimeUnit.SECONDS).statusCode(), d.get(10, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from catalog.audit_entries where actor_account_id=?", Integer.class, rpc.sessions.get(s))).isEqualTo(2);
    }
    @Test void inflightKeysConflictImmediatelyForBothMutations() throws Exception {
        String s = rpc.seller(); var product = create(s, BODY, key());
        for (String operation : List.of("createProduct", "updateProduct")) {
            String k = key();
            try (var connection = POSTGRES.createConnection("")) {
                connection.setAutoCommit(false);
                try (var lock = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
                    lock.setString(1, rpc.sessions.get(s) + ":" + operation + ":" + k); lock.execute();
                }
                var response = operation.equals("createProduct") ? create(s, BODY, k) : put(path(product), s, update(BODY, 0), k);
                assertThat(response.statusCode()).isEqualTo(409);
                assertThat(tree(response).path("code").asString()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
                connection.rollback();
            }
        }
    }
    @Test void sameKeyRaceHasOneDurableProductAuditAndResult() throws Exception {
        String s = rpc.seller(), k = key();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<HttpResponse<String>> call = () -> { gate.await(); return create(s, BODY, k); };
            var a = pool.submit(call); var b = pool.submit(call); gate.countDown();
            var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results).anySatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
            assertThat(results).allSatisfy(r -> assertThat(r.statusCode()).isIn(201, 409));
        }
        assertThat(create(s, BODY, k).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from catalog.products where shop_id=?", Integer.class, shop(s))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from catalog.audit_entries where actor_account_id=?", Integer.class, rpc.sessions.get(s))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from catalog.idempotency_records where actor_id=?", Integer.class, rpc.sessions.get(s))).isEqualTo(1);
    }
    @Test void expiredKeysCannotBypassSkuUniquenessOrVersionGuards() throws Exception {
        String s = rpc.seller(), k = key(); var product = create(s, BODY, k);
        jdbc.update("update catalog.idempotency_records set expires_at=now()-interval '1 second' where actor_id=?", rpc.sessions.get(s));
        assertThat(create(s, BODY, k).statusCode()).isEqualTo(409);
        assertThat(put(path(product), s, update(BODY, 0), k).statusCode()).isEqualTo(200);
        jdbc.update("update catalog.idempotency_records set expires_at=now()-interval '1 second' where actor_id=?", rpc.sessions.get(s));
        store.expireResults();
        assertThat(jdbc.queryForObject("select count(*) from catalog.idempotency_records where actor_id=?", Integer.class, rpc.sessions.get(s))).isZero();
        assertThat(put(path(product), s, update(BODY, 0), k).statusCode()).isEqualTo(409);
    }
    @Test void failuresAtSkuAuditAndResultBoundariesRollbackCreateAndReplacement() throws Exception {
        for (boolean replacing : new boolean[]{false, true}) {
            String s = rpc.seller(); UUID actor = rpc.sessions.get(s);
            var original = replacing ? create(s, BODY, key()) : null;
            String changed = BODY.replace("FISH-500", "FAIL-SKU");
            for (String boundary : List.of("sku", "audit", "result")) {
                String table = switch (boundary) { case "sku" -> "skus"; case "audit" -> "audit_entries"; default -> "idempotency_records"; };
                String rule = switch (boundary) {
                    case "sku" -> "code <> 'FAIL-SKU'";
                    case "audit" -> "actor_account_id <> '" + actor + "'::uuid";
                    default -> "actor_id <> '" + actor + "'::uuid or state <> 'COMPLETED'";
                };
                jdbc.execute("alter table catalog." + table + " add constraint test_failure check (" + rule + ") not valid");
                try {
                    var result = replacing ? put(path(original), s, update(changed, 0), key()) : create(s, changed, key());
                    assertThat(result.statusCode()).as(boundary).isEqualTo(500);
                    assertThat(result.body()).doesNotContain("test_failure", "SQLException", "INSERT", "UPDATE");
                    if (replacing) assertThat(tree(get(path(original), s))).isEqualTo(tree(original));
                    assertThat(jdbc.queryForObject("select count(*) from catalog.products where shop_id=?", Integer.class, shop(s))).isEqualTo(replacing ? 1 : 0);
                    assertThat(jdbc.queryForObject("select count(*) from catalog.skus where shop_id=?", Integer.class, shop(s))).isEqualTo(replacing ? 1 : 0);
                    assertThat(jdbc.queryForObject("select count(*) from catalog.audit_entries where actor_account_id=?", Integer.class, actor)).isEqualTo(replacing ? 1 : 0);
                    assertThat(jdbc.queryForObject("select count(*) from catalog.idempotency_records where actor_id=?", Integer.class, actor)).isEqualTo(replacing ? 1 : 0);
                } finally { jdbc.execute("alter table catalog." + table + " drop constraint test_failure"); }
            }
        }
    }
    @Test void migrationsAndHealthUseOnlyDisposableCatalogPostgres() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select current_setting('server_version')", String.class)).startsWith("16.");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema in ('identity','merchant')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from catalog.flyway_schema_history where success and version='1'", Integer.class)).isEqualTo(1);
    }
}
