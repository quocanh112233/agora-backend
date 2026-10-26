package com.agora.catalog.application;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CatalogOffersService {
    private final StorefrontStore store;
    private final PublicShops shops;
    private final Clock clock;
    public CatalogOffersService(StorefrontStore store,PublicShops shops,Clock clock){this.store=store;this.shops=shops;this.clock=clock;}
    public record Offer(UUID productId,UUID skuId,UUID shopId,String shopName,String productName,String skuLabel,
                        long priceVnd,int netWeightGrams,boolean inStock,StorefrontStore.Image thumbnail) {}
    public List<Offer> resolve(List<UUID> skuIds){
        if(skuIds.isEmpty()||skuIds.size()>20||skuIds.stream().anyMatch(Objects::isNull)||new HashSet<>(skuIds).size()!=skuIds.size())
            throw new IllegalArgumentException("Expected 1–20 distinct SKU IDs.");
        var rows=store.offers(skuIds,LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Ho_Chi_Minh")));
        if(rows.isEmpty())return List.of();
        var active=shops.find(rows.stream().map(StorefrontStore.Offer::shopId).distinct().toList());
        return rows.stream().filter(row->active.containsKey(row.shopId())).map(row->new Offer(
                row.productId(),row.skuId(),row.shopId(),active.get(row.shopId()).name(),row.productName(),row.skuLabel(),
                row.priceVnd(),row.netWeightGrams(),row.inStock(),row.thumbnail())).toList();
    }
}
