package com.agora.catalog;

import com.agora.contracts.identity.v1.*;
import com.agora.contracts.merchant.v1.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

final class RpcFixture implements AutoCloseable {
    final AtomicReference<AccountType> accountType = new AtomicReference<>(AccountType.SELLER);
    static final String TOKEN = "catalog-test-internal-token-at-least-32";
    final Map<String, UUID> sessions = new ConcurrentHashMap<>();
    final Map<UUID, UUID> shops = new ConcurrentHashMap<>();
    final AtomicReference<Status> identityFailure = new AtomicReference<>(), merchantFailure = new AtomicReference<>();
    final AtomicReference<String> accountOverride = new AtomicReference<>(), shopOverride = new AtomicReference<>();
    final AtomicReference<ResolveSessionRequest> identityRequest = new AtomicReference<>();
    final AtomicReference<ResolveActiveShopRequest> merchantRequest = new AtomicReference<>();
    final AtomicInteger identityCalls = new AtomicInteger(), merchantCalls = new AtomicInteger();
    final AtomicLong identityDeadline = new AtomicLong(), merchantDeadline = new AtomicLong();
    final AtomicInteger publicCalls = new AtomicInteger();
    final java.util.List<Integer> publicBatchSizes = new java.util.concurrent.CopyOnWriteArrayList<>();
    final AtomicInteger publicFailAt = new AtomicInteger();
    final AtomicLong publicDeadline = new AtomicLong();
    final AtomicReference<FindPublicShopsResponse> publicResponse = new AtomicReference<>();
    volatile boolean hangIdentity, hangMerchant;
    final Server identityServer, merchantServer;
    final ManagedChannel identityChannel, merchantChannel;

    RpcFixture() throws Exception {
        String identityName = InProcessServerBuilder.generateName(), merchantName = InProcessServerBuilder.generateName();
        identityServer = InProcessServerBuilder.forName(identityName).directExecutor().intercept(authentication())
                .addService(new SessionIdentityGrpc.SessionIdentityImplBase() {
                    @Override public void resolveSession(ResolveSessionRequest request, StreamObserver<ResolveSessionResponse> out) {
                        identityCalls.incrementAndGet(); identityRequest.set(request);
                        identityDeadline.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                        if (hangIdentity) return;
                        if (identityFailure.get() != null) { out.onError(identityFailure.get().asRuntimeException()); return; }
                        UUID account = sessions.get(request.getSessionId());
                        if (account == null) { out.onError(Status.UNAUTHENTICATED.asRuntimeException()); return; }
                        if (request.getRequireCsrf() && !"masked-csrf-fixture".equals(request.getCsrfToken())) {
                            out.onError(Status.PERMISSION_DENIED.asRuntimeException()); return;
                        }
                        out.onNext(ResolveSessionResponse.newBuilder().setAccountId(accountOverride.get() == null
                                ? account.toString() : accountOverride.get()).addRoles("USER").setAccountType(accountType.get()).build());
                        out.onCompleted();
                    }
                }).build().start();
        merchantServer = InProcessServerBuilder.forName(merchantName).directExecutor().intercept(authentication())
                .addService(new MerchantAccessGrpc.MerchantAccessImplBase() {
                    @Override public void findPublicShops(FindPublicShopsRequest request, StreamObserver<FindPublicShopsResponse> out) {
                        int call = publicCalls.incrementAndGet(); publicBatchSizes.add(request.getShopIdsCount());
                        publicDeadline.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                        if (publicFailAt.get() == call) { out.onError(Status.UNAVAILABLE.asRuntimeException()); return; }
                        if (hangMerchant) return;
                        if (merchantFailure.get() != null) { out.onError(merchantFailure.get().asRuntimeException()); return; }
                        if (publicResponse.get() != null) { out.onNext(publicResponse.get()); out.onCompleted(); return; }
                        var result = FindPublicShopsResponse.newBuilder();
                        for (String id : request.getShopIdsList()) if (shops.containsValue(UUID.fromString(id)))
                            result.addShops(PublicShop.newBuilder().setId(id).setName("Cửa hàng công khai"));
                        out.onNext(result.build()); out.onCompleted();
                    }
                    @Override public void resolveActiveShop(ResolveActiveShopRequest request, StreamObserver<ResolveActiveShopResponse> out) {
                        merchantCalls.incrementAndGet(); merchantRequest.set(request);
                        merchantDeadline.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                        if (hangMerchant) return;
                        if (merchantFailure.get() != null) { out.onError(merchantFailure.get().asRuntimeException()); return; }
                        UUID shop = shops.get(UUID.fromString(request.getAccountId()));
                        if (shop == null) { out.onError(Status.PERMISSION_DENIED.asRuntimeException()); return; }
                        out.onNext(ResolveActiveShopResponse.newBuilder().setShopId(shopOverride.get() == null ? shop.toString() : shopOverride.get()).build());
                        out.onCompleted();
                    }
                }).build().start();
        identityChannel = InProcessChannelBuilder.forName(identityName).directExecutor().disableRetry().build();
        merchantChannel = InProcessChannelBuilder.forName(merchantName).directExecutor().disableRetry().build();
    }
    String seller() {
        String session = "opaque+session/" + UUID.randomUUID() + "==";
        UUID account = UUID.randomUUID(); sessions.put(session, account); shops.put(account, UUID.randomUUID());
        return session;
    }
    private ServerInterceptor authentication() {
        return new ServerInterceptor() {
            @Override public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                if (!TOKEN.equals(headers.get(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER)))) {
                    call.close(Status.UNAUTHENTICATED, new Metadata()); return new ServerCall.Listener<>() {};
                }
                return next.startCall(call, headers);
            }
        };
    }
    @Override public void close() throws Exception {
        identityChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        merchantChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        identityServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        merchantServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
}
