package com.agora.identity.configuration;

import com.agora.contracts.identity.v1.ResolveSessionRequest;
import com.agora.contracts.identity.v1.ResolveSessionResponse;
import com.agora.contracts.identity.v1.SessionIdentityGrpc;
import com.agora.identity.adapter.inbound.SessionCsrfVerifier;
import com.agora.identity.application.IdentityService;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

/** Local transport only; an off-machine deployment must add TLS/mTLS, not widen this bind. */
@Component
@ConditionalOnProperty(name = "agora.grpc.enabled", havingValue = "true")
public class IdentityGrpcServer implements SmartLifecycle {
    private static final Metadata.Key<String> INTERNAL_TOKEN =
            Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER);
    private final JdbcIndexedSessionRepository sessions;
    private final IdentityService identity;
    private final JdbcTemplate jdbc;
    private final int configuredPort;
    private final byte[] token;
    private volatile Server server;

    public IdentityGrpcServer(JdbcIndexedSessionRepository sessions, IdentityService identity, JdbcTemplate jdbc,
            @Value("${agora.grpc.port}") int port, @Value("${agora.grpc.token}") String token) {
        if (token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("INTERSERVICE_TOKEN must contain 32–256 URL-safe characters");
        }
        this.sessions = sessions; this.identity = identity; this.jdbc = jdbc; this.configuredPort = port;
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

    private final class Endpoint extends SessionIdentityGrpc.SessionIdentityImplBase {
        @Override public void resolveSession(ResolveSessionRequest request, StreamObserver<ResolveSessionResponse> observer) {
            try {
                if (!request.getSessionId().matches("[0-9a-fA-F-]{36}")) throw Status.UNAUTHENTICATED.asRuntimeException();
                Session session = sessions.findById(request.getSessionId());
                if (session == null || session.isExpired()) throw Status.UNAUTHENTICATED.asRuntimeException();
                SecurityContext context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                var authentication = context == null ? null : context.getAuthentication();
                if (authentication == null || !authentication.isAuthenticated()) throw Status.UNAUTHENTICATED.asRuntimeException();
                var account = identity.current(UUID.fromString(authentication.getName()));
                if (request.getRequireCsrf() && !SessionCsrfVerifier.matches(session.getAttribute("AGORA_CSRF"), request.getCsrfToken())) {
                    throw Status.PERMISSION_DENIED.asRuntimeException();
                }
                // Repository.save also writes SESSION_ID by primary key; a stale request could undo login rotation.
                Instant now = Instant.now();
                int touched = jdbc.update("""
                        UPDATE identity.spring_session
                        SET last_access_time = GREATEST(last_access_time, ?), expiry_time = GREATEST(expiry_time, ?)
                        WHERE session_id = ? AND expiry_time > ?
                        """, now.toEpochMilli(), now.plus(session.getMaxInactiveInterval()).toEpochMilli(),
                        request.getSessionId(), now.toEpochMilli());
                if (touched != 1) throw Status.UNAUTHENTICATED.asRuntimeException();
                observer.onNext(ResolveSessionResponse.newBuilder().setAccountId(account.id().toString())
                        .addAllRoles(account.roles()).build());
                observer.onCompleted();
            } catch (IdentityService.Unauthenticated exception) { observer.onError(Status.UNAUTHENTICATED.asRuntimeException()); }
            catch (StatusRuntimeException exception) { observer.onError(exception); }
            catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(IdentityGrpcServer.class).error("Session resolution failed type={}",
                        exception.getClass().getSimpleName());
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
            }
        }
    }
}
