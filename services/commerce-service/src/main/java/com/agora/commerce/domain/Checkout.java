package com.agora.commerce.domain;

import java.time.Instant;
import java.util.*;

public final class Checkout {
    private Checkout() {}
    public record Item(UUID productId,UUID skuId,String productName,String skuLabel,int netWeightGrams,long unitPriceVnd,int quantity,long lineTotalVnd) {}
    public record Preview(UUID id,UUID cartId,long cartVersion,UUID shopId,String shopName,String currency,long totalVnd,Instant expiresAt,List<Item> items) {
        public Preview { items=List.copyOf(items); }
    }
    public record Snapshot(Preview preview,List<Long> productVersions) {
        public Snapshot { productVersions=List.copyOf(productVersions); }
    }
    public record Attempt(UUID requestKey,UUID checkoutId,String state,UUID orderId,String failureCode) {}
    public record Work(UUID actor,UUID key,UUID checkoutId,long revision,Snapshot snapshot) {}
    public record Order(UUID id,String state,UUID shopId,String shopName,String currency,long totalVnd,Instant createdAt,Instant expiresAt,boolean cartCleared,List<Item> items) {}
    public record Summary(UUID id,String state,UUID shopId,String shopName,String currency,long totalVnd,Instant createdAt,Instant expiresAt,int itemCount) {
        public Summary(Order o){this(o.id(),o.state(),o.shopId(),o.shopName(),o.currency(),o.totalVnd(),o.createdAt(),o.expiresAt(),o.items().size());}
    }
    public record Page(List<Summary> items,String nextCursor) {}
    public static Instant instant(long micros){return Instant.ofEpochSecond(Math.floorDiv(micros,1000000),Math.floorMod(micros,1000000)*1000);}
    public static long micros(Instant time){return Math.addExact(Math.multiplyExact(time.getEpochSecond(),1000000),time.getNano()/1000);}
    public static CartFailure unavailable(){return new CartFailure(503,"CATALOG_UNAVAILABLE","Checkout information is unavailable.");}
    public static CartFailure missing(){return new CartFailure(404,"NOT_FOUND","Resource was not found.");}
    public static CartFailure conflict(){return new CartFailure(409,"CHECKOUT_CONFLICT","Cart or preview has changed. Preview the cart again.");}
}
