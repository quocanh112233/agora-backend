package com.agora.catalog;

import com.agora.catalog.application.ImageStorage;
import com.agora.catalog.application.MediaService;
import com.agora.catalog.application.MediaStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.config.import=")
@Import({CatalogApiTests.RemoteConfiguration.class, MediaApiTests.StorageConfiguration.class})
class MediaApiTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
        p.add("agora.internal-token", () -> RpcFixture.TOKEN);
    }
    @TestConfiguration(proxyBeanMethods = false) static class StorageConfiguration {
        @Bean @Primary FakeStorage fakeStorage() { return new FakeStorage(); }
    }
    static class FakeStorage implements ImageStorage {
        final Map<String, Stored> assets = new ConcurrentHashMap<>();
        final Set<String> deleted = ConcurrentHashMap.newKeySet();
        final AtomicInteger uploads = new AtomicInteger(), finds = new AtomicInteger();
        volatile boolean unknown, rejectDelete;
        volatile java.util.function.Consumer<String> onUpload = id -> {}, onDelete = id -> {};
        volatile java.util.function.UnaryOperator<Stored> metadata = s -> s;
        @Override public Stored upload(byte[] data, String mime, String id) {
            uploads.incrementAndGet();
            onUpload.accept(id);
            try {
                var image = ImageIO.read(new java.io.ByteArrayInputStream(data));
                String format = mime.equals("image/png") ? "png" : "jpg";
                Stored stored = new Stored(id, 1, format, data.length, image.getWidth(), image.getHeight(),
                        "https://res.cloudinary.com/media-test/image/upload/v1/" + id + "." + format);
                assets.put(id, stored);
                if (unknown) throw new IllegalStateException("provider details must stay private");
                return metadata.apply(stored);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
        @Override public Optional<Stored> find(String id) { finds.incrementAndGet(); return Optional.ofNullable(assets.get(id)); }
        @Override public void delete(String id) {
            onDelete.accept(id);
            if (rejectDelete) throw new IllegalStateException("provider details must stay private");
            assets.remove(id); deleted.add(id);
        }
    }
    @LocalServerPort int port;
    @Autowired RpcFixture rpc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeStorage storage;
    @Autowired MediaService media;
    @Autowired MediaStore store;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE catalog.product_images,catalog.image_uploads,catalog.media_requests,catalog.audit_entries,catalog.idempotency_records,catalog.skus,catalog.products");
        storage.assets.clear(); storage.deleted.clear(); storage.unknown = false; storage.rejectDelete = false;
        storage.uploads.set(0); storage.finds.set(0);
        storage.onUpload = id -> {}; storage.onDelete = id -> {}; storage.metadata = s -> s;
    }
    static String key() { return UUID.randomUUID().toString(); }
    static byte[] image(String format) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }
    HttpResponse<String> send(String method, String path, byte[] body, String type, String session, String key, String csrf) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", type);
        if (session != null) b.header("Cookie", "AGORA_SESSION=" + session);
        if (key != null) b.header("Idempotency-Key", key);
        if (csrf != null) b.header("X-CSRF-TOKEN", csrf);
        return http.send(b.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> request(String method, String path, String body, String session, String key) throws Exception {
        return send(method, path, (body == null ? "" : body).getBytes(StandardCharsets.UTF_8), "application/json", session, key, "masked-csrf-fixture");
    }
    JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }
    String product(String session) throws Exception {
        var response = request("POST", "/api/v1/seller/products", CatalogApiTests.BODY.replace("FISH-500", key().toUpperCase(Locale.ROOT)), session, key());
        assertThat(response.statusCode()).isEqualTo(201);
        return "/api/v1/seller/products/" + tree(response).path("id").asString();
    }
    byte[] multipart(byte[] file, String mime, String extra) throws Exception {
        var out = new ByteArrayOutputStream();
        out.write(("--media-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ignored.png\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(file);
        out.write(("\r\n" + extra + "--media-boundary--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
    HttpResponse<String> upload(String path, String session, String key, byte[] file, String mime) throws Exception {
        return send("POST", path + "/image-uploads", multipart(file, mime, ""), "multipart/form-data; boundary=media-boundary", session, key, "masked-csrf-fixture");
    }
    String attachBody(String uploadId, long version) { return "{\"uploadId\":\"" + uploadId + "\",\"expectedVersion\":" + version + "}"; }

    @Test void uploadIsSeparateFromAttachmentAndReplaysStableSnapshotsWithoutChangingSkus() throws Exception {
        String s = rpc.seller(), path = product(s), uploadKey = key();
        var original = tree(request("GET", path, null, s, null));
        byte[] png = image("png");
        var uploaded = upload(path, s, uploadKey, png, "image/png");
        assertThat(uploaded.statusCode()).isEqualTo(201);
        assertThat(uploaded.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(tree(uploaded).propertyNames()).containsExactlyInAnyOrder("uploadId", "productId", "expiresAt");
        assertThat(tree(upload(path, s, uploadKey, png, "image/png"))).isEqualTo(tree(uploaded));
        assertThat(tree(request("GET", path + "/images", null, s, null)).path("items").size()).isZero();
        String id = tree(uploaded).path("uploadId").asString(), attachKey = key(), detachKey = key();
        var attached = request("POST", path + "/images", attachBody(id, 0), s, attachKey);
        assertThat(attached.statusCode()).isEqualTo(200);
        assertThat(tree(attached).path("productVersion").asLong()).isEqualTo(1);
        assertThat(tree(attached).path("items").get(0).path("id").asString()).isEqualTo(id);
        var detached = request("DELETE", path + "/images/" + id, "{\"expectedVersion\":1}", s, detachKey);
        assertThat(detached.statusCode()).isEqualTo(200);
        assertThat(tree(detached).path("productVersion").asLong()).isEqualTo(2);
        assertThat(tree(detached).path("items").size()).isZero();
        assertThat(tree(request("POST", path + "/images", attachBody(id, 0), s, attachKey))).isEqualTo(tree(attached));
        assertThat(tree(request("DELETE", path + "/images/" + id, "{\"expectedVersion\":1}", s, detachKey))).isEqualTo(tree(detached));
        assertThat(tree(request("GET", path, null, s, null)).path("skus")).isEqualTo(original.path("skus"));
    }

    @Test void authenticatesEveryOperationAndReplayAgainstCurrentShopAndCsrf() throws Exception {
        String s = rpc.seller(), path = product(s), k = key();
        var uploaded = upload(path, s, k, image("png"), "image/png");
        assertThat(uploaded.statusCode()).isEqualTo(201);
        String id = tree(uploaded).path("uploadId").asString(), ak = key(), dk = key();
        assertThat(request("POST", path + "/images", attachBody(id, 0), s, ak).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", path + "/images/" + id, "{\"expectedVersion\":1}", s, dk).statusCode()).isEqualTo(200);
        for (String csrf : new String[]{null, "wrong"}) {
            assertThat(send("POST", path + "/image-uploads", multipart(image("png"), "image/png", ""),
                    "multipart/form-data; boundary=media-boundary", s, k, csrf).statusCode()).isEqualTo(403);
            assertThat(send("POST", path + "/images", attachBody(id, 0).getBytes(StandardCharsets.UTF_8), "application/json", s, ak, csrf).statusCode()).isEqualTo(403);
            assertThat(send("DELETE", path + "/images/" + id, "{\"expectedVersion\":1}".getBytes(StandardCharsets.UTF_8), "application/json", s, dk, csrf).statusCode()).isEqualTo(403);
        }
        UUID account = rpc.sessions.get(s), shop = rpc.shops.remove(account);
        for (int status : new int[]{403, 404, 503, 401}) {
            if (status == 404) rpc.shops.put(account, UUID.randomUUID());
            if (status == 503) rpc.merchantFailure.set(io.grpc.Status.UNAVAILABLE);
            if (status == 401) { rpc.merchantFailure.set(null); rpc.sessions.remove(s); }
            try {
                assertThat(request("GET", path + "/images", null, s, null).statusCode()).isEqualTo(status);
                assertThat(upload(path, s, k, image("png"), "image/png").statusCode()).isEqualTo(status);
                assertThat(request("POST", path + "/images", attachBody(id, 0), s, ak).statusCode()).isEqualTo(status);
                assertThat(request("DELETE", path + "/images/" + id, "{\"expectedVersion\":1}", s, dk).statusCode()).isEqualTo(status);
            } finally { rpc.merchantFailure.set(null); }
        }
        rpc.shops.put(account, shop);
    }

    @Test void rejectsForeignProductsUploadsAndMissingImagesWithoutLeakingOwnership() throws Exception {
        String a = rpc.seller(), b = rpc.seller(), pa = product(a), pb = product(b);
        String id = tree(upload(pa, a, key(), image("jpg"), "image/jpeg")).path("uploadId").asString();
        assertThat(request("GET", pa + "/images", null, b, null).statusCode()).isEqualTo(404);
        assertThat(upload(pa, b, key(), image("png"), "image/png").statusCode()).isEqualTo(404);
        assertThat(request("POST", pb + "/images", attachBody(id, 0), b, key()).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", pa + "/images/" + id, "{\"expectedVersion\":0}", b, key()).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", pa + "/images/" + key(), "{\"expectedVersion\":0}", a, key()).statusCode()).isEqualTo(404);
    }

    @Test void validatesMimeMagicBoundsMultipartAndLimitsBeforeProviderCalls() throws Exception {
        String s = rpc.seller(), path = product(s);
        int calls = storage.uploads.get();
        for (String mime : List.of("image/gif", "image/svg+xml", "image/webp", "application/octet-stream")) {
            assertThat(upload(path, s, key(), image("png"), mime).statusCode()).isEqualTo(415);
        }
        assertThat(upload(path, s, key(), image("png"), "image/jpeg").statusCode()).isEqualTo(415);
        assertThat(upload(path, s, key(), new byte[0], "image/png").statusCode()).isEqualTo(422);
        assertThat(upload(path, s, key(), "not an image".getBytes(StandardCharsets.UTF_8), "image/png").statusCode()).isEqualTo(415);
        assertThat(upload(path, s, key(), new byte[5242881], "image/png").statusCode()).isEqualTo(413);
        assertThat(upload(path, s, key(), new byte[6291457], "image/png").statusCode()).isEqualTo(413);
        for (String extra : List.of("--media-boundary\r\nContent-Disposition: form-data; name=\"publicId\"\r\n\r\nspoof\r\n",
                "--media-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"other.png\"\r\nContent-Type: image/png\r\n\r\nother\r\n")) {
            var r = send("POST", path + "/image-uploads", multipart(image("png"), "image/png", extra),
                    "multipart/form-data; boundary=media-boundary", s, key(), "masked-csrf-fixture");
            assertThat(r.statusCode()).isEqualTo(422);
        }
        byte[] huge = image("png");
        java.nio.ByteBuffer.wrap(huge).putInt(16, 20000001).putInt(20, 1);
        var crc = new java.util.zip.CRC32(); crc.update(huge, 12, 17);
        java.nio.ByteBuffer.wrap(huge).putInt(29, (int) crc.getValue());
        assertThat(upload(path, s, key(), huge, "image/png").statusCode()).isEqualTo(422);
        assertThat(storage.uploads.get()).isEqualTo(calls);
        assertThat(upload(path, s, key(), image("jpg"), "image/jpeg").statusCode()).isEqualTo(201);
    }

    @Test void validatesStrictJsonUuidsAndVersions() throws Exception {
        String s = rpc.seller(), path = product(s);
        for (String body : List.of("{}", "[]", "null", "{\"uploadId\":1,\"expectedVersion\":0}",
                attachBody("1-1-1-1-1", 0), attachBody("\\u0000" + key(), 0), attachBody(key(), -1), attachBody(key(), 9007199254740992L),
                attachBody(key(), 0).replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"),
                attachBody(key(), 0).replace("}", ",\"publicId\":\"spoof\"}")))
            assertThat(request("POST", path + "/images", body, s, key()).statusCode()).as(body).isEqualTo(422);
        for (String body : List.of("{broken", attachBody(key(), 0) + " {}", "{\"uploadId\":\"" + key() + "\",\"expectedVersion\":0,\"expectedVersion\":1}"))
            assertThat(request("POST", path + "/images", body, s, key()).statusCode()).as(body).isEqualTo(400);
        assertThat(upload(path, s, null, image("png"), "image/png").statusCode()).isEqualTo(422);
        assertThat(upload(path, s, "1-1-1-1-1", image("png"), "image/png").statusCode()).isEqualTo(422);
        assertThat(request("GET", "/api/v1/seller/products/not-uuid/images", null, s, null).statusCode()).isEqualTo(422);
        assertThat(request("DELETE", path + "/images/" + key(), "{\"expectedVersion\":0,\"extra\":1}", s, key()).statusCode()).isEqualTo(422);
    }

    @Test void keysBindFileProductOperationAndPayloadAndPersistForSevenDays() throws Exception {
        String s = rpc.seller(), path = product(s), k = key();
        byte[] png = image("png");
        String id = tree(upload(path, s, k, png, "image/png")).path("uploadId").asString();
        assertThat(upload(path, s, k, image("jpg"), "image/jpeg").statusCode()).isEqualTo(422);
        String other = product(rpc.seller());
        assertThat(upload(other, s, k, png, "image/png").statusCode()).isEqualTo(404);
        String sameShop = product(s);
        assertThat(upload(sameShop, s, k, png, "image/png").statusCode()).isEqualTo(422);
        String ak = key();
        assertThat(request("POST", path + "/images", attachBody(id, 0), s, ak).statusCode()).isEqualTo(200);
        assertThat(request("POST", path + "/images", attachBody(id, 1), s, ak).statusCode()).isEqualTo(422);
        assertThat(request("DELETE", path + "/images/" + id, "{\"expectedVersion\":1}", s, ak).statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("select min(expires_at-created_at)>=interval '7 days' from catalog.media_requests where actor_id=?",
                Boolean.class, rpc.sessions.get(s))).isTrue();
    }

    @Test void unknownUploadOnlyLooksUpReservedIdAndNeverUploadsAgain() throws Exception {
        String s = rpc.seller(), path = product(s), k = key(); byte[] png = image("png");
        int before = storage.uploads.get(), finds = storage.finds.get();
        storage.unknown = true;
        try {
            var failed = upload(path, s, k, png, "image/png");
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(failed.body()).doesNotContain("provider details");
        } finally { storage.unknown = false; }
        assertThat(upload(path, s, k, png, "image/png").statusCode()).isEqualTo(201);
        assertThat(storage.uploads.get()).isEqualTo(before + 1);
        assertThat(storage.finds.get()).isEqualTo(finds + 1);
        String missingKey = key(); storage.unknown = true;
        try { assertThat(upload(path, s, missingKey, png, "image/png").statusCode()).isEqualTo(503); }
        finally { storage.unknown = false; }
        String reserved = jdbc.queryForObject("select public_id from catalog.image_uploads where actor_id=? and key=?", String.class, rpc.sessions.get(s), UUID.fromString(missingKey));
        storage.assets.remove(reserved);
        assertThat(upload(path, s, missingKey, png, "image/png").statusCode()).isEqualTo(503);
        assertThat(storage.uploads.get()).isEqualTo(before + 2);
    }

    @Test void attachmentLimitStaleVersionsDuplicateAndExpiredUploads() throws Exception {
        String s = rpc.seller(), path = product(s); String sixth = null;
        for (int i = 0; i < 6; i++) {
            String id = tree(upload(path, s, key(), image("png"), "image/png")).path("uploadId").asString();
            if (i == 5) { sixth = id; break; }
            assertThat(request("POST", path + "/images", attachBody(id, i), s, key()).statusCode()).isEqualTo(200);
            assertThat(request("POST", path + "/images", attachBody(id, i + 1), s, key()).statusCode()).isEqualTo(409);
        }
        assertThat(request("POST", path + "/images", attachBody(sixth, 4), s, key()).statusCode()).isEqualTo(409);
        assertThat(request("POST", path + "/images", attachBody(sixth, 5), s, key()).statusCode()).isEqualTo(409);
        jdbc.update("update catalog.image_uploads set expires_at=now()-interval '1 second' where id=?", UUID.fromString(sixth));
        assertThat(request("POST", path + "/images", attachBody(sixth, 5), s, key()).statusCode()).isEqualTo(410);
        assertThat(tree(request("GET", path + "/images", null, s, null)).path("items").size()).isEqualTo(5);
    }

    @Test void actorQuotaIncludesUploadsAcrossProductsAndReleasesOnlyAfterCleanup() throws Exception {
        String s = rpc.seller(), path = product(s), other = product(s);
        for (int i = 0; i < 19; i++) assertThat(upload(path, s, key(), image("png"), "image/png").statusCode()).isEqualTo(201);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { gate.await(); return upload(path, s, key(), image("png"), "image/png").statusCode(); });
            var b = pool.submit(() -> { gate.await(); return upload(other, s, key(), image("png"), "image/png").statusCode(); });
            gate.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(upload(path, s, key(), image("png"), "image/png").statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select min(expires_at-created_at)=interval '24 hours' from catalog.image_uploads where actor_id=?",
                Boolean.class, rpc.sessions.get(s))).isTrue();
        jdbc.update("update catalog.image_uploads set expires_at=now()-interval '1 second' where actor_id=?", rpc.sessions.get(s));
        assertThat(upload(path, s, key(), image("png"), "image/png").statusCode()).isEqualTo(409);
        media.cleanup();
        assertThat(upload(path, s, key(), image("png"), "image/png").statusCode()).isEqualTo(201);
    }

    @Test void concurrentAttachmentVersionsHaveOneWinnerAndOneAudit() throws Exception {
        String s = rpc.seller(), path = product(s);
        var ids = new ArrayList<String>();
        for (int i = 0; i < 2; i++) ids.add(tree(upload(path, s, key(), image("png"), "image/png")).path("uploadId").asString());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { gate.await(); return request("POST", path + "/images", attachBody(ids.get(0), 0), s, key()).statusCode(); });
            var b = pool.submit(() -> { gate.await(); return request("POST", path + "/images", attachBody(ids.get(1), 0), s, key()).statusCode(); });
            gate.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from catalog.audit_entries where actor_account_id=? and action='ATTACH_IMAGE'", Integer.class, rpc.sessions.get(s))).isEqualTo(1);
    }

    @Test void uploadIntentCommitsBeforeNetworkAndOnlyOneUploadOccursForSameKeyRaces() throws Exception {
        String s = rpc.seller(), path = product(s), k = key();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        storage.onUpload = id -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select state from catalog.image_uploads where public_id=?", String.class, id)).isEqualTo("PENDING");
            entered.countDown();
            try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> upload(path, s, k, image("png"), "image/png"));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(upload(path, s, k, image("png"), "image/png").statusCode()).isEqualTo(503);
            } finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        }
        assertThat(upload(path, s, k, image("png"), "image/png").statusCode()).isEqualTo(201);
        assertThat(storage.uploads.get()).isEqualTo(1);
        assertThat(storage.finds.get()).isEqualTo(1);
    }

    @Test void metadataMustMatchReservedResourceFileAndDimensions() throws Exception {
        String s = rpc.seller(), path = product(s);
        List<java.util.function.UnaryOperator<ImageStorage.Stored>> invalid = List.of(
                v -> new ImageStorage.Stored("agora/products/" + key() + "/" + key(), v.version(), v.format(), v.bytes(), v.width(), v.height(), v.url()),
                v -> new ImageStorage.Stored(v.publicId(), 0, v.format(), v.bytes(), v.width(), v.height(), v.url()),
                v -> new ImageStorage.Stored(v.publicId(), v.version(), "jpg", v.bytes(), v.width(), v.height(), v.url()),
                v -> new ImageStorage.Stored(v.publicId(), v.version(), v.format(), v.bytes()+1, v.width(), v.height(), v.url()),
                v -> new ImageStorage.Stored(v.publicId(), v.version(), v.format(), v.bytes(), v.width()+1, v.height(), v.url()),
                v -> new ImageStorage.Stored(v.publicId(), v.version(), v.format(), v.bytes(), v.width(), v.height()+1, v.url()),
                v -> new ImageStorage.Stored(v.publicId(), v.version(), v.format(), v.bytes(), v.width(), v.height(), "https://evil.example/image.png"));
        for (var transform : invalid) {
            storage.metadata = transform;
            assertThat(upload(path, s, key(), image("png"), "image/png").statusCode()).isEqualTo(503);
        }
        assertThat(jdbc.queryForObject("select count(*) from catalog.image_uploads where state='UPLOADED'", Integer.class)).isZero();
        assertThat(tree(request("GET", path + "/images", null, s, null)).path("items").size()).isZero();
    }

    @Test void cleanupClaimsBeforeIoRetriesFailuresAndNeverDeletesAttachedAssets() throws Exception {
        String s = rpc.seller(), path = product(s), k = key();
        String attached = tree(upload(path, s, k, image("png"), "image/png")).path("uploadId").asString();
        String orphan = tree(upload(path, s, key(), image("jpg"), "image/jpeg")).path("uploadId").asString();
        assertThat(request("POST", path + "/images", attachBody(attached, 0), s, key()).statusCode()).isEqualTo(200);
        jdbc.update("update catalog.image_uploads set expires_at=now()-interval '1 second'");
        storage.onDelete = publicId -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select state from catalog.image_uploads where public_id=?", String.class, publicId)).isEqualTo("DELETE_PENDING");
            assertThat(jdbc.queryForObject("select count(*) from catalog.product_images p join catalog.image_uploads u on p.upload_id=u.id where public_id=?", Integer.class, publicId)).isZero();
        };
        storage.rejectDelete = true;
        media.cleanup();
        assertThat(state(orphan)).isEqualTo("DELETE_PENDING");
        assertThat(state(attached)).isEqualTo("UPLOADED");
        assertThat(storage.deleted).isEmpty();
        assertThat(request("POST", path + "/images", attachBody(orphan, 1), s, key()).statusCode()).isEqualTo(410);
        storage.rejectDelete = false;
        media.cleanup();
        assertThat(state(orphan)).isEqualTo("DELETED");
        assertThat(storage.deleted).containsExactly("agora/products/" + path.substring(path.lastIndexOf('/') + 1) + "/" + orphan);
        assertThat(request("DELETE", path + "/images/" + attached, "{\"expectedVersion\":1}", s, key()).statusCode()).isEqualTo(200);
        assertThat(storage.deleted.size()).isEqualTo(1);
        media.cleanup();
        assertThat(state(attached)).isEqualTo("DELETED");
        assertThat(upload(path, s, k, image("png"), "image/png").statusCode()).isEqualTo(410);
    }
    String state(String id) { return jdbc.queryForObject("select state from catalog.image_uploads where id=?", String.class, UUID.fromString(id)); }

    @Test void cleanupSkipsLockedRowsAndClaimsAtMostTwentyIncludingPendingUnknownAssets() throws Exception {
        String s = rpc.seller(), path = product(s);
        storage.unknown = true;
        for (int i = 0; i < 20; i++) assertThat(upload(path, s, key(), image("png"), "image/png").statusCode()).isEqualTo(503);
        storage.unknown = false;
        String other = rpc.seller(); String otherPath = product(other);
        assertThat(upload(otherPath, other, key(), image("png"), "image/png").statusCode()).isEqualTo(201);
        jdbc.update("update catalog.image_uploads set expires_at=now()-interval '1 second'");
        UUID locked = jdbc.queryForObject("select id from catalog.image_uploads order by id limit 1", UUID.class);
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from catalog.image_uploads where id=? for update")) {
                statement.setObject(1, locked); statement.execute();
            }
            media.cleanup();
            assertThat(storage.deleted.size()).isEqualTo(20);
            assertThat(state(locked.toString())).isIn("PENDING", "UPLOADED");
            connection.rollback();
        }
        media.cleanup();
        assertThat(storage.deleted.size()).isEqualTo(21);
        assertThat(jdbc.queryForObject("select count(*) from catalog.image_uploads where state<>'DELETED'", Integer.class)).isZero();
    }

    @Test void mutationFailuresRollbackRelationProductVersionAuditAndKeyTogether() throws Exception {
        String s = rpc.seller(), path = product(s);
        String id = tree(upload(path, s, key(), image("png"), "image/png")).path("uploadId").asString();
        UUID actor = rpc.sessions.get(s);
        for (boolean attach : new boolean[]{true, false}) {
            if (!attach) assertThat(request("POST", path + "/images", attachBody(id, 0), s, key()).statusCode()).isEqualTo(200);
            var before = tree(request("GET", path + "/images", null, s, null));
            String expires = jdbc.queryForObject("select expires_at::text from catalog.image_uploads where id=?", String.class, UUID.fromString(id));
            for (String table : attach ? List.of("product_images", "products", "audit_entries", "media_requests") : List.of("products", "audit_entries", "media_requests")) {
                String rule = switch (table) {
                    case "product_images" -> "upload_id<>'" + id + "'::uuid";
                    case "products" -> "id<>'" + path.substring(path.lastIndexOf('/') + 1) + "'::uuid";
                    case "audit_entries" -> "actor_account_id<>'" + actor + "'::uuid";
                    default -> "actor_id<>'" + actor + "'::uuid";
                };
                jdbc.execute("alter table catalog." + table + " add constraint media_test_failure check (" + rule + ") not valid");
                try {
                    var failed = request(attach ? "POST" : "DELETE", path + "/images" + (attach ? "" : "/" + id),
                            attach ? attachBody(id, 0) : "{\"expectedVersion\":1}", s, key());
                    assertThat(failed.statusCode()).as(table).isEqualTo(500);
                    assertThat(failed.body()).doesNotContain("media_test_failure", "SQLException", "INSERT", "UPDATE");
                    assertThat(tree(request("GET", path + "/images", null, s, null))).isEqualTo(before);
                    assertThat(jdbc.queryForObject("select count(*) from catalog.media_requests where actor_id=?", Integer.class, actor)).isEqualTo(attach ? 0 : 1);
                    assertThat(jdbc.queryForObject("select count(*) from catalog.audit_entries where actor_account_id=?", Integer.class, actor)).isEqualTo(attach ? 1 : 2);
                    assertThat(jdbc.queryForObject("select expires_at::text from catalog.image_uploads where id=?", String.class, UUID.fromString(id))).isEqualTo(expires);
                } finally { jdbc.execute("alter table catalog." + table + " drop constraint media_test_failure"); }
            }
        }
    }

    @Test void draftGuardPrecedesReadingAndEveryReplay() throws Exception {
        String s = rpc.seller(), path = product(s), k = key();
        String id = tree(upload(path, s, k, image("png"), "image/png")).path("uploadId").asString();
        String ak = key();
        assertThat(request("POST", path + "/images", attachBody(id, 0), s, ak).statusCode()).isEqualTo(200);
        try {
            jdbc.update("update catalog.products set status='PUBLISHED'");
            assertThat(request("GET", path + "/images", null, s, null).statusCode()).isEqualTo(409);
            assertThat(upload(path, s, k, image("png"), "image/png").statusCode()).isEqualTo(409);
            assertThat(request("POST", path + "/images", attachBody(id, 0), s, ak).statusCode()).isEqualTo(409);
            assertThat(request("DELETE", path + "/images/" + id, "{\"expectedVersion\":1}", s, key()).statusCode()).isEqualTo(409);
        } finally {
            jdbc.update("update catalog.products set status='DRAFT'");
        }
    }

    @Test void expiredMediaKeysArePurgedButCannotBypassVersionGuards() throws Exception {
        String s = rpc.seller(), path = product(s), k = key();
        String id = tree(upload(path, s, key(), image("png"), "image/png")).path("uploadId").asString();
        assertThat(request("POST", path + "/images", attachBody(id, 0), s, k).statusCode()).isEqualTo(200);
        jdbc.update("update catalog.media_requests set expires_at=now()-interval '1 second'");
        media.cleanup();
        assertThat(jdbc.queryForObject("select count(*) from catalog.media_requests", Integer.class)).isZero();
        assertThat(request("POST", path + "/images", attachBody(id, 0), s, k).statusCode()).isEqualTo(409);
    }

    @Test void reserveFailuresDoNotCallProviderAndCompletionFailuresRecoverByLookup() throws Exception {
        String s = rpc.seller(), path = product(s), k = key();
        jdbc.execute("alter table catalog.image_uploads add constraint media_test_failure check(state<>'PENDING') not valid");
        try {
            assertThat(upload(path, s, k, image("png"), "image/png").statusCode()).isEqualTo(500);
            assertThat(storage.uploads.get()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from catalog.image_uploads", Integer.class)).isZero();
        } finally { jdbc.execute("alter table catalog.image_uploads drop constraint media_test_failure"); }
        jdbc.execute("alter table catalog.image_uploads add constraint media_test_failure check(state<>'UPLOADED') not valid");
        try {
            assertThat(upload(path, s, k, image("png"), "image/png").statusCode()).isEqualTo(500);
            assertThat(storage.uploads.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select state from catalog.image_uploads", String.class)).isEqualTo("PENDING");
        } finally { jdbc.execute("alter table catalog.image_uploads drop constraint media_test_failure"); }
        assertThat(upload(path, s, k, image("png"), "image/png").statusCode()).isEqualTo(201);
        assertThat(storage.uploads.get()).isEqualTo(1);
        assertThat(storage.finds.get()).isEqualTo(1);
    }

    @Test void databaseConstrainsResourceMetadataAndMultipartSpillsOnlyInsideWorkspace() throws Exception {
        String s = rpc.seller(), path = product(s);
        assertThat(upload(path, s, key(), image("png"), "image/png").statusCode()).isEqualTo(201);
        for (String assignment : List.of("bytes=0", "bytes=5242881", "width=20000000,height=2", "state='OTHER'", "format='gif'",
                "format='jpg'", "provider_version=0", "url='http://evil.example'", "public_id='legacy/asset'", "product_id='" + key() + "'::uuid"))
            assertThatThrownBy(() -> jdbc.update("update catalog.image_uploads set " + assignment)).as(assignment)
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var config = application.getBean(jakarta.servlet.MultipartConfigElement.class);
        assertThat(config.getMaxFileSize()).isEqualTo(5242880);
        assertThat(config.getMaxRequestSize()).isEqualTo(6291456);
        var location = java.nio.file.Path.of(config.getLocation());
        assertThat(location).isEqualTo(java.nio.file.Path.of("").toRealPath().resolve("target/upload-tmp"));
        assertThat(java.nio.file.Files.isDirectory(location, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(java.nio.file.Files.isSymbolicLink(location)).isFalse();
    }
    @Autowired org.springframework.context.ApplicationContext application;
}
