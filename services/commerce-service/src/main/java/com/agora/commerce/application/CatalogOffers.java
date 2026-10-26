package com.agora.commerce.application;

import java.util.*;

public interface CatalogOffers {
    record Image(String url,int width,int height) {}
    record Offer(UUID productId,UUID skuId,UUID shopId,String shopName,String productName,String skuLabel,
                 long priceVnd,int netWeightGrams,boolean inStock,Image thumbnail) {}
    Map<UUID,Offer> find(List<UUID> skus);
}
