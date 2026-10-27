package com.agora.catalog.domain;

import java.time.Instant;
import java.util.*;

public final class Checkout {
    private Checkout(){}
    public record QuoteRequest(UUID shopId,List<Reservation.Line> lines){
        public QuoteRequest {
            // Share the raw request's SKU, quantity and identity validation without changing its hash.
            lines=new Reservation.Request(shopId,shopId,shopId,lines).lines();
        }
    }
    public record Offer(UUID productId,UUID skuId,int quantity,String productName,String skuLabel,long unitPriceVnd,int netWeightGrams,long productVersion){}
    public record Quote(UUID shopId,String shopName,List<Offer> offers,Instant expiresAt){public Quote{offers=List.copyOf(offers);}}
    public record Result(Reservation reservation,String closed){
        public Result {if((reservation==null)==(closed==null)||closed!=null&&!Set.of("OFFER_UNAVAILABLE","CHECKOUT_EXPIRED").contains(closed))throw new IllegalArgumentException("Invalid checkout outcome.");}
        public static Result held(Reservation r){return new Result(Objects.requireNonNull(r),null);}
        public static Result closed(String reason){return new Result(null,reason);}
    }
}
