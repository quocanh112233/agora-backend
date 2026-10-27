package com.agora.commerce.adapter.outbound;

import com.agora.commerce.application.CheckoutCatalog;
import com.agora.commerce.domain.*;
import com.agora.commerce.domain.Checkout.*;
import com.agora.contracts.catalog.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class GrpcCheckoutCatalog implements CheckoutCatalog {
    private final CatalogCheckoutGrpc.CatalogCheckoutBlockingStub stub;
    public GrpcCheckoutCatalog(@Qualifier("catalogChannel") ManagedChannel channel,@Value("${agora.internal-token}") String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid interservice token.");
        var headers=new Metadata();headers.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);
        stub=CatalogCheckoutGrpc.newBlockingStub(channel).withMaxInboundMessageSize(131072).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    @Override public Snapshot quote(UUID id,UUID cart,long version,UUID shop,List<Cart.Item> lines){
        var request=QuoteCheckoutRequest.newBuilder().setShopId(shop.toString());
        lines.forEach(i->request.addLines(CheckoutLine.newBuilder().setProductId(i.productId().toString()).setSkuId(i.skuId().toString()).setQuantity(i.quantity())));
        QuoteCheckoutResponse response;
        try{response=stub.withDeadlineAfter(10,TimeUnit.SECONDS).quoteCheckout(request.build());}
        catch(StatusRuntimeException e){if(e.getStatus().getCode()==Status.Code.FAILED_PRECONDITION)throw Checkout.conflict();throw Checkout.unavailable();}
        if(!response.getShopId().equals(shop.toString())||!text(response.getShopName(),1,120)||response.getOffersCount()!=lines.size()||response.getExpiresAtEpochMicros()<=0)throw Checkout.unavailable();
        var items=new ArrayList<Item>();var versions=new ArrayList<Long>();var seen=new HashSet<UUID>();long total=0;
        for(var offer:response.getOffersList()){
            Cart.Item line=lines.stream().filter(i->i.skuId().toString().equals(offer.getSkuId())&&i.productId().toString().equals(offer.getProductId())).findFirst().orElseThrow(Checkout::unavailable);
            if(!seen.add(line.skuId())||line.quantity()!=offer.getQuantity()||!text(offer.getProductName(),3,160)||!text(offer.getSkuLabel(),1,100)||offer.getUnitPriceVnd()<1||offer.getUnitPriceVnd()>1000000000L||offer.getProductVersion()<0||offer.getProductVersion()>9007199254740991L||offer.getNetWeightGrams()<1||offer.getNetWeightGrams()>50000)throw Checkout.unavailable();
            long amount=Math.multiplyExact(offer.getUnitPriceVnd(),line.quantity());total=Math.addExact(total,amount);
            items.add(new Item(line.productId(),line.skuId(),offer.getProductName(),offer.getSkuLabel(),offer.getNetWeightGrams(),offer.getUnitPriceVnd(),line.quantity(),amount));versions.add(offer.getProductVersion());
        }
        return new Snapshot(new Preview(id,cart,version,shop,response.getShopName(),"VND",total,Checkout.instant(response.getExpiresAtEpochMicros()),items),versions);
    }
    @Override public Decision hold(Work work){
        var p=work.snapshot().preview();var request=HoldCheckoutRequest.newBuilder().setBuyerId(work.actor().toString()).setCheckoutId(work.checkoutId().toString()).setShopId(p.shopId().toString()).setNotAfterEpochMicros(Checkout.micros(p.expiresAt()));
        for(int n=0;n<p.items().size();n++){var i=p.items().get(n);request.addLines(AcceptedCheckoutLine.newBuilder().setProductId(i.productId().toString()).setSkuId(i.skuId().toString()).setQuantity(i.quantity()).setUnitPriceVnd(i.unitPriceVnd()).setProductVersion(work.snapshot().productVersions().get(n)));}
        CheckoutHoldResult response;
        try{response=stub.withDeadlineAfter(10,TimeUnit.SECONDS).holdCheckout(request.build());}catch(StatusRuntimeException e){throw Checkout.unavailable();}
        if(response.hasClosed())return switch(response.getClosed().getReason()){
            case OFFER_UNAVAILABLE -> new Decision("OFFER_UNAVAILABLE",null);
            case CHECKOUT_EXPIRED -> new Decision("CHECKOUT_EXPIRED",null);
            default -> throw Checkout.unavailable();
        };
        if(!response.hasReservation())throw Checkout.unavailable();var r=response.getReservation();
        if(!r.getBuyerId().equals(work.actor().toString())||!r.getCheckoutId().equals(work.checkoutId().toString())||!r.getShopId().equals(p.shopId().toString())||r.getCreatedAtEpochMicros()<=0||r.getExpiresAtEpochMicros()-r.getCreatedAtEpochMicros()!=900000000L||r.getItemsCount()!=p.items().size())throw Checkout.unavailable();
        if(r.getState()==ReservationState.HELD&&r.getReleaseReason()!=ReservationReleaseReason.RESERVATION_RELEASE_REASON_UNSPECIFIED||r.getState()==ReservationState.RELEASED&&r.getReleaseReason()!=ReservationReleaseReason.CANCELLED&&r.getReleaseReason()!=ReservationReleaseReason.EXPIRED)throw Checkout.unavailable();
        var seen=new HashSet<String>();var lots=new HashSet<String>();
        for(var i:r.getItemsList()){
            var expected=p.items().stream().filter(v->v.skuId().toString().equals(i.getSkuId())&&v.productId().toString().equals(i.getProductId())).findFirst().orElseThrow(Checkout::unavailable);
            if(!seen.add(i.getSkuId())||i.getQuantity()!=expected.quantity()||i.getLotsCount()==0)throw Checkout.unavailable();
            long quantity=0;
            for(var lot:i.getLotsList()){try{if(!UUID.fromString(lot.getLotId()).toString().equals(lot.getLotId())||!lots.add(lot.getLotId())||lot.getQuantity()<1)throw Checkout.unavailable();}catch(IllegalArgumentException e){throw Checkout.unavailable();}quantity+=lot.getQuantity();}
            if(quantity!=i.getQuantity())throw Checkout.unavailable();
        }
        return switch(r.getState()){
            case HELD -> new Decision(null,Checkout.instant(r.getExpiresAtEpochMicros()));
            case RELEASED -> new Decision("RESERVATION_RELEASED",null);
            default -> throw Checkout.unavailable();
        };
    }
    private boolean text(String s,int min,int max){int n=s.codePointCount(0,s.length());return !s.isBlank()&&n>=min&&n<=max;}
}
