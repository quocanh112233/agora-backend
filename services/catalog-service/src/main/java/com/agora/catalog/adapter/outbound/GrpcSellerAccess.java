package com.agora.catalog.adapter.outbound;

import com.agora.catalog.application.SellerAccess;
import com.agora.catalog.domain.CatalogFailure;
import com.agora.contracts.identity.v1.ResolveSessionRequest;
import com.agora.contracts.identity.v1.SessionIdentityGrpc;
import com.agora.contracts.merchant.v1.MerchantAccessGrpc;
import com.agora.contracts.merchant.v1.ResolveActiveShopRequest;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GrpcSellerAccess implements SellerAccess {
    private final SessionIdentityGrpc.SessionIdentityBlockingStub identity;
    private final MerchantAccessGrpc.MerchantAccessBlockingStub merchant;
    public GrpcSellerAccess(@Qualifier("identityChannel") ManagedChannel identityChannel,
                            @Qualifier("merchantChannel") ManagedChannel merchantChannel,
                            @Value("${agora.internal-token}") String token) {
        if (token == null || token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("INTERSERVICE_TOKEN must contain 32–256 URL-safe characters.");
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER), token);
        var credential = MetadataUtils.newAttachHeadersInterceptor(headers);
        identity = SessionIdentityGrpc.newBlockingStub(identityChannel).withInterceptors(credential);
        merchant = MerchantAccessGrpc.newBlockingStub(merchantChannel).withInterceptors(credential);
    }
    @Override public Seller resolve(String sessionId, String csrfToken, boolean requireCsrf) {
        if (sessionId == null || sessionId.isBlank())
            throw new CatalogFailure(401, "UNAUTHENTICATED", "Session is required.");
        UUID account;
        try {
            var response = identity.withDeadlineAfter(2, TimeUnit.SECONDS).resolveSession(ResolveSessionRequest.newBuilder()
                    .setSessionId(sessionId).setCsrfToken(csrfToken == null ? "" : csrfToken).setRequireCsrf(requireCsrf).build());
            if (response.getAccountType() == com.agora.contracts.identity.v1.AccountType.ACCOUNT_TYPE_UNSPECIFIED
                    || response.getAccountType() == com.agora.contracts.identity.v1.AccountType.UNRECOGNIZED) throw unavailable("IDENTITY");
            if (response.getAccountType() != com.agora.contracts.identity.v1.AccountType.SELLER)
                throw new CatalogFailure(403, "FORBIDDEN", "A SELLER account is required.");
            account = uuid(response.getAccountId());
        } catch (StatusRuntimeException e) {
            throw switch (e.getStatus().getCode()) {
                case UNAUTHENTICATED -> new CatalogFailure(401, "UNAUTHENTICATED", "Session is invalid.");
                case PERMISSION_DENIED -> new CatalogFailure(403, "FORBIDDEN", "Request is not permitted.");
                default -> unavailable("IDENTITY");
            };
        } catch (IllegalArgumentException e) { throw unavailable("IDENTITY"); }
        try {
            var response = merchant.withDeadlineAfter(2, TimeUnit.SECONDS).resolveActiveShop(
                    ResolveActiveShopRequest.newBuilder().setAccountId(account.toString()).build());
            return new Seller(account, uuid(response.getShopId()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.PERMISSION_DENIED)
                throw new CatalogFailure(403, "FORBIDDEN", "An ACTIVE owned shop is required.");
            throw unavailable("MERCHANT");
        } catch (IllegalArgumentException e) { throw unavailable("MERCHANT"); }
    }
    private UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return id;
    }
    private CatalogFailure unavailable(String service) {
        return new CatalogFailure(503, service + "_UNAVAILABLE", "Seller authorization is unavailable.");
    }
}
