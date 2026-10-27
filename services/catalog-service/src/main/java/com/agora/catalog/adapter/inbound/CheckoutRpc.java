package com.agora.catalog.adapter.inbound;

import com.agora.catalog.application.CheckoutService;
import com.agora.catalog.domain.*;
import com.agora.catalog.domain.Checkout.*;
import com.agora.contracts.catalog.v1.*;
import com.google.protobuf.MessageLite;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;

@Component
public class CheckoutRpc extends CatalogCheckoutGrpc.CatalogCheckoutImplBase {
    private final CheckoutService service;
    public CheckoutRpc(CheckoutService service){this.service=service;}
    @Override public void quoteCheckout(QuoteCheckoutRequest input,StreamObserver<QuoteCheckoutResponse> observer){
        invoke(observer,()->{
            var request=new QuoteRequest(ProductInput.uuid(input.getShopId()),input.getLinesList().stream().map(l->new Reservation.Line(ProductInput.uuid(l.getProductId()),ProductInput.uuid(l.getSkuId()),l.getQuantity())).toList());
            var quote=service.quote(request);
            if(!request.shopId().equals(quote.shopId())||quote.offers().size()!=request.lines().size()||!text(quote.shopName(),120)||quote.expiresAt()==null)throw malformed();
            var seen=new HashSet<UUID>();var response=QuoteCheckoutResponse.newBuilder().setShopId(quote.shopId().toString()).setShopName(quote.shopName()).setExpiresAtEpochMicros(ReservationRpc.micros(quote.expiresAt()));
            for(var offer:quote.offers()){
                if(!seen.add(offer.skuId())||request.lines().stream().noneMatch(l->l.productId().equals(offer.productId())&&l.skuId().equals(offer.skuId())&&l.quantity()==offer.quantity())
                    ||!text(offer.productName(),160)||!text(offer.skuLabel(),100)||offer.unitPriceVnd()<1||offer.unitPriceVnd()>1000000000L||offer.productVersion()<0||offer.productVersion()>9007199254740991L||offer.netWeightGrams()<1||offer.netWeightGrams()>50000)throw malformed();
                response.addOffers(CheckoutOffer.newBuilder().setProductId(offer.productId().toString()).setSkuId(offer.skuId().toString()).setQuantity(offer.quantity()).setProductName(offer.productName()).setSkuLabel(offer.skuLabel()).setUnitPriceVnd(offer.unitPriceVnd()).setNetWeightGrams(offer.netWeightGrams()).setProductVersion(offer.productVersion()));
            }
            return response.build();
        });
    }
    @Override public void holdCheckout(HoldCheckoutRequest input,StreamObserver<CheckoutHoldResult> observer){
        invoke(observer,()->{
            var lines=new ArrayList<Reservation.Line>();var accepted=new HashMap<UUID,Reservation.Acceptance>();
            for(var line:input.getLinesList()){
                UUID sku=ProductInput.uuid(line.getSkuId());lines.add(new Reservation.Line(ProductInput.uuid(line.getProductId()),sku,line.getQuantity()));
                accepted.put(sku,new Reservation.Acceptance(line.getUnitPriceVnd(),line.getProductVersion()));
            }
            var request=new Reservation.Request(ProductInput.uuid(input.getBuyerId()),ProductInput.uuid(input.getCheckoutId()),ProductInput.uuid(input.getShopId()),lines,input.getNotAfterEpochMicros(),accepted);
            var result=service.hold(request);var response=CheckoutHoldResult.newBuilder();
            if(result.reservation()!=null){
                var r=result.reservation();
                if(!r.buyerId().equals(request.buyerId())||!r.checkoutId().equals(request.checkoutId())||!r.shopId().equals(request.shopId())||r.items().size()!=request.lines().size()
                    ||!Set.of("HELD","RELEASED").contains(r.state())||!r.expiresAt().equals(r.createdAt().plusSeconds(900))
                    ||r.state().equals("HELD")&&r.releaseReason()!=null||r.state().equals("RELEASED")&&!Set.of("CANCELLED","EXPIRED").contains(String.valueOf(r.releaseReason())))throw malformed();
                var seen=new HashSet<UUID>();var lots=new HashSet<UUID>();
                for(var item:r.items()){
                    if(!seen.add(item.skuId())||request.lines().stream().noneMatch(l->l.productId().equals(item.productId())&&l.skuId().equals(item.skuId())&&l.quantity()==item.quantity())||item.allocations().isEmpty())throw malformed();
                    long total=0;for(var allocation:item.allocations()){
                        if(allocation.lotId()==null||!lots.add(allocation.lotId())||allocation.quantity()<1||allocation.quantity()>99)throw malformed();total+=allocation.quantity();
                    }
                    if(total!=item.quantity())throw malformed();
                }
                response.setReservation(ReservationRpc.response(r));
            }else response.setClosed(CheckoutClosed.newBuilder().setReason(CheckoutClosureReason.valueOf(result.closed())));
            return response.build();
        });
    }
    private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.codePointCount(0,value.length())<=max;}
    private static IllegalStateException malformed(){return new IllegalStateException("Invalid checkout response.");}
    private <T extends MessageLite>void invoke(StreamObserver<T> observer,Supplier<T> operation){
        try{
            T response=operation.get();if(response.getSerializedSize()>131072)throw malformed();
            observer.onNext(response);observer.onCompleted();
        }catch(CatalogFailure e){
            Status status=e.code().equals("IDEMPOTENCY_PAYLOAD_MISMATCH")?Status.ALREADY_EXISTS:switch(e.status()){
                case 400,422->Status.INVALID_ARGUMENT;case 404->Status.NOT_FOUND;case 409->Status.FAILED_PRECONDITION;
                case 429->Status.RESOURCE_EXHAUSTED;case 503->Status.UNAVAILABLE;default->Status.INTERNAL;
            };observer.onError(status.asRuntimeException());
        }catch(TransientDataAccessException|DataAccessResourceFailureException|CannotCreateTransactionException|TransactionTimedOutException e){observer.onError(Status.UNAVAILABLE.asRuntimeException());}
        catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(CheckoutRpc.class).error("Checkout RPC failed type={}",e.getClass().getSimpleName());observer.onError(Status.INTERNAL.asRuntimeException());}
    }
}
