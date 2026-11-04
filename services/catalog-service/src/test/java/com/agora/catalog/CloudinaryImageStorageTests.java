package com.agora.catalog;

import com.agora.catalog.adapter.outbound.CloudinaryImageStorage;
import com.agora.catalog.domain.CatalogFailure;
import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class CloudinaryImageStorageTests {
    static final String ID = "agora/products/04caabf1-32ae-4ff9-8b15-e715d744104b/9b974801-6d64-4119-bf4e-3a0c140fcceb";
    static final String META = """
            {"public_id":"%s","resource_type":"image","type":"upload","version":1234,"format":"png",
             "width":2,"height":3,"bytes":68,"secure_url":"https://attacker.invalid/ignored"}
            """.formatted(ID);
    HttpClient http;
    CloudinaryImageStorage storage;
    List<HttpRequest> requests;

    @BeforeEach void setup() throws Exception {
        http = mock(HttpClient.class); requests = new ArrayList<>();
        storage = new CloudinaryImageStorage(http, JsonMapper.builder().build(), "fixture-cloud", "fixture-key", "fixture-secret");
    }
    @SuppressWarnings("unchecked") void replies(int... statuses) throws Exception {
        var queue = new ArrayDeque<Integer>(); for (int status : statuses) queue.add(status);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0); requests.add(request);
            int status = queue.remove();
            var response = (HttpResponse<byte[]>) mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            String body = request.uri().getPath().endsWith("/destroy") ? "{\"result\":\"ok\"}" : META;
            when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(response);
        });
    }
    @SuppressWarnings("unchecked") void response(int status, String body) throws Exception {
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            var response = (HttpResponse<byte[]>) mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(response);
        });
    }
    static String body(HttpRequest request) {
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { byte[] a = new byte[b.remaining()]; b.get(a); bytes.writeBytes(a); }
            public void onError(Throwable t) { throw new AssertionError(t); }
            public void onComplete() { }
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }
    @Test void uploadUsesFixedHttpsAuthenticatedMultipartNoOverwriteAndRechecksMetadata() throws Exception {
        replies(200, 200);
        var image = storage.upload(new byte[]{1,2,3}, "image/png", ID);
        assertThat(requests).hasSize(2);
        var upload = requests.getFirst();
        assertThat(upload.uri().toString()).isEqualTo("https://api.cloudinary.com/v1_1/fixture-cloud/image/upload");
        assertThat(upload.method()).isEqualTo("POST");
        assertThat(upload.timeout()).contains(Duration.ofSeconds(8));
        assertThat(upload.headers().firstValue("Authorization")).contains("Basic " + Base64.getEncoder().encodeToString("fixture-key:fixture-secret".getBytes(StandardCharsets.UTF_8)));
        assertThat(body(upload)).contains("name=\"public_id\"", ID, "name=\"overwrite\"\r\n\r\nfalse", "Content-Type: image/png");
        assertThat(requests.getLast().method()).isEqualTo("GET");
        assertThat(image.publicId()).isEqualTo(ID);
        assertThat(image.url()).isEqualTo("https://res.cloudinary.com/fixture-cloud/image/upload/v1234/" + ID + ".png");
        assertThat(image.width()).isEqualTo(2); assertThat(image.height()).isEqualTo(3); assertThat(image.bytes()).isEqualTo(68);
    }
    @Test void missingResourceIsEmptyButProviderErrorsAreNotMissing() throws Exception {
        replies(404, 401);
        assertThat(storage.find(ID)).isEmpty();
        assertThatThrownBy(() -> storage.find(ID)).isInstanceOfSatisfying(CatalogFailure.class, e -> {
            assertThat(e.status()).isEqualTo(503); assertThat(e.getMessage()).doesNotContain("fixture-key", "fixture-secret");
        });
    }
    @Test void destroyUsesOnlyReservedPublicIdAndAcceptsAlreadyMissing() throws Exception {
        response(200, "{\"result\":\"not found\"}"); storage.delete(ID);
        var request = requests.getFirst();
        assertThat(request.uri().toString()).isEqualTo("https://api.cloudinary.com/v1_1/fixture-cloud/image/destroy");
        assertThat(body(request)).contains("public_id=agora%2Fproducts%2F", "invalidate=true");
    }
    @Test void mediaNamespacesCannotCrossServiceBoundaries() {
        var avatars=new com.agora.media.CloudinaryImageStorage(http,JsonMapper.builder().build(),"fixture-cloud","fixture-key","fixture-secret","avatars",()->new IllegalStateException("Synthetic failure"));
        String avatar=ID.replace("/products/","/avatars/");
        assertThatThrownBy(()->avatars.delete(ID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->storage.find(avatar)).isInstanceOf(CatalogFailure.class);
        assertThatThrownBy(()->storage.upload(new byte[]{1},"image/png",avatar)).isInstanceOf(CatalogFailure.class);
        verifyNoInteractions(http);
    }
    @Test void arbitraryIdsNeverReachProvider() {
        for (String id : List.of("sample", "https://attacker.invalid/file", "agora/products/../foreign", ID + "/more")) {
            assertThatThrownBy(() -> storage.delete(id)).isInstanceOf(CatalogFailure.class);
            assertThatThrownBy(() -> storage.find(id)).isInstanceOf(CatalogFailure.class);
        }
        verifyNoInteractions(http);
    }
    @Test void metadataMustMatchIdTypeFormatAndBounds() throws Exception {
        for (String metadata : List.of(META.replace(ID, "sample"), META.replace("\"image\"", "\"raw\""),
                META.replace("\"upload\"", "\"private\""), META.replace("\"png\"", "\"svg\""),
                META.replace("\"width\":2", "\"width\":20000001"), META.replace("\"bytes\":68", "\"bytes\":5242881"),
                META.replace("\"height\":3", "\"height\":0"), META.replace("\"version\":1234", "\"version\":1.5"))) {
            response(200, metadata);
            assertThatThrownBy(() -> storage.find(ID)).isInstanceOf(CatalogFailure.class);
        }
    }
    @Test void oversizedOrMalformedProviderJsonIsRejected() throws Exception {
        for (String value : List.of("not-json", " ".repeat(65537), "{}")) {
            response(200, value); assertThatThrownBy(() -> storage.find(ID)).isInstanceOf(CatalogFailure.class);
        }
    }
    @Test void networkFailureHasGeneric503AndDoesNotLeakDiagnostics() throws Exception {
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(
                CompletableFuture.failedFuture(new IOException("fixture-secret internal details")));
        assertThatThrownBy(() -> storage.find(ID)).isInstanceOfSatisfying(CatalogFailure.class, e -> {
            assertThat(e.status()).isEqualTo(503); assertThat(e.getMessage()).doesNotContain("fixture-secret", "internal details");
        });
    }
    @Test void exchangeStartupFailureAlsoHasSanitized503() {
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("fixture-secret"));
        assertThatThrownBy(() -> storage.find(ID)).isInstanceOfSatisfying(CatalogFailure.class,
                e -> { assertThat(e.status()).isEqualTo(503); assertThat(e.getMessage()).doesNotContain("fixture-secret"); });
    }
    @Test @SuppressWarnings("unchecked") void incompleteBodyIsCancelledAtDeadline() {
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        long start = System.nanoTime();
        assertThatThrownBy(() -> storage.find(ID)).isInstanceOfSatisfying(CatalogFailure.class, e -> assertThat(e.status()).isEqualTo(503));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofSeconds(7), Duration.ofSeconds(10));
        assertThat(pending.isCancelled()).isTrue();
    }
    @Test @SuppressWarnings("unchecked") void bodySubscriberCancelsBeforeBufferingMoreThan64KiB() {
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpResponse.BodyHandler<byte[]> handler = call.getArgument(1);
            var subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            var subscription = mock(Flow.Subscription.class);
            subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[65537])));
            verify(subscription).cancel();
            return subscriber.getBody().toCompletableFuture();
        });
        assertThatThrownBy(() -> storage.find(ID)).isInstanceOf(CatalogFailure.class);
    }
    @Test void unconfiguredStorageFailsOnUseAndPartialConfigurationFailsStartup() {
        var unconfigured = new CloudinaryImageStorage(http, JsonMapper.builder().build(), "", "", "");
        assertThatThrownBy(() -> unconfigured.find(ID)).isInstanceOfSatisfying(CatalogFailure.class, e -> assertThat(e.status()).isEqualTo(503));
        assertThatThrownBy(() -> new CloudinaryImageStorage(http, JsonMapper.builder().build(), "fixture-cloud", "", "secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloudinaryImageStorage(http, JsonMapper.builder().build(), "bad/host", "key", "secret"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(http);
    }
}
