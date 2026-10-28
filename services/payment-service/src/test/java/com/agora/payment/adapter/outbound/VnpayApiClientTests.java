package com.agora.payment.adapter.outbound;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class VnpayApiClientTests {
    private static final String PATH = "/merchant_webapi/api/transaction";
    private static final String GOOD = "{\"vnp_ResponseCode\":\"00\",\"vnp_Message\":\"Request successful\"}";
    private static final String PRIVATE = "synthetic-private-response";
    private final JsonMapper json = new JsonMapper();
    private final VnpayApiCodec.Request request = new VnpayApiCodec("TESTCODE", "synthetic-test-secret")
            .query("req123", new VnpayApiCodec.Expected("abc123", 5000), "20261028120000",
                    "20261028130000", "127.0.0.1", "Query order abc123");
    private HttpServer server;
    private ExecutorService executor;
    private URI endpoint;
    private VnpayApiClient client;
    private final AtomicReference<Handler> handler = new AtomicReference<>();

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws Exception; }

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) { handler.get().handle(exchange); }
            catch (Exception ignored) { /* Timeout/size rejection can close the fixture connection. */ }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH);
        client = new VnpayApiClient(json, endpoint, Duration.ofSeconds(2));
    }

    @AfterEach void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void reply(HttpExchange e, int status, String body, boolean chunked) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(status, chunked ? 0 : bytes.length);
        e.getResponseBody().write(bytes);
    }

    private void fails() {
        var error = assertThrows(IllegalStateException.class, () -> client.send(request));
        assertEquals("VNPAY API request failed", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.toString().contains(PRIVATE));
    }

    @Test void postsExactJsonAndReturnsImmutableStringMapWithoutClaimingVerification() {
        var method = new AtomicReference<String>();
        var path = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var payload = new AtomicReference<String>();
        handler.set(e -> {
            method.set(e.getRequestMethod()); path.set(e.getRequestURI().toString());
            contentType.set(e.getRequestHeaders().getFirst("Content-Type"));
            payload.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(e, 200, GOOD, false);
        });
        var result = client.send(request);
        assertEquals("POST", method.get());
        assertEquals(PATH, path.get());
        assertEquals("application/json", contentType.get());
        assertEquals(json.valueToTree(request.params()), json.readTree(payload.get()));
        assertEquals(Map.of("vnp_ResponseCode", "00", "vnp_Message", "Request successful"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.put("vnp_ResponseCode", "99"));
    }

    @Test void redirectsAreNeverFollowed() {
        var hits = new AtomicInteger();
        handler.set(e -> {
            hits.incrementAndGet();
            e.getResponseHeaders().set("Location", endpoint + "/redirected");
            reply(e, 302, PRIVATE, false);
        });
        fails();
        assertEquals(1, hits.get());
    }

    @Test void non200ResponsesAreSanitized() {
        for (int status : new int[]{201, 400, 500}) {
            handler.set(e -> reply(e, status, PRIVATE, false));
            fails();
        }
    }

    @Test void rejectsOversizedDeclaredAndChunkedBodies() {
        for (boolean chunked : List.of(false, true)) {
            handler.set(e -> reply(e, 200, " ".repeat(65537) + GOOD, chunked));
            fails();
        }
    }

    @Test void acceptsExactly64KiBButRejectsOneExtraByteWhileStreaming() {
        String exact = " ".repeat(65536 - GOOD.getBytes(StandardCharsets.UTF_8).length) + GOOD;
        handler.set(e -> reply(e, 200, exact, true));
        assertEquals("00", client.send(request).get("vnp_ResponseCode"));
        handler.set(e -> reply(e, 200, exact + " ", true));
        fails();
    }

    @Test void slowHeadersAndSlowBodyBothRespectWholeResponseDeadline() {
        client = new VnpayApiClient(json, endpoint, Duration.ofMillis(150));
        for (boolean headersFirst : List.of(false, true)) {
            handler.set(e -> {
                if (headersFirst) {
                    e.sendResponseHeaders(200, 0);
                    e.getResponseBody().write('{');
                    e.getResponseBody().flush();
                }
                Thread.sleep(1500);
                if (!headersFirst) reply(e, 200, GOOD, false);
            });
            assertTimeoutPreemptively(Duration.ofSeconds(1), this::fails);
        }
    }

    @Test void trickledBodyCannotExtendDeadline() {
        client = new VnpayApiClient(json, endpoint, Duration.ofMillis(150));
        handler.set(e -> {
            e.sendResponseHeaders(200, 0);
            for (int i = 0; i < 20; i++) {
                e.getResponseBody().write(' '); e.getResponseBody().flush(); Thread.sleep(40);
            }
            e.getResponseBody().write(GOOD.getBytes(StandardCharsets.UTF_8));
        });
        assertTimeoutPreemptively(Duration.ofSeconds(1), this::fails);
    }

    @Test void malformedDuplicateTrailingAndNonStringJsonAreRejected() {
        for (String body : List.of("", "{", "[]", "null", "{}", "{\"vnp_x\":1}",
                "{\"vnp_x\":null}", "{\"vnp_x\":true}", "{\"vnp_x\":{}}", "{\"vnp_x\":[]}",
                GOOD + "{}", GOOD + "private", "{\"vnp_x\":\"a\",\"vnp_x\":\"b\"}",
                "{\"vnp_x\":\"a\",\"vnp_\\u0078\":\"b\"}", "{\"vnp_x\":\"" + PRIVATE + "\",}")) {
            handler.set(e -> reply(e, 200, body, false));
            fails();
        }
    }

    @Test void callerPermissiveMapperCannotWeakenStrictJson() {
        client = new VnpayApiClient(JsonMapper.builder().enable(JsonReadFeature.values()).build(), endpoint, Duration.ofSeconds(2));
        for (String body : List.of("{'vnp_x':'a'}", "{vnp_x:\"a\"}", "/* comment */" + GOOD, GOOD + "// comment")) {
            handler.set(e -> reply(e, 200, body, false));
            fails();
        }
    }

    @Test void mapNameValueCountAndTotalBoundsAreEnforced() {
        var cases = new java.util.ArrayList<String>();
        cases.add("{\"other\":\"a\"}");
        cases.add(json.writeValueAsString(Map.of("vnp_" + "x".repeat(61), "a")));
        cases.add(json.writeValueAsString(Map.of("vnp_x", "x".repeat(513))));
        cases.add(json.writeValueAsString(Map.of("vnp_x", "bad|field")));
        cases.add(json.writeValueAsString(Map.of("vnp_x", "bad\nfield")));
        var many = new java.util.HashMap<String, String>();
        for (int i = 0; i < 33; i++) many.put("vnp_" + i, "a");
        cases.add(json.writeValueAsString(many));
        many.clear();
        for (int i = 0; i < 17; i++) many.put("vnp_" + i, "x".repeat(512));
        cases.add(json.writeValueAsString(many));
        for (String body : cases) {
            handler.set(e -> reply(e, 200, body, false));
            fails();
        }
    }

    @Test void malformedUtf8AndTruncatedHttpBodiesAreRejected() {
        handler.set(e -> {
            e.sendResponseHeaders(200, 0);
            e.getResponseBody().write(new byte[]{'{', '"', 'v', 'n', 'p', '_', 'x', '"', ':', '"', (byte) 0xc0, (byte) 0xaf, '"', '}'});
        });
        fails();
        handler.set(e -> { e.sendResponseHeaders(200, 100); e.getResponseBody().write(GOOD.getBytes(StandardCharsets.UTF_8)); });
        fails();
    }

    @Test void fixtureConstructorAcceptsOnlyExactLoopbackOrFixedEndpointAndBoundedTimeout() {
        for (String uri : List.of("http://localhost:1234" + PATH, "http://127.0.0.2:1234" + PATH,
                "http://127.0.0.1.example.test:1234" + PATH, "https://other.example" + PATH,
                "http://user@127.0.0.1:1234" + PATH, endpoint + "?next=bad", endpoint + "#bad"))
            assertThrows(IllegalArgumentException.class, () -> new VnpayApiClient(json, URI.create(uri), Duration.ofSeconds(1)));
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(9)))
            assertThrows(IllegalArgumentException.class, () -> new VnpayApiClient(json, endpoint, timeout));
        assertDoesNotThrow(() -> new VnpayApiClient(json));
    }
}
