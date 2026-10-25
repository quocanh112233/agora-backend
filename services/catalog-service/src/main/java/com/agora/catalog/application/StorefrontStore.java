package com.agora.catalog.application;

import java.time.*;
import java.util.*;

public interface StorefrontStore {
    record Image(String url,int width,int height) {}
    record Sku(UUID id,String label,long priceVnd,int netWeightGrams,String saleUnit,boolean inStock) {}
    record Candidate(UUID id,UUID shopId,String name,String category,String condition,String origin,
                     long minPriceVnd,boolean inStock,Image thumbnail,Instant createdAt) {}
    record Details(Candidate product,String description,String producerName,String storageInstructions,List<Sku> skus,List<Image> images) {}
    List<Candidate> candidates(StorefrontQuery query,LocalDate today);
    Optional<Details> detail(UUID id,LocalDate today);
}
