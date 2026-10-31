package com.agora.commerce.adapter.inbound;

import com.agora.commerce.application.PaymentApplicationService;
import com.agora.commerce.domain.*;
import com.agora.commerce.domain.CaptureApplication.*;
import com.agora.contracts.commerce.v1.*;
import com.google.protobuf.MessageLite;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Component
public class PaymentRpc extends CommercePaymentsGrpc.CommercePaymentsImplBase {
    private final PaymentApplicationService service;
    public PaymentRpc(PaymentApplicationService service){this.service=service;}
    @Override public void getReviewEligibility(ReviewLineReference input,StreamObserver<ReviewEligibility> out){
        invoke(input,out,()->{
            var line=service.reviewLine(uuid(input.getBuyerId()),uuid(input.getOrderId()),uuid(input.getSkuId()));
            return ReviewEligibility.newBuilder().setBuyerId(line.buyerId().toString()).setOrderId(line.orderId().toString()).setSkuId(line.skuId().toString()).setProductId(line.productId().toString()).setShopId(line.shopId().toString()).build();
        });
    }
    @Override public void getPayableOrder(PaymentOrderReference input,StreamObserver<PayableOrderResponse> out){
        invoke(input,out,()->{
            var owned=service.payable(uuid(input.getBuyerId()),uuid(input.getOrderId()));var o=owned.order();
            return PayableOrderResponse.newBuilder().setBuyerId(owned.buyerId().toString()).setOrderId(o.id().toString()).setCheckoutId(owned.checkoutId().toString()).setShopId(o.shopId().toString()).setCurrency(o.currency()).setAmountVnd(o.totalVnd()).setExpiresAtEpochMicros(Checkout.micros(o.expiresAt())).build();
        });
    }
    @Override public void applyCapture(ApplyCaptureRequest input,StreamObserver<CaptureApplicationResponse> out){
        invoke(input,out,()->response(service.apply(new Request(uuid(input.getCaptureId()),uuid(input.getPaymentAttemptId()),uuid(input.getBuyerId()),uuid(input.getOrderId()),input.getProviderTransactionNo(),input.getAmountVnd(),input.getCurrency()))));
    }
    @Override public void getCaptureApplication(CaptureApplicationReference input,StreamObserver<CaptureApplicationResponse> out){
        invoke(input,out,()->response(service.get(uuid(input.getBuyerId()),uuid(input.getOrderId()),uuid(input.getCaptureId()))));
    }
    private CaptureApplicationResponse response(Receipt r){
        var result=CaptureApplicationResponse.newBuilder().setCaptureId(r.captureId().toString()).setBuyerId(r.buyerId().toString()).setOrderId(r.orderId().toString()).setState(CaptureApplicationState.valueOf("CAPTURE_"+r.state())).setNeedsReconciliation(r.needsReconciliation());
        if(r.reason()!=null)result.setReason(CaptureApplicationReason.valueOf(r.reason()));return result.build();
    }
    private UUID uuid(String value){try{var id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}catch(IllegalArgumentException e){throw new CartFailure(422,"INVALID_CAPTURE","Provide a valid identity.");}}
    private <T extends MessageLite> void invoke(MessageLite input,StreamObserver<T> out,Supplier<T> action){
        try{
            if(input.getSerializedSize()>4096){out.onError(Status.RESOURCE_EXHAUSTED.asRuntimeException());return;}
            var response=action.get();if(response.getSerializedSize()>4096)throw new IllegalStateException("Payment response exceeds contract.");out.onNext(response);out.onCompleted();
        }catch(CartFailure e){out.onError((switch(e.status()){case 400,422->Status.INVALID_ARGUMENT;case 404->Status.NOT_FOUND;case 409->Status.FAILED_PRECONDITION;case 503->Status.UNAVAILABLE;default->Status.INTERNAL;}).asRuntimeException());}
        catch(DataAccessException|TransactionException e){out.onError(Status.UNAVAILABLE.asRuntimeException());}
        catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(PaymentRpc.class).error("Payment RPC failed type={}",e.getClass().getSimpleName());out.onError(Status.INTERNAL.asRuntimeException());}
    }
}
