package com.agora.merchant.configuration;

import com.agora.contracts.merchant.v1.MerchantAccessGrpc;
import com.agora.contracts.merchant.v1.ResolveActiveShopRequest;
import com.agora.contracts.merchant.v1.ResolveActiveShopResponse;
import com.agora.merchant.application.ShopService;
import com.agora.merchant.domain.MerchantFailure;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Local transport only; an off-machine deployment must add TLS/mTLS, not widen this bind. */
@Component
@ConditionalOnProperty(name = "agora.grpc.enabled", havingValue = "true")
public class MerchantGrpcServer implements SmartLifecycle {
    private static final Metadata.Key<String> INTERNAL_TOKEN =
            Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER);
    private final ShopService shops;
    private final int configuredPort;
    private final byte[] token;
    private volatile Server server;

    public MerchantGrpcServer(ShopService shops, @Value("${agora.grpc.port}") int port,
            @Value("${agora.grpc.token}") String token) {
        if (token == null || token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("INTERSERVICE_TOKEN must contain 32–256 URL-safe characters.");
        this.shops = shops; this.configuredPort = port;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override public synchronized void start() {
        if (isRunning()) return;
        try {
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", configuredPort))
                    .maxInboundMessageSize(4096).maxConcurrentCallsPerConnection(32)
                    .intercept(new ServerInterceptor() {
                        @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                            String supplied = headers.get(INTERNAL_TOKEN);
                            if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
                                call.close(Status.PERMISSION_DENIED, new Metadata());
                                return new ServerCall.Listener<>() { };
                            }
                            return next.startCall(call, headers);
                        }
                    }).addService(new Endpoint()).build().start();
        } catch (IOException exception) { throw new IllegalStateException("Cannot start Merchant gRPC listener", exception); }
    }

    public int port() { return server.getPort(); }
    @Override public boolean isRunning() { return server != null && !server.isShutdown(); }
    @Override public synchronized void stop() {
        Server running = server;
        if (running == null) return;
        running.shutdown();
        try {
            if (!running.awaitTermination(5, TimeUnit.SECONDS)) running.shutdownNow();
        } catch (InterruptedException exception) { running.shutdownNow(); Thread.currentThread().interrupt(); }
    }

    private final class Endpoint extends MerchantAccessGrpc.MerchantAccessImplBase {
        @Override public void resolveActiveShop(ResolveActiveShopRequest request, StreamObserver<ResolveActiveShopResponse> observer) {
            UUID account;
            try {
                account = UUID.fromString(request.getAccountId());
                if (!account.toString().equalsIgnoreCase(request.getAccountId())) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
                return;
            }
            try {
                var shop = shops.mine(account);
                if (!"ACTIVE".equals(shop.status())) {
                    observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
                    return;
                }
                observer.onNext(ResolveActiveShopResponse.newBuilder().setShopId(shop.id().toString()).build());
                observer.onCompleted();
            } catch (RuntimeException exception) {
                if (exception instanceof MerchantFailure failure && failure.status() == 404) {
                    observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
                } else {
                    org.slf4j.LoggerFactory.getLogger(MerchantGrpcServer.class).error("Shop resolution failed type={}",
                            exception.getClass().getSimpleName());
                    observer.onError(Status.UNAVAILABLE.asRuntimeException());
                }
            }
        }
    }
}
