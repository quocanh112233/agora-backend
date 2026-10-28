package com.agora.commerce.adapter.outbound;

import com.agora.commerce.application.*;
import com.agora.commerce.domain.*;
import com.agora.commerce.domain.Checkout.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcCheckoutStore implements CheckoutStore {
    private final JdbcTemplate jdbc;private final JsonMapper json;
    public JdbcCheckoutStore(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
    private void actorLock(UUID actor){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"commerce-checkout:"+actor);}
    private Optional<Preview> existingPreview(UUID actor,UUID key,String hash){
        return jdbc.query("SELECT request_hash,snapshot FROM commerce.checkout_previews WHERE actor_id=? AND request_key=?",(r,n)->{
            if(!r.getString(1).equals(hash))throw mismatch();return json.readValue(r.getString(2),Snapshot.class).preview();
        },actor,key).stream().findFirst();
    }
    @Override @Transactional(readOnly=true)
    public Optional<Preview> replayPreview(UUID actor,UUID key,String hash){return existingPreview(actor,key,hash);}
    @Override @Transactional(timeout=10)
    public Preview savePreview(UUID actor,UUID key,String hash,Snapshot snapshot){
        actorLock(actor);var existing=existingPreview(actor,key,hash);if(existing.isPresent())return existing.get();
        var p=snapshot.preview();Checkout.paymentAmount(p.totalVnd());long version=lockCart(actor);if(version!=p.cartVersion())throw Checkout.conflict();
        jdbc.update("INSERT INTO commerce.checkout_previews(id,actor_id,request_key,request_hash,snapshot,expires_at) VALUES(?,?,?,?,?::jsonb,?)",p.id(),actor,key,hash,json.writeValueAsString(snapshot),Timestamp.from(p.expiresAt()));return p;
    }
    private long lockCart(UUID actor){
        var rows=jdbc.query("SELECT version FROM commerce.carts WHERE account_id=? FOR UPDATE",(r,n)->r.getLong(1),actor);
        if(rows.isEmpty())throw Checkout.conflict();return rows.getFirst();
    }
    private Attempt readAttempt(ResultSet r)throws SQLException{return new Attempt(r.getObject("request_key",UUID.class),r.getObject("checkout_id",UUID.class),r.getString("state"),r.getObject("order_id",UUID.class),r.getString("failure_code"));}
    private Optional<Attempt> existingAttempt(UUID actor,UUID key,UUID preview){
        return jdbc.query("SELECT * FROM commerce.checkout_attempts WHERE actor_id=? AND request_key=?",(r,n)->{if(preview!=null&&!r.getObject("preview_id",UUID.class).equals(preview))throw mismatch();return readAttempt(r);},actor,key).stream().findFirst();
    }
    @Override @Transactional(timeout=10)
    public Attempt claim(UUID actor,UUID key,UUID preview){
        actorLock(actor);var existing=existingAttempt(actor,key,preview);if(existing.isPresent())return existing.get();
        var previews=jdbc.query("SELECT snapshot,expires_at>clock_timestamp() AS valid FROM commerce.checkout_previews WHERE actor_id=? AND id=? FOR UPDATE",(r,n)->{if(!r.getBoolean("valid"))throw Checkout.conflict();return json.readValue(r.getString("snapshot"),Snapshot.class);},actor,preview);
        if(previews.isEmpty())throw Checkout.missing();var snapshot=previews.getFirst();Checkout.paymentAmount(snapshot.preview().totalVnd());long version=lockCart(actor);
        if(jdbc.queryForObject("SELECT count(*) FROM commerce.checkout_attempts WHERE preview_id=?",Integer.class,preview)!=0||version!=snapshot.preview().cartVersion())throw Checkout.conflict();
        UUID checkout=UUID.randomUUID();jdbc.update("INSERT INTO commerce.checkout_attempts(actor_id,request_key,preview_id,checkout_id) VALUES(?,?,?,?)",actor,key,preview,checkout);
        return new Attempt(key,checkout,"PROCESSING",null,null);
    }
    @Override @Transactional(readOnly=true)
    public Attempt attempt(UUID actor,UUID key){return existingAttempt(actor,key,null).orElseThrow(Checkout::missing);}
    @Override @Transactional(timeout=10)
    public Optional<Work> acquire(UUID actor,UUID key){
        return jdbc.query("UPDATE commerce.checkout_attempts SET revision=revision+1,lease_until=clock_timestamp()+interval '30 seconds' WHERE actor_id=? AND request_key=? AND state='PROCESSING' AND lease_until<=clock_timestamp() RETURNING checkout_id,revision,preview_id",(r,n)->new Work(actor,key,r.getObject(1,UUID.class),r.getLong(2),jdbc.queryForObject("SELECT snapshot FROM commerce.checkout_previews WHERE id=?",(s,m)->json.readValue(s.getString(1),Snapshot.class),r.getObject(3,UUID.class))),actor,key).stream().findFirst();
    }
    @Override @Transactional(timeout=10)
    public void complete(Work work,CheckoutCatalog.Decision decision){
        long version=lockCart(work.actor());
        var revisions=jdbc.query("SELECT revision FROM commerce.checkout_attempts WHERE actor_id=? AND request_key=? AND state='PROCESSING' AND lease_until>clock_timestamp() FOR UPDATE",(r,n)->r.getLong(1),work.actor(),work.key());
        if(revisions.isEmpty()||revisions.getFirst()!=work.revision())return;
        String failure=decision.failure();
        if(failure!=null){jdbc.update("UPDATE commerce.checkout_attempts SET state='FAILED',failure_code=? WHERE actor_id=? AND request_key=?",failure,work.actor(),work.key());return;}
        var p=work.snapshot().preview();boolean clear=version==p.cartVersion()&&version<9007199254740991L;
        if(clear){for(var item:p.items())jdbc.update("DELETE FROM commerce.cart_items WHERE account_id=? AND sku_id=?",work.actor(),item.skuId());jdbc.update("UPDATE commerce.carts SET version=version+1 WHERE account_id=?",work.actor());}
        UUID order=UUID.randomUUID();jdbc.update("INSERT INTO commerce.orders(id,actor_id,checkout_id,snapshot,expires_at,cart_cleared) VALUES(?,?,?,?::jsonb,?,?)",order,work.actor(),work.checkoutId(),json.writeValueAsString(p),Timestamp.from(decision.expiresAt()),clear);
        jdbc.update("UPDATE commerce.checkout_attempts SET state='ORDER_CREATED',order_id=? WHERE actor_id=? AND request_key=?",order,work.actor(),work.key());
    }
    @Override @Transactional(readOnly=true)
    public List<Map.Entry<UUID,UUID>> pending(){return jdbc.query("SELECT actor_id,request_key FROM commerce.checkout_attempts WHERE state='PROCESSING' AND lease_until<=clock_timestamp() ORDER BY lease_until,checkout_id LIMIT 20",(r,n)->Map.entry(r.getObject(1,UUID.class),r.getObject(2,UUID.class)));}
    private Order readOrder(ResultSet r)throws SQLException{
        var p=json.readValue(r.getString("snapshot"),Preview.class);return new Order(r.getObject("id",UUID.class),r.getString("state"),p.shopId(),p.shopName(),p.currency(),p.totalVnd(),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getBoolean("cart_cleared"),p.items());
    }
    @Override @Transactional(readOnly=true)
    public Order order(UUID actor,UUID id){return jdbc.query("SELECT * FROM commerce.orders WHERE actor_id=? AND id=?",(r,n)->readOrder(r),actor,id).stream().findFirst().orElseThrow(Checkout::missing);}
    @Override @Transactional(readOnly=true)
    public Page orders(UUID actor,String cursor){
        List<Order> orders;
        if(cursor==null)orders=jdbc.query("SELECT * FROM commerce.orders WHERE actor_id=? ORDER BY created_at DESC,id DESC LIMIT 21",(r,n)->readOrder(r),actor);
        else{
            Order anchor;
            try{
                if(cursor.length()>512||!cursor.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
                String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":",-1);
                if(parts.length!=2||!parts[0].equals(actor.toString())||!UUID.fromString(parts[1]).toString().equals(parts[1]))throw new IllegalArgumentException();
                anchor=order(actor,UUID.fromString(parts[1]));
            }catch(IllegalArgumentException|CartFailure e){throw new CartFailure(422,"VALIDATION_ERROR","Provide a valid cursor for this account.");}
            orders=jdbc.query("SELECT * FROM commerce.orders WHERE actor_id=? AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT 21",(r,n)->readOrder(r),actor,Timestamp.from(anchor.createdAt()),anchor.id());
        }
        String next=orders.size()>20?Base64.getUrlEncoder().withoutPadding().encodeToString((actor+":"+orders.get(19).id()).getBytes(StandardCharsets.UTF_8)):null;
        return new Page(orders.stream().limit(20).map(Summary::new).toList(),next);
    }
    private CartFailure mismatch(){return new CartFailure(422,"IDEMPOTENCY_MISMATCH","Idempotency key was already used for another request.");}
}
