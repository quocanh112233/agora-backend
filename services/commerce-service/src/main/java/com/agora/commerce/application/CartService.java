package com.agora.commerce.application;

import com.agora.commerce.domain.CartFailure;
import com.agora.commerce.application.CartStore.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

@Service
public class CartService {
    private final CartStore store;private final CatalogOffers catalog;
    public CartService(CartStore store,CatalogOffers catalog){this.store=store;this.catalog=catalog;}
    public record Item(UUID productId,UUID skuId,int quantity,CatalogOffers.Offer offer) {}
    public record View(UUID cartId,long version,List<Item> items) {}
    public static UUID cartId(UUID actor){return UUID.nameUUIDFromBytes(("agora-cart:"+actor).getBytes(StandardCharsets.UTF_8));}
    public View read(UUID actor){
        var cart=storage(()->store.read(actor));
        var offers=catalog.find(cart.items().stream().map(i->i.skuId()).toList());
        var items=cart.items().stream().map(i->{
            var offer=offers.get(i.skuId());
            if(offer!=null&&!offer.productId().equals(i.productId()))throw new CartFailure(503,"CATALOG_UNAVAILABLE","Product information is unavailable.");
            return new Item(i.productId(),i.skuId(),i.quantity(),offer);
        }).toList();
        return new View(cartId(actor),cart.version(),items);
    }
    public Mutation put(Key key,UUID cartId,UUID product,UUID sku,int quantity,long version){
        checkCart(key.actor(),cartId);
        var replay=storage(()->store.replay(key));if(replay.isPresent())return replay.get();
        try{
            var offer=catalog.find(List.of(sku)).get(sku);
            if(offer==null||!offer.productId().equals(product)||!offer.inStock())throw new CartFailure(409,"PRODUCT_UNAVAILABLE","Product is unavailable. Reload the cart.");
        }catch(CartFailure failure){
            // A concurrent original request may have committed while this lookup failed.
            replay=storage(()->store.replay(key));if(replay.isPresent())return replay.get();throw failure;
        }
        return storage(()->store.put(key,product,sku,quantity,version));
    }
    public Mutation remove(Key key,UUID cartId,UUID sku,long version){checkCart(key.actor(),cartId);return storage(()->store.remove(key,sku,version));}
    private void checkCart(UUID actor,UUID supplied){if(!cartId(actor).equals(supplied))throw new CartFailure(409,"CART_CHANGED","Account cart has changed. Reload it before continuing.");}
    private <T> T storage(Supplier<T> action){
        try{return action.get();}
        catch(DataAccessResourceFailureException|CannotCreateTransactionException e){throw new CartFailure(503,"COMMERCE_UNAVAILABLE","Cart storage is unavailable.");}
    }
}
