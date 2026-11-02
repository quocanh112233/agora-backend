package com.agora.feedback.adapter.outbound;

import com.agora.feedback.application.FeedbackAccess;
import com.agora.feedback.domain.*;
import com.agora.feedback.domain.Feedback.*;
import com.agora.contracts.identity.v1.*;
import com.agora.contracts.commerce.v1.*;
import com.agora.contracts.merchant.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
@Component
public class GrpcFeedbackAccess implements FeedbackAccess {
    private final SessionIdentityGrpc.SessionIdentityBlockingStub identity;
    private final CommercePaymentsGrpc.CommercePaymentsBlockingStub commerce;
    private final MerchantAccessGrpc.MerchantAccessBlockingStub merchant;
    public GrpcFeedbackAccess(@Qualifier("identityChannel") ManagedChannel identity,@Qualifier("commerceChannel") ManagedChannel commerce,@Qualifier("merchantChannel") ManagedChannel merchant,@Value("${agora.internal-token}") String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid interservice token.");
        var headers=new Metadata();headers.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);var auth=MetadataUtils.newAttachHeadersInterceptor(headers);
        this.identity=SessionIdentityGrpc.newBlockingStub(identity).withInterceptors(auth);this.commerce=CommercePaymentsGrpc.newBlockingStub(commerce).withInterceptors(auth);this.merchant=MerchantAccessGrpc.newBlockingStub(merchant).withInterceptors(auth);
    }
    @Override public Actor resolve(String session,String csrf,boolean unsafe){
        if(session==null||session.isBlank())throw new FeedbackFailure(401,"UNAUTHENTICATED","Session is required.");
        if(session.length()>256||csrf!=null&&csrf.length()>256)throw new FeedbackFailure(401,"UNAUTHENTICATED","Invalid session.");
        try{var result=identity.withDeadlineAfter(2,TimeUnit.SECONDS).resolveSession(ResolveSessionRequest.newBuilder().setSessionId(session).setCsrfToken(csrf==null?"":csrf).setRequireCsrf(unsafe).build());if(result.getAccountType()==AccountType.ACCOUNT_TYPE_UNSPECIFIED||result.getAccountType()==AccountType.UNRECOGNIZED)throw Feedback.unavailable("IDENTITY");return new Actor(uuid(result.getAccountId()),result.getRolesList().contains("ADMIN"),result.getAccountType().name());}
        catch(StatusRuntimeException e){throw switch(e.getStatus().getCode()){case UNAUTHENTICATED->new FeedbackFailure(401,"UNAUTHENTICATED","Session is invalid.");case PERMISSION_DENIED->new FeedbackFailure(403,"FORBIDDEN","Request is not permitted.");default->Feedback.unavailable("IDENTITY");};}
        catch(IllegalArgumentException e){throw Feedback.unavailable("IDENTITY");}
    }
    @Override public Line eligible(UUID buyer,UUID order,UUID sku){
        try{var r=commerce.withDeadlineAfter(2,TimeUnit.SECONDS).getReviewEligibility(ReviewLineReference.newBuilder().setBuyerId(buyer.toString()).setOrderId(order.toString()).setSkuId(sku.toString()).build());
            if(!r.getBuyerId().equals(buyer.toString())||!r.getOrderId().equals(order.toString())||!r.getSkuId().equals(sku.toString()))throw new IllegalArgumentException();
            return new Line(buyer,order,sku,uuid(r.getProductId()),uuid(r.getShopId()));
        }catch(StatusRuntimeException e){throw switch(e.getStatus().getCode()){case NOT_FOUND->Feedback.missing();case FAILED_PRECONDITION->new FeedbackFailure(409,"REVIEW_NOT_ELIGIBLE","Only paid order lines can be reviewed.");default->Feedback.unavailable("COMMERCE");};}
        catch(IllegalArgumentException e){throw Feedback.unavailable("COMMERCE");}
    }
    @Override public UUID shop(UUID actor){try{return uuid(merchant.withDeadlineAfter(2,TimeUnit.SECONDS).resolveActiveShop(ResolveActiveShopRequest.newBuilder().setAccountId(actor.toString()).build()).getShopId());}
        catch(StatusRuntimeException e){if(e.getStatus().getCode()==Status.Code.PERMISSION_DENIED)throw new FeedbackFailure(403,"FORBIDDEN","An active owned shop is required.");if(e.getStatus().getCode()==Status.Code.NOT_FOUND||e.getStatus().getCode()==Status.Code.FAILED_PRECONDITION)throw Feedback.missing();throw Feedback.unavailable("MERCHANT");}
        catch(IllegalArgumentException e){throw Feedback.unavailable("MERCHANT");}}
    private UUID uuid(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}
}
