package com.agora.catalog.domain;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

public record Reservation(UUID checkoutId,UUID buyerId,UUID shopId,String state,Instant createdAt,
                          Instant expiresAt,String releaseReason,List<Item> items) {
    public Reservation {items=List.copyOf(items);}
    public record Line(UUID productId,UUID skuId,int quantity) {}
    public record Allocation(UUID lotId,int quantity) {}
    public record Item(UUID productId,UUID skuId,int quantity,List<Allocation> allocations) {
        public Item {allocations=List.copyOf(allocations);}
    }
    public record Request(UUID buyerId,UUID checkoutId,UUID shopId,List<Line> lines) {
        public Request {
            if(buyerId==null||checkoutId==null||shopId==null||lines==null||lines.isEmpty()||lines.size()>20)
                throw invalid();
            var seen=new HashSet<UUID>();
            for(var line:lines)if(line==null||line.productId()==null||line.skuId()==null||line.quantity()<1||line.quantity()>99||!seen.add(line.skuId()))throw invalid();
            lines=lines.stream().sorted(Comparator.comparing(line->line.skuId().toString())).toList();
        }
        public String hash(){
            var canonical=new StringBuilder().append(buyerId).append('|').append(shopId);
            for(var line:lines)canonical.append('|').append(line.productId()).append(':').append(line.skuId()).append(':').append(line.quantity());
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));}
            catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 is unavailable.",e);}
        }
        private static CatalogFailure invalid(){return new CatalogFailure(422,"INVALID_RESERVATION","Expected buyer, checkout, shop and 1–20 distinct SKUs with quantities 1–99.");}
    }
}
