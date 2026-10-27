package com.agora.catalog.application;

import com.agora.catalog.domain.*;
import com.agora.catalog.domain.Checkout.*;
import com.agora.catalog.domain.Reservation.Request;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class CheckoutService {
    private final CheckoutStore store;private final PublicShops shops;
    public CheckoutService(CheckoutStore store,PublicShops shops){this.store=store;this.shops=shops;}
    public Quote quote(QuoteRequest request){return access(()->{
        var quote=store.quote(request);
        var shop=shops.find(List.of(request.shopId())).get(request.shopId());
        if(shop==null)throw new CatalogFailure(409,"SHOP_UNAVAILABLE","Shop is not active.");
        return new Quote(quote.shopId(),shop.name(),quote.offers(),quote.expiresAt());
    });}
    public Result hold(Request request){
        if(!request.accepted())throw new IllegalArgumentException("Accepted checkout required.");
        return access(()->{
            var replay=store.replayCheckout(request);if(replay.isPresent())return replay.get();
            boolean active;
            try{active=shops.find(List.of(request.shopId())).containsKey(request.shopId());}
            catch(CatalogFailure e){replay=store.replayCheckout(request);if(replay.isPresent())return replay.get();throw e;}
            return store.holdCheckout(request,active);
        });
    }
    private <T>T access(Supplier<T> operation){
        try{return operation.get();}
        catch(TransientDataAccessException|DataAccessResourceFailureException|CannotCreateTransactionException|TransactionTimedOutException e){throw new CatalogFailure(503,"CATALOG_UNAVAILABLE","Checkout is temporarily unavailable.");}
        catch(org.springframework.jdbc.UncategorizedSQLException e){
            if(Set.of("55P03","57014").contains(String.valueOf(e.getSQLException().getSQLState())))throw new CatalogFailure(503,"CATALOG_UNAVAILABLE","Checkout is temporarily unavailable.");
            throw e;
        }
    }
}
