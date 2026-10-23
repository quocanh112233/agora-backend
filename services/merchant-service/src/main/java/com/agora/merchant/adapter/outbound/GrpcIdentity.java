package com.agora.merchant.adapter.outbound;

import com.agora.contracts.identity.v1.ResolveSessionRequest;
import com.agora.contracts.identity.v1.SessionIdentityGrpc;
import com.agora.merchant.application.IdentityPort;
import com.agora.merchant.domain.MerchantFailure;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GrpcIdentity implements IdentityPort {
    private final SessionIdentityGrpc.SessionIdentityBlockingStub stub;
    public GrpcIdentity(ManagedChannel channel, @Value("${agora.identity.token}") String token) {
        if (token == null || token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("INTERSERVICE_TOKEN must contain 32–256 URL-safe characters.");
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER), token);
        stub = SessionIdentityGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
    @Override public Principal resolve(String sessionId, String csrfToken, boolean requireCsrf) {
        if (sessionId == null || sessionId.isBlank())
            throw new MerchantFailure(401, "UNAUTHENTICATED", "Session is required.");
        try {
            var response = stub.withDeadlineAfter(2, TimeUnit.SECONDS).resolveSession(ResolveSessionRequest.newBuilder()
                    .setSessionId(sessionId).setCsrfToken(csrfToken == null ? "" : csrfToken)
                    .setRequireCsrf(requireCsrf).build());
            String account = response.getAccountId();
            UUID id = UUID.fromString(account);
            if (!id.toString().equalsIgnoreCase(account)) throw new IllegalArgumentException();
            return new Principal(id, java.util.Set.copyOf(response.getRolesList()));
        } catch (StatusRuntimeException e) {
            throw switch (e.getStatus().getCode()) {
                case UNAUTHENTICATED -> new MerchantFailure(401, "UNAUTHENTICATED", "Session is invalid.");
                case PERMISSION_DENIED -> new MerchantFailure(403, "FORBIDDEN", "Request is not permitted.");
                default -> new MerchantFailure(503, "IDENTITY_UNAVAILABLE", "Identity is unavailable.");
            };
        } catch (IllegalArgumentException e) {
            throw new MerchantFailure(503, "IDENTITY_UNAVAILABLE", "Identity is unavailable.");
        }
    }
}
