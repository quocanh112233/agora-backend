package com.agora.commerce.adapter.outbound;

import com.agora.commerce.application.IdentityAccess;
import com.agora.commerce.domain.CartFailure;
import com.agora.contracts.identity.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class GrpcIdentityAccess implements IdentityAccess {
    private final SessionIdentityGrpc.SessionIdentityBlockingStub stub;
    public GrpcIdentityAccess(@Qualifier("identityChannel") ManagedChannel channel,@Value("${agora.internal-token}") String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid interservice token.");
        var headers=new Metadata();headers.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);
        stub=SessionIdentityGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    @Override public UUID resolve(String session,String csrf,boolean requireCsrf){
        if(session==null||session.isBlank())throw new CartFailure(401,"UNAUTHENTICATED","Session is required.");
        try{
            var response=stub.withDeadlineAfter(2,TimeUnit.SECONDS).resolveSession(ResolveSessionRequest.newBuilder().setSessionId(session).setCsrfToken(csrf==null?"":csrf).setRequireCsrf(requireCsrf).build());
            if(response.getAccountType()==AccountType.ACCOUNT_TYPE_UNSPECIFIED || response.getAccountType()==AccountType.UNRECOGNIZED) throw unavailable();
            if(response.getAccountType()!=AccountType.BUYER) throw new CartFailure(403,"FORBIDDEN","A BUYER account is required.");
            String account=response.getAccountId();
            UUID id=UUID.fromString(account);if(!id.toString().equalsIgnoreCase(account))throw new IllegalArgumentException();return id;
        }catch(StatusRuntimeException e){
            throw switch(e.getStatus().getCode()){
                case UNAUTHENTICATED->new CartFailure(401,"UNAUTHENTICATED","Session is invalid.");
                case PERMISSION_DENIED->new CartFailure(403,"FORBIDDEN","Request is not permitted.");
                default->unavailable();
            };
        }catch(IllegalArgumentException e){throw unavailable();}
    }
    private CartFailure unavailable(){return new CartFailure(503,"IDENTITY_UNAVAILABLE","Identity is unavailable.");}
}
