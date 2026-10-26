package com.agora.commerce.adapter.outbound;

import com.agora.commerce.application.CartStore;
import com.agora.commerce.domain.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class JdbcCartStore implements CartStore {
    private final JdbcTemplate jdbc;
    public JdbcCartStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Cart read(UUID actor){
        var versions=jdbc.query("SELECT version FROM commerce.carts WHERE account_id=?",(r,n)->r.getLong(1),actor);
        return versions.isEmpty()?Cart.empty():load(actor,versions.getFirst());
    }
    private Cart load(UUID actor,long version){return new Cart(version,jdbc.query("SELECT product_id,sku_id,quantity FROM commerce.cart_items WHERE account_id=? ORDER BY sku_id",(r,n)->new Cart.Item(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3)),actor));}
    @Override @Transactional(readOnly=true)
    public Optional<Mutation> replay(Key key){return existing(key);}
    private Optional<Mutation> existing(Key key){
        return jdbc.query("SELECT operation,request_hash,response_version FROM commerce.cart_requests WHERE actor_id=? AND key=?",(r,n)->{
            if(!r.getString(1).equals(key.operation())||!r.getString(2).equals(key.hash()))throw new CartFailure(422,"IDEMPOTENCY_MISMATCH","Idempotency key was already used for another request.");
            return new Mutation(r.getLong(3));
        },key.actor(),key.key()).stream().findFirst();
    }
    @Override @Transactional
    public Mutation put(Key key,UUID product,UUID sku,int quantity,long version){
        return mutate(key,cart->cart.put(product,sku,quantity,version),()->jdbc.update("INSERT INTO commerce.cart_items(account_id,sku_id,product_id,quantity) VALUES(?,?,?,?) ON CONFLICT(account_id,sku_id) DO UPDATE SET quantity=EXCLUDED.quantity",key.actor(),sku,product,quantity));
    }
    @Override @Transactional
    public Mutation remove(Key key,UUID sku,long version){return mutate(key,cart->cart.remove(sku,version),()->jdbc.update("DELETE FROM commerce.cart_items WHERE account_id=? AND sku_id=?",key.actor(),sku));}
    private Mutation mutate(Key key,Function<Cart,Cart> change,Runnable write){
        jdbc.update("INSERT INTO commerce.carts(account_id,version) VALUES(?,0) ON CONFLICT(account_id) DO NOTHING",key.actor());
        long version=jdbc.queryForObject("SELECT version FROM commerce.carts WHERE account_id=? FOR UPDATE",Long.class,key.actor());
        var replay=existing(key);if(replay.isPresent())return replay.get();
        Cart changed=change.apply(load(key.actor(),version));
        if(changed.version()!=version){
            write.run();
            if(jdbc.update("UPDATE commerce.carts SET version=? WHERE account_id=? AND version=?",changed.version(),key.actor(),version)!=1)throw new CartFailure(409,"CART_VERSION_CONFLICT","Cart has changed. Reload it.");
        }
        jdbc.update("INSERT INTO commerce.cart_requests(actor_id,key,operation,request_hash,response_version) VALUES(?,?,?,?,?)",key.actor(),key.key(),key.operation(),key.hash(),changed.version());
        return new Mutation(changed.version());
    }
    @Scheduled(fixedDelay=3600000,initialDelay=3600000)
    public void cleanupRequests(){jdbc.update("DELETE FROM commerce.cart_requests WHERE created_at < now() - interval '7 days'");}
}
