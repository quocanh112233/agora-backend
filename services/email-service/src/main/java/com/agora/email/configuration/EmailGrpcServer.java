package com.agora.email.configuration;

import com.agora.contracts.email.v1.*;
import com.agora.email.application.LinkMailer;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Local transport only; an off-machine deployment must add TLS/mTLS, not widen this bind. */
@Component
@ConditionalOnProperty(name = "agora.grpc.enabled", havingValue = "true")
public class EmailGrpcServer implements SmartLifecycle {
    private static final Metadata.Key<String> INTERNAL_TOKEN =
            Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER);
    private final LinkMailer mailer;
    private final int configuredPort;
    private final byte[] token;
    private volatile Server server;

    public EmailGrpcServer(LinkMailer mailer,
            @Value("${agora.grpc.port}") int port, @Value("${agora.grpc.token}") String token) {
        if (token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("INTERSERVICE_TOKEN must contain 32–256 URL-safe characters");
        }
        this.mailer = mailer; this.configuredPort = port;
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
        } catch (IOException exception) { throw new IllegalStateException("Cannot start Identity gRPC listener", exception); }
    }

    public int port() { return server.getPort(); }
    @Override public boolean isRunning() { return server != null && !server.isShutdown(); }

    @Override public void stop() {
        Server running = server;
        if (running == null) return;
        running.shutdown();
        try {
            if (!running.awaitTermination(5, TimeUnit.SECONDS)) running.shutdownNow();
        } catch (InterruptedException exception) { running.shutdownNow(); Thread.currentThread().interrupt(); }
    }

    private final class Endpoint extends EmailDeliveryGrpc.EmailDeliveryImplBase {
        @Override public void sendLink(SendLinkRequest request, StreamObserver<SendLinkResponse> observer) {
            try { mailer.send(request); observer.onNext(SendLinkResponse.newBuilder().setAccepted(true).build()); observer.onCompleted(); }
            catch (IllegalArgumentException e) { observer.onError(Status.INVALID_ARGUMENT.asRuntimeException()); }
            catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("SMTP delivery deferred type={}", e.getClass().getSimpleName()); observer.onError(Status.UNAVAILABLE.asRuntimeException()); }
        }
    }
}
