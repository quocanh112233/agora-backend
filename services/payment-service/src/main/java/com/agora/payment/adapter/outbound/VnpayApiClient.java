package com.agora.payment.adapter.outbound;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded transport only. The caller must authenticate and bind the returned map with VnpayApiCodec. */
public final class VnpayApiClient {
    private static final URI ENDPOINT = URI.create("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_BYTES = 64 * 1024;
    private final JsonMapper json;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public VnpayApiClient(JsonMapper json) { this(json, ENDPOINT, TIMEOUT); }

    // Only an exact loopback fixture may replace the fixed provider endpoint.
    VnpayApiClient(JsonMapper json, URI endpoint, Duration timeout) {
        boolean loopback = endpoint != null && "http".equals(endpoint.getScheme())
                && "127.0.0.1".equals(endpoint.getHost()) && endpoint.getPort() > 0
                && endpoint.getPort() <= 65535 && ENDPOINT.getRawPath().equals(endpoint.getRawPath())
                && endpoint.getRawUserInfo() == null && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null;
        if (json == null || !(ENDPOINT.equals(endpoint) || loopback) || timeout == null
                || timeout.isZero() || timeout.isNegative() || timeout.compareTo(TIMEOUT) > 0)
            throw new IllegalArgumentException("Invalid VNPAY API configuration");
        this.json = json.rebuild().disable(JsonReadFeature.values())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    /** Failures are deliberately generic and carry no response, request, secret, or nested cause. */
    public Map<String, String> send(VnpayApiCodec.Request request) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            if (request == null) throw failure();
            var wire = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request.params()))).build();
            pending = http.sendAsync(wire, info -> {
                if (info.statusCode() != 200 || info.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_BYTES)
                    throw failure();
                return new BoundedBody();
            });
            // HttpRequest's timeout alone does not bound a body that stalls after its headers arrive.
            return parse(pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS).body());
        } catch (InterruptedException e) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            if (pending != null) pending.cancel(true);
            throw failure();
        }
    }

    private Map<String, String> parse(byte[] body) {
        var values = new HashMap<String, String>();
        int length = 0;
        try (var parser = json.createParser(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString())) {
            require(parser.nextToken() == JsonToken.START_OBJECT);
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.PROPERTY_NAME && values.size() < 32);
                String name = parser.currentName();
                require(name.length() <= 64 && name.matches("vnp_[A-Za-z0-9_]{1,60}"));
                require(parser.nextToken() == JsonToken.VALUE_STRING);
                String value = parser.getString();
                require(value.length() <= 512 && value.chars().allMatch(c -> c >= 32 && c <= 126 && c != '|'));
                length += name.length() + value.length();
                require(length <= 8192 && values.putIfAbsent(name, value) == null);
            }
            require(!values.isEmpty() && parser.nextToken() == null);
        } catch (CharacterCodingException e) {
            throw failure();
        }
        return Map.copyOf(values);
    }

    /** Check each incoming batch before the JDK subscriber can allocate or copy its body storage. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;

        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                bytes += buffer.remaining();
                if (bytes > MAX_BYTES) {
                    subscription.cancel();
                    delegate.onError(new IOException("VNPAY API response exceeds byte limit"));
                    return;
                }
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { delegate.onError(error); }
        @Override public void onComplete() { delegate.onComplete(); }
    }

    private static void require(boolean condition) { if (!condition) throw failure(); }
    private static IllegalStateException failure() { return new IllegalStateException("VNPAY API request failed"); }
}
