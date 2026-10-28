package com.agora.payment.adapter.outbound;

import com.agora.payment.application.IdentityAccess;
import com.agora.payment.domain.PaymentFailure;
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
        if(session==null||session.isBlank())throw new PaymentFailure(401,"UNAUTHENTICATED","Session is required.");
        try{
            String account=stub.withDeadlineAfter(2,TimeUnit.SECONDS).resolveSession(ResolveSessionRequest.newBuilder().setSessionId(session).setCsrfToken(csrf==null?"":csrf).setRequireCsrf(requireCsrf).build()).getAccountId();
            UUID id=UUID.fromString(account);if(!id.toString().equalsIgnoreCase(account))throw new IllegalArgumentException();return id;
        }catch(StatusRuntimeException e){
            throw switch(e.getStatus().getCode()){
                case UNAUTHENTICATED->new PaymentFailure(401,"UNAUTHENTICATED","Session is invalid.");
                case PERMISSION_DENIED->new PaymentFailure(403,"FORBIDDEN","Request is not permitted.");
                default->unavailable();
            };
        }catch(IllegalArgumentException e){throw unavailable();}
    }
    private PaymentFailure unavailable(){return new PaymentFailure(503,"IDENTITY_UNAVAILABLE","Identity is unavailable.");}
}
