package com.agora.catalog.application;

import com.agora.catalog.domain.CatalogFailure;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

@Service
public class StorefrontService {
    private final StorefrontStore store;private final PublicShops shops;private final Clock clock;
    public StorefrontService(StorefrontStore store,PublicShops shops,Clock clock){this.store=store;this.shops=shops;this.clock=clock;}
    public record Summary(UUID id,String name,String category,String condition,String origin,PublicShops.Shop shop,boolean inStock,long minPriceVnd,StorefrontStore.Image thumbnail) {}
    public record Product(UUID id,String name,String category,String condition,String origin,PublicShops.Shop shop,boolean inStock,String description,String producerName,String storageInstructions,List<StorefrontStore.Sku> skus,List<StorefrontStore.Image> images) {}
    public record Page(List<Summary> items,String nextCursor) {}
    private LocalDate today(){return LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Ho_Chi_Minh"));}
    public Page browse(StorefrontQuery query){
        var found=read(()->store.candidates(query,today()));var window=found.subList(0,Math.min(20,found.size()));
        var active=shops.find(window.stream().map(StorefrontStore.Candidate::shopId).toList());
        var items=window.stream().filter(p->active.containsKey(p.shopId())).map(p->new Summary(p.id(),p.name(),p.category(),p.condition(),p.origin(),active.get(p.shopId()),p.inStock(),p.minPriceVnd(),p.thumbnail())).toList();
        var last=window.isEmpty()?null:window.getLast();return new Page(items,found.size()>20?query.next(last.createdAt(),last.id()):null);
    }
    public Product detail(UUID id){
        var d=read(()->store.detail(id,today())).orElseThrow(this::missing);var p=d.product();
        var shop=shops.find(List.of(p.shopId())).get(p.shopId());if(shop==null)throw missing();
        return new Product(p.id(),p.name(),p.category(),p.condition(),p.origin(),shop,p.inStock(),d.description(),d.producerName(),d.storageInstructions(),d.skus(),d.images());
    }
    private CatalogFailure missing(){return new CatalogFailure(404,"PRODUCT_NOT_FOUND","Product is unavailable.");}
    private <T> T read(Supplier<T> query){
        try{return query.get();}
        catch(DataAccessResourceFailureException|CannotCreateTransactionException e){
            throw new CatalogFailure(503,"CATALOG_UNAVAILABLE","Public product information is unavailable.");
        }
    }
}
