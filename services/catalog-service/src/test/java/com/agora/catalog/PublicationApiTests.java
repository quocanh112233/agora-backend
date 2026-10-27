package com.agora.catalog;

import com.agora.catalog.adapter.outbound.JdbcProductStore;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.config.import=")
@Import({CatalogApiTests.RemoteConfiguration.class, MediaApiTests.StorageConfiguration.class})
class PublicationApiTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl); p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword); p.add("agora.internal-token", () -> RpcFixture.TOKEN);
    }
    @LocalServerPort int port;
    @Autowired RpcFixture rpc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    final HttpClient http = HttpClient.newHttpClient();
    static final String BASE = "/api/v1/seller/products";
    static String key() { return UUID.randomUUID().toString(); }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE catalog.checkout_decisions,catalog.reservation_audit,catalog.reservation_allocations,catalog.reservation_lines,catalog.reservations,catalog.inventory_audit,catalog.inventory_requests,catalog.inventory_lots,catalog.product_images,catalog.image_uploads,catalog.media_requests,catalog.audit_entries,catalog.idempotency_records,catalog.skus,catalog.products");
    }
    HttpResponse<String> request(String method, String path, String body, String session, String key, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (session != null) builder.header("Cookie", "AGORA_SESSION=" + session);
        if (key != null) builder.header("Idempotency-Key", key);
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> request(String method, String path, String body, String session, String key) throws Exception {
        return request(method, path, body, session, key, "masked-csrf-fixture");
    }
    JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    String create(String session) throws Exception {
        var r = request("POST", BASE, CatalogApiTests.BODY.replace("FISH-500", key().toUpperCase(Locale.ROOT)), session, key());
        assertThat(r.statusCode()).isEqualTo(201); return tree(r).path("id").asString();
    }
    String image(JdbcTemplate db, String product, String state, boolean attach) {
        UUID id = UUID.randomUUID(), productId = UUID.fromString(product);
        UUID shop = db.queryForObject("SELECT shop_id FROM catalog.products WHERE id=?", UUID.class, productId);
        UUID actor = rpc.shops.entrySet().stream().filter(e -> e.getValue().equals(shop)).map(Map.Entry::getKey)
                .findFirst().orElseGet(UUID::randomUUID);
        String publicId = "agora/products/" + product + "/" + id;
        boolean uploaded = state.equals("UPLOADED");
        db.update("""
                INSERT INTO catalog.image_uploads(id,actor_id,shop_id,product_id,key,request_hash,public_id,state,mime,bytes,width,height,provider_version,format,url)
                VALUES (?,?,?,?,?,?,?,?,'image/png',68,1,1,?,?,?)
                """, id, actor, shop, productId, UUID.randomUUID(), "0".repeat(64), publicId, state,
                uploaded ? 1L : null, uploaded ? "png" : null,
                uploaded ? "https://res.cloudinary.com/publication-test/image/upload/v1/" + publicId + ".png" : null);
        if (attach) db.update("INSERT INTO catalog.product_images(upload_id,product_id,shop_id,position) VALUES (?,?,?,0)", id, productId, shop);
        return id.toString();
    }
    String body(String status, long version) { return "{\"status\":\"" + status + "\",\"expectedVersion\":" + version + "}"; }
    HttpResponse<String> visibility(String product, String status, long version, String session, String key) throws Exception {
        return request("PUT", BASE + "/" + product + "/visibility", body(status, version), session, key);
    }
    long count(String table, String product) {
        return jdbc.queryForObject("SELECT count(*) FROM catalog." + table + " WHERE product_id=?", Long.class, UUID.fromString(product));
    }
    @Test void publishesHidesEditsHiddenWithoutRepublishingAndPreservesSkusImages() throws Exception {
        String s = rpc.seller(), p = create(s); image(jdbc, p, "UPLOADED", true);
        JsonNode initial = tree(request("GET", BASE + "/" + p, null, s, null));
        var published = visibility(p, "PUBLISHED", 0, s, key()); assertThat(published.statusCode()).isEqualTo(200);
        assertThat(published.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(tree(published).path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(tree(request("GET", BASE + "/" + p, null, s, null)).path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(tree(request("GET", BASE, null, s, null)).path("items").get(0).path("status").asString()).isEqualTo("PUBLISHED");
        var update = (tools.jackson.databind.node.ObjectNode) json.readTree(CatalogApiTests.BODY);
        var skuInput = (tools.jackson.databind.node.ObjectNode) json.readTree(initial.path("skus").get(0).toString());
        skuInput.remove(List.of("id", "saleUnit"));
        update.set("skus", json.createArrayNode().add(skuInput));
        update.put("expectedVersion", 1);
        assertThat(request("PUT", BASE + "/" + p, update.toString(), s, key()).statusCode()).isEqualTo(409);
        assertThat(request("GET", BASE + "/" + p + "/images", null, s, null).statusCode()).isEqualTo(409);
        var hidden = visibility(p, "HIDDEN", 1, s, key()); assertThat(hidden.statusCode()).isEqualTo(200);
        update.put("expectedVersion", 2).put("name", "Updated hidden product");
        var edited = request("PUT", BASE + "/" + p, update.toString(), s, key()); assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(tree(edited).path("status").asString()).isEqualTo("HIDDEN");
        assertThat(tree(edited).path("skus")).isEqualTo(initial.path("skus"));
        assertThat(request("GET", BASE + "/" + p + "/images", null, s, null).statusCode()).isEqualTo(200);
        assertThat(visibility(p, "PUBLISHED", 3, s, key()).statusCode()).isEqualTo(200);
        assertThat(count("product_images", p)).isEqualTo(1); assertThat(count("audit_entries", p)).isEqualTo(5);
    }
    @Test void publicationRequiresAnAttachedVerifiedImageAndRollsBackKeys() throws Exception {
        String s = rpc.seller();
        for (int scenario = 0; scenario < 3; scenario++) {
            String p = create(s);
            if (scenario > 0) image(jdbc, p, scenario == 1 ? "UPLOADED" : "PENDING", scenario == 2);
            var r = visibility(p, "PUBLISHED", 0, s, key()); assertThat(r.statusCode()).isEqualTo(422);
            assertThat(tree(r).path("code").asString()).isEqualTo("PUBLICATION_NOT_READY");
            assertThat(jdbc.queryForObject("SELECT version FROM catalog.products WHERE id=?", Long.class, UUID.fromString(p))).isZero();
            assertThat(count("audit_entries", p)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.idempotency_records", Long.class)).isEqualTo(3);
    }
    @Test void validatesSmallStrictJsonStatusVersionsAndIdentifiers() throws Exception {
        String s = rpc.seller(), p = create(s); image(jdbc, p, "UPLOADED", true);
        for (String invalid : List.of("{}", "[]", "null", "{\"status\":\"BLOCKED\",\"expectedVersion\":0}",
                "{\"status\":true,\"expectedVersion\":0}", "{\"status\":\"PUBLISHED\",\"expectedVersion\":-1}",
                "{\"status\":\"PUBLISHED\",\"expectedVersion\":0.5}", "{\"status\":\"PUBLISHED\",\"expectedVersion\":9007199254740992}",
                "{\"status\":\"PUBLISHED\",\"expectedVersion\":0,\"owner\":\"forged\"}"))
            assertThat(request("PUT", BASE + "/" + p + "/visibility", invalid, s, key()).statusCode()).as(invalid).isEqualTo(422);
        for (String malformed : List.of("{", "", body("PUBLISHED", 0) + "{}", " ".repeat(1025),
                "{\"status\":\"PUBLISHED\",\"status\":\"HIDDEN\",\"expectedVersion\":0}"))
            assertThat(request("PUT", BASE + "/" + p + "/visibility", malformed, s, key()).statusCode()).as(malformed).isEqualTo(400);
        assertThat(visibility("bad-id", "PUBLISHED", 0, s, key()).statusCode()).isEqualTo(422);
        assertThat(visibility(p, "PUBLISHED", 0, s, "bad-key").statusCode()).isEqualTo(422);
        assertThat(count("audit_entries", p)).isEqualTo(1);
    }
    @Test void currentAuthorizationAndOwnershipApplyBeforeEveryReplay() throws Exception {
        String s = rpc.seller(), p = create(s), key = key(); image(jdbc, p, "UPLOADED", true);
        assertThat(visibility(p, "PUBLISHED", 0, s, key).statusCode()).isEqualTo(200);
        assertThat(visibility(p, "PUBLISHED", 0, null, key()).statusCode()).isEqualTo(401);
        assertThat(visibility(p, "PUBLISHED", 0, rpc.seller(), key()).statusCode()).isEqualTo(404);
        assertThat(request("PUT", BASE + "/" + p + "/visibility", body("PUBLISHED", 0), s, key, "wrong").statusCode()).isEqualTo(403);
        UUID actor = rpc.sessions.get(s), shop = rpc.shops.remove(actor);
        try { assertThat(visibility(p, "PUBLISHED", 0, s, key).statusCode()).isEqualTo(403); }
        finally { rpc.shops.put(actor, shop); }
        rpc.sessions.remove(s);
        try { assertThat(visibility(p, "PUBLISHED", 0, s, key).statusCode()).isEqualTo(401); }
        finally { rpc.sessions.put(s, actor); }
        assertThat(count("audit_entries", p)).isEqualTo(2);
    }
    @Test void replayKeepsSnapshotAndBindsStatusVersionAndProduct() throws Exception {
        String s = rpc.seller(), p = create(s), k = key(); image(jdbc, p, "UPLOADED", true);
        var first = visibility(p, "PUBLISHED", 0, s, k); assertThat(first.statusCode()).isEqualTo(200);
        assertThat(visibility(p, "HIDDEN", 1, s, key()).statusCode()).isEqualTo(200);
        var replay = visibility(p, "PUBLISHED", 0, s, k); assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(visibility(p, "HIDDEN", 0, s, k).statusCode()).isEqualTo(422);
        assertThat(visibility(p, "PUBLISHED", 1, s, k).statusCode()).isEqualTo(422);
        assertThat(visibility(create(s), "PUBLISHED", 0, s, k).statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT status FROM catalog.products WHERE id=?", String.class, UUID.fromString(p))).isEqualTo("HIDDEN");
        assertThat(count("audit_entries", p)).isEqualTo(3);
    }
    @Test void hiddenMediaCanChangeAndRemovingLastImageBlocksRepublish() throws Exception {
        String s = rpc.seller(), p = create(s), image = image(jdbc, p, "UPLOADED", true);
        assertThat(visibility(p, "PUBLISHED", 0, s, key()).statusCode()).isEqualTo(200);
        assertThat(visibility(p, "HIDDEN", 1, s, key()).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", BASE + "/" + p + "/images/" + image, "{\"expectedVersion\":2}", s, key()).statusCode()).isEqualTo(200);
        assertThat(visibility(p, "PUBLISHED", 3, s, key()).statusCode()).isEqualTo(422);
        String next = image(jdbc, p, "UPLOADED", false);
        assertThat(request("POST", BASE + "/" + p + "/images", "{\"uploadId\":\"" + next + "\",\"expectedVersion\":3}", s, key()).statusCode()).isEqualTo(200);
        assertThat(visibility(p, "PUBLISHED", 4, s, key()).statusCode()).isEqualTo(200);
    }
    @Test void staleRepeatedAndExhaustedVersionsDoNotCreateAuditsOrKeys() throws Exception {
        String s = rpc.seller(), p = create(s); image(jdbc, p, "UPLOADED", true);
        assertThat(visibility(p, "HIDDEN", 0, s, key()).statusCode()).isEqualTo(409);
        assertThat(visibility(p, "PUBLISHED", 1, s, key()).statusCode()).isEqualTo(409);
        assertThat(visibility(p, "PUBLISHED", 0, s, key()).statusCode()).isEqualTo(200);
        assertThat(visibility(p, "PUBLISHED", 1, s, key()).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE catalog.products SET version=9007199254740991 WHERE id=?", UUID.fromString(p));
        assertThat(visibility(p, "HIDDEN", 9007199254740991L, s, key()).statusCode()).isEqualTo(409);
        assertThat(count("audit_entries", p)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.idempotency_records", Integer.class)).isEqualTo(2);
    }
    @Test void publishAndDetachLastImageHaveOnlyOneWinner() throws Exception {
        String s = rpc.seller(), p = create(s), image = image(jdbc, p, "UPLOADED", true);
        var gate = new CountDownLatch(1);
        var publish = CompletableFuture.supplyAsync(() -> { try { gate.await(); return visibility(p, "PUBLISHED", 0, s, key()); } catch (Exception e) { throw new RuntimeException(e); } });
        var detach = CompletableFuture.supplyAsync(() -> { try { gate.await(); return request("DELETE", BASE + "/" + p + "/images/" + image, "{\"expectedVersion\":0}", s, key()); } catch (Exception e) { throw new RuntimeException(e); } });
        gate.countDown();
        assertThat(List.of(publish.get(10, TimeUnit.SECONDS).statusCode(), detach.get(10, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,409);
        String status = jdbc.queryForObject("SELECT status FROM catalog.products WHERE id=?", String.class, UUID.fromString(p));
        assertThat(count("product_images", p)).isEqualTo(status.equals("PUBLISHED") ? 1 : 0);
        assertThat(count("audit_entries", p)).isEqualTo(2);
    }
    @Test void auditFailureRollsBackVisibilityVersionAndClaim() throws Exception {
        String s = rpc.seller(), p = create(s), k = key(); image(jdbc, p, "UPLOADED", true);
        jdbc.execute("ALTER TABLE catalog.audit_entries ADD CONSTRAINT publication_test_failure CHECK(action<>'PUBLISH_PRODUCT') NOT VALID");
        try { assertThat(visibility(p, "PUBLISHED", 0, s, k).statusCode()).isEqualTo(500); }
        finally { jdbc.execute("ALTER TABLE catalog.audit_entries DROP CONSTRAINT publication_test_failure"); }
        assertThat(jdbc.queryForObject("SELECT status FROM catalog.products WHERE id=?", String.class, UUID.fromString(p))).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT version FROM catalog.products WHERE id=?", Long.class, UUID.fromString(p))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.idempotency_records", Integer.class)).isEqualTo(1);
        assertThat(count("audit_entries", p)).isEqualTo(1);
        assertThat(visibility(p, "PUBLISHED", 0, s, k).statusCode()).isEqualTo(200);
    }
    @Test void incompleteSavedDataCannotBePublished() throws Exception {
        String s = rpc.seller(), p = create(s); image(jdbc, p, "UPLOADED", true);
        jdbc.update("UPDATE catalog.products SET name=? WHERE id=?", "\u00a0\u00a0\u00a0", UUID.fromString(p));
        assertThat(visibility(p, "PUBLISHED", 0, s, key()).statusCode()).isEqualTo(422);
        assertThat(count("audit_entries", p)).isEqualTo(1);
    }
    @Test void upgradesV2DataWithoutLosingSkusImagesOrCompletedSnapshots() {
        jdbc.execute("CREATE DATABASE visibility_upgrade");
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/visibility_upgrade");
        var config = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("catalog").schemas("catalog").createSchemas(true);
        config.target("2").load().migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID p = UUID.randomUUID(), shop = UUID.randomUUID(), sku = UUID.randomUUID();
        db.update("INSERT INTO catalog.products(id,shop_id,name,category,condition,origin,storage_instructions,status,version,created_at,updated_at) VALUES (?,?,'Upgrade product','SEAFOOD','FRESH','VN','Cold','DRAFT',0,now(),now())", p, shop);
        db.update("INSERT INTO catalog.skus VALUES (?,?,?,'UPGRADE','500 g',50000,500,600,20,10,5,'PACKAGE',0)", sku,p,shop);
        String upload = image(db, p.toString(), "UPLOADED", true);
        var saved = new JdbcProductStore(db, json).owned(shop,p).orElseThrow();
        db.update("INSERT INTO catalog.idempotency_records(actor_id,shop_id,operation,key,request_hash,state,http_status,response_snapshot,resource_id) VALUES (?,?,'createProduct',?,?,'COMPLETED',201,?::jsonb,?)",
                UUID.randomUUID(),shop,UUID.randomUUID(),"0".repeat(64),json.writeValueAsString(saved),p);
        config.target("latest").load().migrate();
        assertThat(db.queryForObject("SELECT id FROM catalog.skus", UUID.class)).isEqualTo(sku);
        assertThat(db.queryForObject("SELECT upload_id FROM catalog.product_images", UUID.class)).isEqualTo(UUID.fromString(upload));
        assertThat(db.queryForObject("SELECT response_snapshot->>'status' FROM catalog.idempotency_records", String.class)).isEqualTo("DRAFT");
        assertThat(db.update("UPDATE catalog.products SET status='PUBLISHED' WHERE id=?",p)).isEqualTo(1);
    }
}
