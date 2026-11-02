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
    @Override public java.util.Set<UUID> activeSellers(java.util.List<UUID> ids) {
        if (ids.isEmpty()) return java.util.Set.of();
        if (ids.size() > 5 || java.util.Set.copyOf(ids).size() != ids.size()) throw new IllegalArgumentException();
        try {
            var response = stub.withDeadlineAfter(2, TimeUnit.SECONDS).getAccountStates(
                    com.agora.contracts.identity.v1.GetAccountStatesRequest.newBuilder()
                    .addAllAccountIds(ids.stream().map(UUID::toString).toList()).build());
            var seen = new java.util.HashSet<UUID>();
            var active = new java.util.HashSet<UUID>();
            for (var account : response.getAccountsList()) {
                UUID id = UUID.fromString(account.getAccountId());
                if (!id.toString().equalsIgnoreCase(account.getAccountId()) || !ids.contains(id) || !seen.add(id)
                        || account.getAccountType() == com.agora.contracts.identity.v1.AccountType.ACCOUNT_TYPE_UNSPECIFIED
                        || account.getAccountType() == com.agora.contracts.identity.v1.AccountType.UNRECOGNIZED)
                    throw new IllegalArgumentException();
                if (account.getIsActive() && account.getAccountType() == com.agora.contracts.identity.v1.AccountType.SELLER) active.add(id);
            }
            return java.util.Set.copyOf(active);
        } catch (StatusRuntimeException | IllegalArgumentException e) {
            throw new MerchantFailure(503, "IDENTITY_UNAVAILABLE", "Owner eligibility is unavailable.");
        }
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
            if (response.getAccountType() == com.agora.contracts.identity.v1.AccountType.ACCOUNT_TYPE_UNSPECIFIED
                    || response.getAccountType() == com.agora.contracts.identity.v1.AccountType.UNRECOGNIZED)
                throw new IllegalArgumentException();
            return new Principal(id, java.util.Set.copyOf(response.getRolesList()), response.getAccountType().name(), response.getDisplayName());
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
