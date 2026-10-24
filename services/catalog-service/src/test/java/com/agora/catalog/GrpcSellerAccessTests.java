package com.agora.catalog;

import com.agora.catalog.adapter.outbound.GrpcSellerAccess;
import com.agora.catalog.configuration.RpcConfiguration;
import com.agora.catalog.domain.CatalogFailure;
import io.grpc.Status;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class GrpcSellerAccessTests {
    RpcFixture rpc;
    GrpcSellerAccess client;
    @BeforeEach void start() throws Exception {
        rpc = new RpcFixture(); client = new GrpcSellerAccess(rpc.identityChannel, rpc.merchantChannel, RpcFixture.TOKEN);
    }
    @AfterEach void close() throws Exception { rpc.close(); }
    @Test void forwardsRawCookieMaskedCsrfCredentialAndCurrentAccountToSeparateServices() {
        String session = rpc.seller();
        var seller = client.resolve(session, "masked-csrf-fixture", true);
        assertThat(seller.accountId()).isEqualTo(rpc.sessions.get(session));
        assertThat(seller.shopId()).isEqualTo(rpc.shops.get(seller.accountId()));
        assertThat(rpc.identityRequest.get().getSessionId()).isEqualTo(session);
        assertThat(rpc.identityRequest.get().getCsrfToken()).isEqualTo("masked-csrf-fixture");
        assertThat(rpc.identityRequest.get().getRequireCsrf()).isTrue();
        assertThat(rpc.merchantRequest.get().getAccountId()).isEqualTo(seller.accountId().toString());
        assertThat(rpc.identityDeadline.get()).isBetween(1L, 2000L);
        assertThat(rpc.merchantDeadline.get()).isBetween(1L, 2000L);
        client.resolve(session, null, false);
        assertThat(rpc.identityRequest.get().getRequireCsrf()).isFalse();
        assertThat(rpc.identityRequest.get().getCsrfToken()).isEmpty();
        rpc.shops.remove(seller.accountId());
        assertFailure(() -> client.resolve(session, null, false), 403);
        rpc.sessions.remove(session);
        assertFailure(() -> client.resolve(session, null, false), 401);
    }
    @Test void identityFailuresNeverCallMerchantOrRetryAndHideDiagnostics() {
        for (var entry : Map.of(Status.Code.UNAUTHENTICATED, 401, Status.Code.PERMISSION_DENIED, 403,
                Status.Code.UNAVAILABLE, 503, Status.Code.DEADLINE_EXCEEDED, 503, Status.Code.INTERNAL, 503).entrySet()) {
            rpc.identityFailure.set(Status.fromCode(entry.getKey()).withDescription("private diagnostic"));
            int before = rpc.identityCalls.get();
            assertFailure(() -> client.resolve("session", "csrf", true), entry.getValue());
            assertThat(rpc.identityCalls.get()).isEqualTo(before + 1);
            assertThat(rpc.merchantCalls.get()).isZero();
        }
    }
    @Test void merchantAuthorizationIs403AndOtherFailuresAre503WithoutRetry() {
        String session = rpc.seller();
        for (var entry : Map.of(Status.Code.PERMISSION_DENIED, 403, Status.Code.UNAUTHENTICATED, 503,
                Status.Code.INVALID_ARGUMENT, 503, Status.Code.UNAVAILABLE, 503, Status.Code.INTERNAL, 503).entrySet()) {
            rpc.merchantFailure.set(Status.fromCode(entry.getKey()).withDescription("private diagnostic"));
            int before = rpc.merchantCalls.get();
            assertFailure(() -> client.resolve(session, null, false), entry.getValue());
            assertThat(rpc.merchantCalls.get()).isEqualTo(before + 1);
        }
    }
    @Test void bothServicesHaveRealTwoSecondDeadlines() {
        String session = rpc.seller();
        for (boolean identity : new boolean[]{true, false}) {
            rpc.hangIdentity = identity; rpc.hangMerchant = !identity;
            long start = System.nanoTime();
            assertFailure(() -> client.resolve(session, null, false), 503);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(1500L, 4500L);
        }
    }
    @Test void missingSessionsMalformedResponsesAndCredentialsFailClosed() {
        assertFailure(() -> client.resolve(null, null, false), 401);
        assertThat(rpc.identityCalls.get()).isZero();
        String session = rpc.seller();
        for (String malformed : new String[]{"", "not-a-uuid", "1-1-1-1-1"}) {
            rpc.accountOverride.set(malformed);
            assertFailure(() -> client.resolve(session, null, false), 503);
            assertThat(rpc.merchantCalls.get()).isZero();
        }
        rpc.accountOverride.set(null); rpc.shopOverride.set("not-a-uuid");
        assertFailure(() -> client.resolve(session, null, false), 503);
        for (String invalid : new String[]{"short", "a".repeat(257), "a".repeat(32) + "+", "a".repeat(32) + "="})
            assertThatThrownBy(() -> new GrpcSellerAccess(rpc.identityChannel, rpc.merchantChannel, invalid)).isInstanceOf(IllegalArgumentException.class);
        var wrong = new GrpcSellerAccess(rpc.identityChannel, rpc.merchantChannel, "wrong-token-at-least-32-characters");
        assertFailure(() -> wrong.resolve(session, null, false), 401);
    }
    @Test void productionChannelsUseDistinctLoopbackPorts() throws Exception {
        var config = new RpcConfiguration();
        var identity = config.identityChannel(); var merchant = config.merchantChannel();
        try {
            assertThat(identity.authority()).isEqualTo("127.0.0.1:9091");
            assertThat(merchant.authority()).isEqualTo("127.0.0.1:9092");
        } finally {
            identity.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            merchant.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    private void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CatalogFailure.class, e -> {
            assertThat(e.status()).isEqualTo(status); assertThat(e.getMessage()).doesNotContain("private diagnostic");
        });
    }
}
