package com.agora.commerce.adapter.outbound;

import com.agora.commerce.application.ReservationSettlement;
import com.agora.commerce.domain.*;
import com.agora.commerce.domain.CaptureApplication.OwnedOrder;
import com.agora.contracts.catalog.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class GrpcReservationSettlement implements ReservationSettlement {
    private final CatalogReservationsGrpc.CatalogReservationsBlockingStub stub;
    public GrpcReservationSettlement(@Qualifier("catalogChannel") ManagedChannel channel,@Value("${agora.internal-token}") String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid interservice token.");
        var headers=new Metadata();headers.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);
        stub=CatalogReservationsGrpc.newBlockingStub(channel).withMaxInboundMessageSize(131072).withMaxOutboundMessageSize(4096).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    @Override public Result get(OwnedOrder order){
        try{return validate(order,stub.withDeadlineAfter(10,TimeUnit.SECONDS).get(ReservationReference.newBuilder().setBuyerId(order.buyerId().toString()).setCheckoutId(order.checkoutId().toString()).build()));}
        catch(StatusRuntimeException e){if(e.getStatus().getCode()==Status.Code.ALREADY_EXISTS)throw new CartFailure(503,"CAPTURE_CONFLICT","Capture requires reconciliation.");throw Checkout.unavailable();}
    }
    @Override public Result consume(OwnedOrder order,UUID capture){
        try{return validate(order,stub.withDeadlineAfter(10,TimeUnit.SECONDS).consume(ConsumeReservationRequest.newBuilder().setBuyerId(order.buyerId().toString()).setCheckoutId(order.checkoutId().toString()).setCaptureId(capture.toString()).build()));}
        catch(StatusRuntimeException e){if((e.getStatus().getCode()==Status.Code.ALREADY_EXISTS||e.getStatus().getCode()==Status.Code.FAILED_PRECONDITION))throw new CartFailure(503,"CAPTURE_CONFLICT","Capture requires reconciliation.");throw Checkout.unavailable();}
    }
    private Result validate(OwnedOrder owned,ReservationResponse r){
        var order=owned.order();
        if(r.getSerializedSize()>131072||!r.getBuyerId().equals(owned.buyerId().toString())||!r.getCheckoutId().equals(owned.checkoutId().toString())||!r.getShopId().equals(order.shopId().toString())||r.getCreatedAtEpochMicros()<=0||r.getCreatedAtEpochMicros()>Long.MAX_VALUE-900000000L||r.getExpiresAtEpochMicros()-r.getCreatedAtEpochMicros()!=900000000L||r.getItemsCount()!=order.items().size())throw Checkout.unavailable();
        UUID consumed=null;
        switch(r.getState()){
            case HELD->{if(r.getReleaseReason()!=ReservationReleaseReason.RESERVATION_RELEASE_REASON_UNSPECIFIED)throw Checkout.unavailable();}
            case RELEASED->{if(!Set.of(ReservationReleaseReason.CANCELLED,ReservationReleaseReason.EXPIRED,ReservationReleaseReason.UNAVAILABLE).contains(r.getReleaseReason()))throw Checkout.unavailable();}
            case CONSUMED->{consumed=uuid(r.getConsumedCaptureId());if(r.getReleaseReason()!=ReservationReleaseReason.RESERVATION_RELEASE_REASON_UNSPECIFIED||r.getConsumedAtEpochMicros()<r.getCreatedAtEpochMicros()||r.getConsumedAtEpochMicros()>=r.getExpiresAtEpochMicros())throw Checkout.unavailable();}
            default->throw Checkout.unavailable();
        }
        if(r.getState()!=ReservationState.CONSUMED&&(!r.getConsumedCaptureId().isEmpty()||r.getConsumedAtEpochMicros()!=0))throw Checkout.unavailable();
        var seen=new HashSet<String>();var lots=new HashSet<UUID>();
        for(var item:r.getItemsList()){
            var expected=order.items().stream().filter(i->i.productId().toString().equals(item.getProductId())&&i.skuId().toString().equals(item.getSkuId())).findFirst().orElseThrow(Checkout::unavailable);
            if(!seen.add(item.getSkuId())||item.getQuantity()!=expected.quantity()||item.getLotsCount()==0)throw Checkout.unavailable();
            long quantity=0;for(var lot:item.getLotsList()){if(!lots.add(uuid(lot.getLotId()))||lot.getQuantity()<1)throw Checkout.unavailable();quantity+=lot.getQuantity();}
            if(quantity!=item.getQuantity())throw Checkout.unavailable();
        }
        return new Result(r.getState().name(),consumed);
    }
    private UUID uuid(String value){try{var id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}catch(IllegalArgumentException e){throw Checkout.unavailable();}}
}
