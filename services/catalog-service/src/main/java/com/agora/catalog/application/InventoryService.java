package com.agora.catalog.application;

import com.agora.catalog.application.InventoryStore.*;
import com.agora.catalog.domain.*;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class InventoryService {
    private final InventoryStore store;
    private final ProductStore products;
    private final Clock clock;
    public InventoryService(InventoryStore store,ProductStore products,Clock clock){this.store=store;this.products=products;this.clock=clock;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(UUID shop,UUID product,int page,int size){
        products.owned(shop,product).orElseThrow(InventoryService::notFound);
        LocalDate today=LocalDate.now(clock);
        return new Page(store.list(shop,product,page,size).stream().map(lot->lot.view(today)).toList(),page,size,store.count(shop,product));
    }
    @Transactional
    public Result create(Key key,InventoryLot.Input input,String traceId){
        products.owned(key.shopId(),key.productId()).orElseThrow(InventoryService::notFound);
        var replay=store.claim(key);if(replay.isPresent())return replay.get();
        // Lock the parent before checking the SKU, sharing the edit/delete lock.
        Product product=products.lockOwned(key.shopId(),key.productId()).orElseThrow(InventoryService::notFound);
        if(product.skus().stream().noneMatch(sku->sku.id().equals(input.skuId())))throw notFound();
        LocalDate today=LocalDate.now(clock);
        InventoryLot lot=InventoryLot.create(key.productId(),key.shopId(),input,today);
        store.insert(lot);store.audit(lot,key.actor(),"CREATE_LOT",traceId);
        Result result=new Result(201,lot.view(today));store.complete(key,result);return result;
    }
    @Transactional
    public Result changeStatus(Key key,UUID id,long version,String target,String traceId){
        var replay=store.claim(key);if(replay.isPresent())return replay.get();
        InventoryLot lot=store.lockOwned(key.shopId(),key.productId(),id).orElseThrow(InventoryService::notFound);
        LocalDate today=LocalDate.now(clock);
        InventoryLot changed=lot.changeStatus(version,target,today);
        store.update(changed,version);store.audit(changed,key.actor(),target.equals("BLOCKED")?"BLOCK_LOT":"OPEN_LOT",traceId);
        Result result=new Result(200,changed.view(today));store.complete(key,result);return result;
    }
    private static CatalogFailure notFound(){return new CatalogFailure(404,"LOT_NOT_FOUND","Product, SKU or lot was not found.");}
}
