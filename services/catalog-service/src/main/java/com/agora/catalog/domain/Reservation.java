package com.agora.catalog.domain;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

public record Reservation(UUID checkoutId,UUID buyerId,UUID shopId,String state,Instant createdAt,
                          Instant expiresAt,String releaseReason,List<Item> items,UUID consumedCaptureId,Instant consumedAt) {
    public Reservation(UUID checkoutId,UUID buyerId,UUID shopId,String state,Instant createdAt,Instant expiresAt,String releaseReason,List<Item> items){this(checkoutId,buyerId,shopId,state,createdAt,expiresAt,releaseReason,items,null,null);}
    public Reservation {items=List.copyOf(items);}
    public record Line(UUID productId,UUID skuId,int quantity) {}
    public record Allocation(UUID lotId,int quantity) {}
    public record Item(UUID productId,UUID skuId,int quantity,List<Allocation> allocations) {
        public Item {allocations=List.copyOf(allocations);}
    }
    public record Acceptance(long unitPriceVnd,long productVersion) {
        public Acceptance {if(unitPriceVnd<1||unitPriceVnd>1000000000L||productVersion<0||productVersion>9007199254740991L)throw new CatalogFailure(422,"INVALID_RESERVATION","Invalid accepted price or version.");}
    }
    public record Request(UUID buyerId,UUID checkoutId,UUID shopId,List<Line> lines,Long notAfterEpochMicros,Map<UUID,Acceptance> acceptedLines) {
        public Request(UUID buyerId,UUID checkoutId,UUID shopId,List<Line> lines){this(buyerId,checkoutId,shopId,lines,null,Map.of());}
        public boolean accepted(){return notAfterEpochMicros!=null;}
        public Request {
            if(buyerId==null||checkoutId==null||shopId==null||lines==null||lines.isEmpty()||lines.size()>20)
                throw invalid();
            var seen=new HashSet<UUID>();
            for(var line:lines)if(line==null||line.productId()==null||line.skuId()==null||line.quantity()<1||line.quantity()>99||!seen.add(line.skuId()))throw invalid();
            if(acceptedLines==null)throw invalid();
            acceptedLines=Map.copyOf(acceptedLines);
            if(notAfterEpochMicros==null?!acceptedLines.isEmpty():notAfterEpochMicros<=0||!acceptedLines.keySet().equals(seen))throw invalid();
            lines=lines.stream().sorted(Comparator.comparing(line->line.skuId().toString())).toList();
        }
        public String hash(){
            var canonical=new StringBuilder().append(buyerId).append('|').append(shopId);
            for(var line:lines)canonical.append('|').append(line.productId()).append(':').append(line.skuId()).append(':').append(line.quantity());
            if(accepted()){
                canonical.append("|checkout-v1:").append(notAfterEpochMicros);
                for(var line:lines){var accepted=acceptedLines.get(line.skuId());canonical.append('|').append(line.skuId()).append(':').append(accepted.unitPriceVnd()).append(':').append(accepted.productVersion());}
            }
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));}
            catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 is unavailable.",e);}
        }
        private static CatalogFailure invalid(){return new CatalogFailure(422,"INVALID_RESERVATION","Expected buyer, checkout, shop and 1–20 distinct SKUs with quantities 1–99.");}
    }
}
