package com.agora.merchant;

import com.agora.contracts.identity.v1.*;
import com.agora.merchant.adapter.outbound.GrpcIdentity;
import com.agora.merchant.configuration.IdentityConfiguration;
import com.agora.merchant.domain.MerchantFailure;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class GrpcIdentityTests {
    static final String TOKEN = "test-internal-token-at-least-32-characters";
    ManagedChannel channel;
    Server server;
    AtomicReference<ResolveSessionRequest> received = new AtomicReference<>();
    AtomicReference<String> credential = new AtomicReference<>();
    AtomicReference<Status> failure = new AtomicReference<>();
    AtomicReference<String> account = new AtomicReference<>(UUID.randomUUID().toString());
    AtomicReference<Set<String>> roles = new AtomicReference<>(Set.of("USER"));
    AtomicReference<GetAccountStatesResponse> batch = new AtomicReference<>(GetAccountStatesResponse.getDefaultInstance());
    AtomicReference<AccountType> accountType = new AtomicReference<>(AccountType.SELLER);
    AtomicLong deadlineMillis = new AtomicLong();
    AtomicInteger calls = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        var service = new SessionIdentityGrpc.SessionIdentityImplBase() {
            @Override public void getAccountStates(GetAccountStatesRequest request, StreamObserver<GetAccountStatesResponse> out) {
                if (failure.get() != null) { out.onError(failure.get().asRuntimeException()); return; }
                out.onNext(batch.get()); out.onCompleted();
            }
            @Override public void resolveSession(ResolveSessionRequest request, StreamObserver<ResolveSessionResponse> out) {
                calls.incrementAndGet(); received.set(request);
                deadlineMillis.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                if (failure.get() != null) { out.onError(failure.get().asRuntimeException()); return; }
                if ("hang".equals(request.getSessionId())) return;
                out.onNext(ResolveSessionResponse.newBuilder().setAccountId(account.get()).addAllRoles(roles.get()).setAccountType(accountType.get()).build());
                out.onCompleted();
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor().intercept(new ServerInterceptor() {
            @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                credential.set(headers.get(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER)));
                return next.startCall(call, headers);
            }
        }).addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().disableRetry().build();
    }
    @AfterEach void stop() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    @Test void rejectsUnknownSessionTypeAndFiltersBatchToActiveSellers() {
        var client = new GrpcIdentity(channel, TOKEN);
        accountType.set(AccountType.ACCOUNT_TYPE_UNSPECIFIED);
        assertThatThrownBy(() -> client.resolve("session", null, false)).isInstanceOfSatisfying(MerchantFailure.class,
                e -> assertThat(e.status()).isEqualTo(503));
        UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID(), suspended = UUID.randomUUID();
        var state = AccountState.newBuilder().setAccountId(seller.toString()).setAccountType(AccountType.SELLER).setIsActive(true).build();
        batch.set(GetAccountStatesResponse.newBuilder().addAccounts(state)
                .addAccounts(state.toBuilder().setAccountId(buyer.toString()).setAccountType(AccountType.BUYER))
                .addAccounts(state.toBuilder().setAccountId(suspended.toString()).setIsActive(false)).build());
        assertThat(client.activeSellers(java.util.List.of(seller, buyer, suspended))).containsExactly(seller);
        for (var invalid : java.util.List.of(
                GetAccountStatesResponse.newBuilder().addAccounts(state).addAccounts(state).build(),
                GetAccountStatesResponse.newBuilder().addAccounts(state.toBuilder().setAccountId(UUID.randomUUID().toString())).build(),
                GetAccountStatesResponse.newBuilder().addAccounts(state.toBuilder().setAccountTypeValue(999)).build())) {
            batch.set(invalid);
            assertThatThrownBy(() -> client.activeSellers(java.util.List.of(seller)))
                    .isInstanceOfSatisfying(MerchantFailure.class, e -> assertThat(e.status()).isEqualTo(503));
        }
        assertThat(client.activeSellers(java.util.List.of())).isEmpty();
        failure.set(Status.UNAVAILABLE);
        assertThatThrownBy(() -> client.activeSellers(java.util.List.of(seller)))
                .isInstanceOfSatisfying(MerchantFailure.class, e -> assertThat(e.status()).isEqualTo(503));
    }
    @Test void forwardsRawCookieMaskedCsrfInternalCredentialAndTwoSecondDeadline() {
        var client = new GrpcIdentity(channel, TOKEN);
        assertThat(client.resolve("raw+session/value==", "masked+csrf==", true).accountId().toString()).isEqualTo(account.get());
        assertThat(received.get().getSessionId()).isEqualTo("raw+session/value==");
        assertThat(received.get().getCsrfToken()).isEqualTo("masked+csrf==");
        assertThat(received.get().getRequireCsrf()).isTrue();
        assertThat(credential.get()).isEqualTo(TOKEN);
        assertThat(deadlineMillis.get()).isBetween(1L, 2000L);
        client.resolve("raw", null, false);
        assertThat(received.get().getRequireCsrf()).isFalse();
        assertThat(received.get().getCsrfToken()).isEmpty();
    }
    @Test void mapsAllGrpcErrorsFailClosedWithoutAuthRetry() {
        var client = new GrpcIdentity(channel, TOKEN);
        for (var entry : java.util.Map.of(Status.Code.UNAUTHENTICATED, 401, Status.Code.PERMISSION_DENIED, 403,
                Status.Code.UNAVAILABLE, 503, Status.Code.DEADLINE_EXCEEDED, 503, Status.Code.INTERNAL, 503).entrySet()) {
            int before = calls.get(); failure.set(Status.fromCode(entry.getKey()).withDescription("private diagnostic"));
            assertThatThrownBy(() -> client.resolve("session", "csrf", true))
                    .isInstanceOfSatisfying(MerchantFailure.class, e -> {
                        assertThat(e.status()).isEqualTo(entry.getValue());
                        assertThat(e.getMessage()).doesNotContain("private diagnostic");
                    });
            assertThat(calls.get()).isEqualTo(before + 1);
        }
    }
    @Test void returnsImmutableRolesAndRefreshesPrincipalOnEveryCall() {
        var client = new GrpcIdentity(channel, TOKEN);
        roles.set(Set.of("USER", "ADMIN"));
        var admin = client.resolve("session", null, false);
        assertThat(admin.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
        assertThatThrownBy(() -> admin.roles().add("OTHER")).isInstanceOf(UnsupportedOperationException.class);
        roles.set(Set.of("USER"));
        assertThat(client.resolve("session", null, false).roles()).containsExactly("USER");
        assertThat(admin.roles()).contains("ADMIN");
        var source = new java.util.HashSet<>(Set.of("USER"));
        var principal = new com.agora.merchant.application.IdentityPort.Principal(UUID.randomUUID(), source, "SELLER", "Fixture operator");
        source.add("ADMIN");
        assertThat(principal.roles()).containsExactly("USER");
    }
    @Test void deadlineAndStoppedDependencyReturn503() {
        var client = new GrpcIdentity(channel, TOKEN);
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.resolve("hang", null, false)).isInstanceOfSatisfying(MerchantFailure.class,
                e -> assertThat(e.status()).isEqualTo(503));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(1500L, 4000L);
        server.shutdownNow();
        assertThatThrownBy(() -> client.resolve("session", null, false)).isInstanceOfSatisfying(MerchantFailure.class,
                e -> assertThat(e.status()).isEqualTo(503));
    }
    @Test void rejectsMissingSessionMalformedIdentityAndShortCredential() {
        assertThatThrownBy(() -> new GrpcIdentity(channel, "short")).isInstanceOf(IllegalArgumentException.class);
        var client = new GrpcIdentity(channel, TOKEN);
        assertThatThrownBy(() -> client.resolve(null, null, false)).isInstanceOfSatisfying(MerchantFailure.class,
                e -> assertThat(e.status()).isEqualTo(401));
        assertThat(calls.get()).isZero();
        account.set("not-a-uuid");
        assertThatThrownBy(() -> client.resolve("session", null, false)).isInstanceOfSatisfying(MerchantFailure.class,
                e -> assertThat(e.status()).isEqualTo(503));
    }
    @Test void internalCredentialMatchesIdentityUrlSafeLengthConstraint() {
        for (String invalid : new String[]{"a".repeat(31), "a".repeat(257), "a".repeat(32) + "+",
                "a".repeat(32) + "/", "a".repeat(32) + "=", "a".repeat(32) + " ", "a".repeat(32) + "é"})
            assertThatThrownBy(() -> new GrpcIdentity(channel, invalid)).as("Invalid credential shape")
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(new GrpcIdentity(channel, "a".repeat(32))).isNotNull();
        assertThat(new GrpcIdentity(channel, "A_0-".repeat(64))).isNotNull();
    }
    @Test void productionChannelOnlyTargetsLoopback9091() throws Exception {
        ManagedChannel production = new IdentityConfiguration().identityChannel();
        try { assertThat(production.authority()).isEqualTo("127.0.0.1:9091"); }
        finally { production.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }
}
