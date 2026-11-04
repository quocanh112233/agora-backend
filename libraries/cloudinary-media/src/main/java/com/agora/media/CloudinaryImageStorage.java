package com.agora.media;



import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class CloudinaryImageStorage implements ImageStorage {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final HttpClient http;
    private final JsonMapper json;
    private final String cloud;
    private final String authorization;
    private final String namespace;
    private final java.util.function.Supplier<? extends RuntimeException> failure;

    public CloudinaryImageStorage(HttpClient http, JsonMapper json, String cloud, String key, String secret, String namespace, java.util.function.Supplier<? extends RuntimeException> failure) {
        if (!java.util.Set.of("products", "avatars").contains(namespace)) throw new IllegalArgumentException("Invalid media namespace");
        this.namespace = namespace; this.failure = java.util.Objects.requireNonNull(failure);
        this.http = http; this.json = json; this.cloud = cloud;
        boolean configured = !cloud.isBlank() && !key.isBlank() && !secret.isBlank();
        if (!(cloud.isBlank() && key.isBlank() && secret.isBlank()) &&
                (!configured || !cloud.matches("[A-Za-z0-9_-]{1,128}") || key.contains(":") ||
                        key.contains("\r") || key.contains("\n") || secret.contains("\r") || secret.contains("\n")))
            throw new IllegalArgumentException("Cloudinary configuration is incomplete or invalid.");
        authorization = configured ? "Basic " + Base64.getEncoder().encodeToString(
                (key + ":" + secret).getBytes(StandardCharsets.UTF_8)) : null;
    }

    @Override public Stored upload(byte[] data, String mime, String publicId) {
        check(publicId);
        if (data == null || data.length == 0 || data.length > 5 * 1024 * 1024 ||
                !("image/png".equals(mime) || "image/jpeg".equals(mime))) throw unavailable();
        String boundary = "agora-" + UUID.randomUUID();
        String fields = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"public_id\"\r\n\r\n" + publicId +
                "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"overwrite\"\r\n\r\nfalse" +
                "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"asset\"\r\nContent-Type: " + mime + "\r\n\r\n";
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(fields),
                HttpRequest.BodyPublishers.ofByteArray(data), HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        exchange(request("image/upload").header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build(), false);
        // Read the reserved resource independently; upload response fields cannot select the stored asset.
        return find(publicId).orElseThrow(this::unavailable);
    }

    @Override public Optional<Stored> find(String publicId) {
        check(publicId);
        JsonNode meta = exchange(request("resources/image/upload/" + encode(publicId)).GET().build(), true);
        if (meta == null) return Optional.empty();
        String format = meta.path("format").asString("");
        if (format.equals("jpeg")) format = "jpg";
        if (!meta.path("public_id").asString("").equals(publicId) ||
                !meta.path("resource_type").asString("").equals("image") ||
                !meta.path("type").asString("").equals("upload") ||
                !(format.equals("png") || format.equals("jpg"))) throw unavailable();
        long version = integer(meta, "version", 9_007_199_254_740_991L);
        long bytes = integer(meta, "bytes", 5 * 1024 * 1024);
        int width = (int) integer(meta, "width", 20_000_000);
        int height = (int) integer(meta, "height", 20_000_000);
        if ((long) width * height > 20_000_000) throw unavailable();
        return Optional.of(new Stored(publicId, version, format, bytes, width, height,
                "https://res.cloudinary.com/" + cloud + "/image/upload/v" + version + "/" + publicId + "." + format));
    }

    @Override public void delete(String publicId) {
        check(publicId);
        var result = exchange(request("image/destroy").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("public_id=" + encode(publicId) + "&invalidate=true")).build(), false);
        if (!java.util.Set.of("ok", "not found", "not_found").contains(result.path("result").asString(""))) throw unavailable();
    }

    private void check(String publicId) {
        if (authorization == null || publicId == null ||
                !publicId.matches("agora/" + namespace + "/" + UUID_PATTERN + "/" + UUID_PATTERN)) throw unavailable();
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("https://api.cloudinary.com/v1_1/" + cloud + "/" + path))
                .timeout(Duration.ofSeconds(8)).header("Authorization", authorization).header("Accept", "application/json");
    }
    private JsonNode exchange(HttpRequest request, boolean allowMissing) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = http.sendAsync(request, info -> new LimitedBody());
            // JDK 25's request timeout ends at headers; this deadline includes the complete body.
            var response = pending.get(8, TimeUnit.SECONDS);
            if (allowMissing && response.statusCode() == 404) return null;
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable();
            byte[] bytes = response.body();
            if (bytes.length > 65536) throw unavailable();
            JsonNode result = json.readTree(bytes);
            if (result == null || !result.isObject()) throw unavailable();
            return result;
        } catch (InterruptedException failure) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (TimeoutException | ExecutionException | RuntimeException failure) {
            if (pending != null) pending.cancel(true);
            // Provider diagnostics may contain credentials or account details.
            throw unavailable();
        }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription; subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > 65536 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Response is too large.")); return;
                }
                byte[] data = new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    private long integer(JsonNode object, String field, long max) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw unavailable();
        long number = value.asLong();
        if (number < 1 || number > max) throw unavailable();
        return number;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private RuntimeException unavailable() { return failure.get(); }
}
