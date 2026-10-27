package com.agora.commerce.application;

import com.agora.commerce.domain.*;
import com.agora.commerce.domain.Checkout.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class CheckoutService {
    private final CheckoutStore store;private final CartStore carts;private final CheckoutCatalog catalog;
    public CheckoutService(CheckoutStore store,CartStore carts,CheckoutCatalog catalog){this.store=store;this.carts=carts;this.catalog=catalog;}
    public Preview preview(UUID actor,UUID key,String hash,UUID cartId,long version,UUID shop,List<UUID> skus){
        if(!CartService.cartId(actor).equals(cartId))throw Checkout.conflict();
        var replay=storage(()->store.replayPreview(actor,key,hash));if(replay.isPresent())return replay.get();
        Snapshot snapshot;
        try{
            var cart=storage(()->carts.read(actor));if(cart.version()!=version)throw Checkout.conflict();
            var lines=cart.items().stream().filter(i->skus.contains(i.skuId())).toList();if(lines.size()!=skus.size())throw Checkout.conflict();
            snapshot=catalog.quote(UUID.randomUUID(),cartId,version,shop,lines);
        }catch(CartFailure e){replay=storage(()->store.replayPreview(actor,key,hash));if(replay.isPresent())return replay.get();throw e;}
        return storage(()->store.savePreview(actor,key,hash,snapshot));
    }
    public Attempt confirm(UUID actor,UUID key,UUID preview){
        var attempt=storage(()->store.claim(actor,key,preview));if(!attempt.state().equals("PROCESSING"))return attempt;
        process(actor,key);return storage(()->store.attempt(actor,key));
    }
    public Attempt attempt(UUID actor,UUID key){return storage(()->store.attempt(actor,key));}
    public Order order(UUID actor,UUID id){return storage(()->store.order(actor,id));}
    public Page orders(UUID actor,String cursor){return storage(()->store.orders(actor,cursor));}
    private void process(UUID actor,UUID key){
        try{store.acquire(actor,key).ifPresent(work->{var result=catalog.hold(work);store.complete(work,result);});}
        catch(CartFailure e){if(e.status()!=503)throw e;}
        catch(DataAccessException|TransactionException e){org.slf4j.LoggerFactory.getLogger(CheckoutService.class).warn("Checkout remains pending after storage failure type={}",e.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelay=10000,initialDelay=10000)
    public void recover(){
        try{for(var key:store.pending())process(key.getKey(),key.getValue());}
        catch(DataAccessException|TransactionException e){org.slf4j.LoggerFactory.getLogger(CheckoutService.class).warn("Checkout recovery storage unavailable type={}",e.getClass().getSimpleName());}
    }
    private <T>T storage(Supplier<T> action){try{return action.get();}catch(DataAccessException|TransactionException e){throw new CartFailure(503,"COMMERCE_UNAVAILABLE","Checkout storage is unavailable.");}}
}
