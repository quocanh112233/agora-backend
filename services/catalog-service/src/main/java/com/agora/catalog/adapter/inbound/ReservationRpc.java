package com.agora.catalog.adapter.inbound;

import com.agora.catalog.application.ReservationService;
import com.agora.catalog.domain.CatalogFailure;
import com.agora.catalog.domain.Reservation;
import com.agora.contracts.catalog.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

@Component
public class ReservationRpc extends CatalogReservationsGrpc.CatalogReservationsImplBase {
    private final ReservationService service;
    public ReservationRpc(ReservationService service){this.service=service;}
    @Override public void hold(HoldReservationRequest input,StreamObserver<ReservationResponse> observer){
        invoke(observer,()->service.hold(new Reservation.Request(uuid(input.getBuyerId()),uuid(input.getCheckoutId()),uuid(input.getShopId()),
                input.getLinesList().stream().map(line->new Reservation.Line(uuid(line.getProductId()),uuid(line.getSkuId()),line.getQuantity())).toList())));
    }
    @Override public void get(ReservationReference input,StreamObserver<ReservationResponse> observer){
        invoke(observer,()->service.get(uuid(input.getBuyerId()),uuid(input.getCheckoutId())));
    }
    @Override public void release(ReservationReference input,StreamObserver<ReservationResponse> observer){
        invoke(observer,()->service.release(uuid(input.getBuyerId()),uuid(input.getCheckoutId())));
    }
    @Override public void consume(ConsumeReservationRequest input,StreamObserver<ReservationResponse> observer){
        invoke(observer,()->service.consume(uuid(input.getBuyerId()),uuid(input.getCheckoutId()),uuid(input.getCaptureId())));
    }
    private static UUID uuid(String value){
        try{
            UUID id=UUID.fromString(value);
            if(!id.toString().equalsIgnoreCase(value))throw new IllegalArgumentException();
            return id;
        }catch(IllegalArgumentException e){throw new CatalogFailure(422,"INVALID_RESERVATION","Invalid UUID.");}
    }
    private void invoke(StreamObserver<ReservationResponse> observer,Supplier<Reservation> action){
        try{
            var response=response(action.get());
            if(response.getSerializedSize()>131072)throw new IllegalStateException("Reservation response exceeds its contract.");
            observer.onNext(response);observer.onCompleted();
        }catch(CatalogFailure e){
            Status status=java.util.Set.of("IDEMPOTENCY_PAYLOAD_MISMATCH","CAPTURE_MISMATCH","CAPTURE_ALREADY_USED").contains(e.code())?Status.ALREADY_EXISTS:switch(e.status()){
                case 400,422->Status.INVALID_ARGUMENT;case 404->Status.NOT_FOUND;case 409->Status.FAILED_PRECONDITION;
                case 429->Status.RESOURCE_EXHAUSTED;case 503->Status.UNAVAILABLE;default->Status.INTERNAL;
            };
            observer.onError(status.asRuntimeException());
        }catch(TransientDataAccessException|DataAccessResourceFailureException|CannotCreateTransactionException e){observer.onError(Status.UNAVAILABLE.asRuntimeException());}
        catch(RuntimeException e){
            org.slf4j.LoggerFactory.getLogger(ReservationRpc.class).error("Reservation RPC failed type={}",e.getClass().getSimpleName());
            observer.onError(Status.INTERNAL.asRuntimeException());
        }
    }
    static long micros(Instant value){return Math.addExact(Math.multiplyExact(value.getEpochSecond(),1000000L),value.getNano()/1000);}
    static ReservationResponse response(Reservation view){
        var result=ReservationResponse.newBuilder().setCheckoutId(view.checkoutId().toString()).setBuyerId(view.buyerId().toString()).setShopId(view.shopId().toString())
                .setState(ReservationState.valueOf(view.state())).setCreatedAtEpochMicros(micros(view.createdAt())).setExpiresAtEpochMicros(micros(view.expiresAt()));
        if(view.state().equals("CONSUMED")){
            if(view.consumedCaptureId()==null||view.consumedAt()==null||view.releaseReason()!=null||view.consumedAt().isBefore(view.createdAt())||!view.consumedAt().isBefore(view.expiresAt()))throw new IllegalStateException("Invalid consumption metadata.");
            result.setConsumedCaptureId(view.consumedCaptureId().toString()).setConsumedAtEpochMicros(micros(view.consumedAt()));
        }else if(view.consumedCaptureId()!=null||view.consumedAt()!=null)throw new IllegalStateException("Unexpected consumption metadata.");
        if(view.releaseReason()!=null)result.setReleaseReason(ReservationReleaseReason.valueOf(view.releaseReason()));
        for(var item:view.items()){
            var line=ReservedItem.newBuilder().setProductId(item.productId().toString()).setSkuId(item.skuId().toString()).setQuantity(item.quantity());
            for(var lot:item.allocations())line.addLots(ReservedLot.newBuilder().setLotId(lot.lotId().toString()).setQuantity(lot.quantity()));
            result.addItems(line);
        }
        return result.build();
    }
}
