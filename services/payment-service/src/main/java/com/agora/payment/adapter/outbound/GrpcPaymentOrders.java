package com.agora.payment.adapter.outbound;
import com.agora.payment.application.PaymentOrders;
import com.agora.payment.domain.*;
import com.agora.payment.domain.Payment.*;
import com.agora.contracts.commerce.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
@Component
public class GrpcPaymentOrders implements PaymentOrders {
    private final CommercePaymentsGrpc.CommercePaymentsBlockingStub stub;
    public GrpcPaymentOrders(@Qualifier("commerceChannel") ManagedChannel channel,@Value("${agora.internal-token}") String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid interservice token.");
        var headers=new Metadata();headers.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);
        stub=CommercePaymentsGrpc.newBlockingStub(channel).withMaxInboundMessageSize(4096).withMaxOutboundMessageSize(4096).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    @Override public Payable payable(UUID buyer,UUID order){
        try {
            var r=stub.withDeadlineAfter(10,TimeUnit.SECONDS).getPayableOrder(PaymentOrderReference.newBuilder().setBuyerId(buyer.toString()).setOrderId(order.toString()).build());
            if(!r.getBuyerId().equals(buyer.toString())||!r.getOrderId().equals(order.toString())||!r.getCurrency().equals("VND")||r.getExpiresAtEpochMicros()<=0)throw Payment.unavailable();
            UUID checkout=uuid(r.getCheckoutId()),shop=uuid(r.getShopId());Payment.amount(r.getAmountVnd());
            return new Payable(order,checkout,shop,r.getAmountVnd(),Instant.ofEpochSecond(r.getExpiresAtEpochMicros()/1000000,(r.getExpiresAtEpochMicros()%1000000)*1000));
        }catch(StatusRuntimeException e){throw switch(e.getStatus().getCode()){
            case NOT_FOUND->Payment.missing();case FAILED_PRECONDITION->Payment.conflict();default->Payment.unavailable();};
        }catch(IllegalArgumentException e){throw Payment.unavailable();}
    }
    private UUID uuid(String text){UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw new IllegalArgumentException();return id;}
    @Override public Application apply(Capture capture){
        try {
            var r=stub.withDeadlineAfter(10,TimeUnit.SECONDS).applyCapture(ApplyCaptureRequest.newBuilder().setCaptureId(capture.id().toString()).setPaymentAttemptId(capture.attemptId().toString()).setBuyerId(capture.actorId().toString()).setOrderId(capture.orderId().toString()).setProviderTransactionNo(capture.providerTransactionNo()).setAmountVnd(capture.amountVnd()).setCurrency("VND").build());
            if(!r.getCaptureId().equals(capture.id().toString())||!r.getBuyerId().equals(capture.actorId().toString())||!r.getOrderId().equals(capture.orderId().toString()))return new Application("PROCESSING",null,true);
            String reason=null,state;
            switch(r.getState()){
                case CAPTURE_APPLIED -> state="APPLIED";
                case CAPTURE_PROCESSING -> state="PROCESSING";
                case CAPTURE_REFUND_REQUIRED -> {state="REFUND_REQUIRED";if(!java.util.Set.of(CaptureApplicationReason.ORDER_EXPIRED,CaptureApplicationReason.DUPLICATE_CAPTURE,CaptureApplicationReason.AMOUNT_MISMATCH,CaptureApplicationReason.RESERVATION_RELEASED).contains(r.getReason()))return new Application("PROCESSING",null,true);reason=r.getReason().name();}
                default -> {return new Application("PROCESSING",null,true);}
            }
            if(reason==null&&r.getReason()!=CaptureApplicationReason.CAPTURE_APPLICATION_REASON_UNSPECIFIED)return new Application("PROCESSING",null,true);
            return new Application(state,reason,r.getNeedsReconciliation());
        }catch(StatusRuntimeException e){throw Payment.unavailable();}
    }

}
