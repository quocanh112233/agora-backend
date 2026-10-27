package com.agora.catalog.application;

import com.agora.catalog.domain.*;
import com.agora.catalog.domain.Reservation.Request;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class ReservationService {
    private final ReservationStore store;
    private final PublicShops shops;
    public ReservationService(ReservationStore store,PublicShops shops){this.store=store;this.shops=shops;}
    public Reservation hold(Request request){
        Objects.requireNonNull(request);
        return access(()->{
            var replay=store.replay(request);if(replay.isPresent())return replay.get();
            try{
                if(!shops.find(List.of(request.shopId())).containsKey(request.shopId()))
                    throw new CatalogFailure(409,"SHOP_UNAVAILABLE","Shop is not active.");
            }catch(CatalogFailure failure){
                // A concurrent Hold may have committed while Merchant was unavailable.
                replay=store.replay(request);if(replay.isPresent())return replay.get();throw failure;
            }
            return store.create(request);
        });
    }
    public Reservation get(UUID buyer,UUID checkout){validate(buyer,checkout);return access(()->store.get(buyer,checkout));}
    public Reservation release(UUID buyer,UUID checkout){validate(buyer,checkout);return access(()->store.release(buyer,checkout));}
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public int expire(){return access(store::expire);}
    private static void validate(UUID buyer,UUID checkout){if(buyer==null||checkout==null)throw new CatalogFailure(422,"INVALID_RESERVATION","Buyer and checkout are required.");}
    private <T> T access(Supplier<T> operation){
        try{return operation.get();}
        catch(TransientDataAccessException|DataAccessResourceFailureException|CannotCreateTransactionException|TransactionTimedOutException e){
            throw unavailable();
        }catch(UncategorizedSQLException e){
            String state=e.getSQLException().getSQLState();
            if("55P03".equals(state)||"57014".equals(state))throw unavailable();
            throw e;
        }
    }
    private static CatalogFailure unavailable(){return new CatalogFailure(503,"CATALOG_UNAVAILABLE","Reservations are temporarily unavailable.");}
}
