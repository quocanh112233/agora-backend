package com.agora.catalog.adapter.outbound;

import com.agora.catalog.application.InventoryStore;
import com.agora.catalog.domain.*;
import java.sql.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcInventoryStore implements InventoryStore {
    private final JdbcTemplate jdbc;private final JsonMapper json;
    public JdbcInventoryStore(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
    private InventoryLot map(ResultSet r,int index)throws SQLException{
        var produced=r.getDate("produced_on");
        return new InventoryLot(r.getObject("id",UUID.class),r.getObject("product_id",UUID.class),r.getObject("shop_id",UUID.class),
                r.getObject("sku_id",UUID.class),r.getString("code"),produced==null?null:produced.toLocalDate(),r.getDate("expires_on").toLocalDate(),
                r.getLong("on_hand"),r.getLong("held"),r.getLong("allocated"),r.getString("state"),r.getLong("version"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    }
    @Override public List<InventoryLot> list(UUID shop,UUID product,int page,int size){return jdbc.query("SELECT * FROM catalog.inventory_lots WHERE shop_id=? AND product_id=? ORDER BY expires_on,id LIMIT ? OFFSET ?",this::map,shop,product,size,(long)page*size);}
    @Override public long count(UUID shop,UUID product){return jdbc.queryForObject("SELECT count(*) FROM catalog.inventory_lots WHERE shop_id=? AND product_id=?",Long.class,shop,product);}
    @Override public Optional<InventoryLot> lockOwned(UUID shop,UUID product,UUID lot){return jdbc.query("SELECT * FROM catalog.inventory_lots WHERE shop_id=? AND product_id=? AND id=? FOR UPDATE",this::map,shop,product,lot).stream().findFirst();}
    @Override public Optional<Result> claim(Key key){
        boolean locked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",Boolean.class,"inventory:"+key.actor()+":"+key.operation()+":"+key.key()));
        if(!locked)throw new CatalogFailure(409,"IDEMPOTENCY_IN_PROGRESS","Request is already being processed.");
        jdbc.update("DELETE FROM catalog.inventory_requests WHERE actor_id=? AND operation=? AND key=? AND expires_at<=now()",key.actor(),key.operation(),key.key());
        int n=jdbc.update("INSERT INTO catalog.inventory_requests(actor_id,shop_id,product_id,operation,key,request_hash,state) VALUES(?,?,?,?,?,?,'IN_PROGRESS') ON CONFLICT(actor_id,operation,key) DO NOTHING",key.actor(),key.shopId(),key.productId(),key.operation(),key.key(),key.hash());
        if(n==1)return Optional.empty();
        return jdbc.query("SELECT * FROM catalog.inventory_requests WHERE actor_id=? AND operation=? AND key=?",(r,i)->{
            if(!key.shopId().equals(r.getObject("shop_id",UUID.class)))throw new CatalogFailure(404,"LOT_NOT_FOUND","Lot was not found.");
            if(!key.productId().equals(r.getObject("product_id",UUID.class))||!key.hash().equals(r.getString("request_hash")))throw new CatalogFailure(422,"IDEMPOTENCY_PAYLOAD_MISMATCH","Key was used for another request.");
            if(!r.getString("state").equals("COMPLETED"))throw new CatalogFailure(409,"IDEMPOTENCY_IN_PROGRESS","Request is already being processed.");
            return new Result(r.getInt("http_status"),json.readValue(r.getString("response_snapshot"),InventoryLot.View.class));
        },key.actor(),key.operation(),key.key()).stream().findFirst();
    }
    @Override public void insert(InventoryLot lot){
        try{jdbc.update("INSERT INTO catalog.inventory_lots(id,product_id,shop_id,sku_id,code,produced_on,expires_on,on_hand,held,allocated,state,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                lot.id(),lot.productId(),lot.shopId(),lot.skuId(),lot.code(),lot.producedOn(),lot.expiresOn(),lot.onHand(),lot.held(),lot.allocated(),lot.state(),lot.version(),Timestamp.from(lot.createdAt()),Timestamp.from(lot.updatedAt()));}
        catch(DuplicateKeyException e){throw new CatalogFailure(409,"LOT_CODE_CONFLICT","Lot code is already used for this SKU.");}
    }
    @Override public void update(InventoryLot lot,long expected){
        int n=jdbc.update("UPDATE catalog.inventory_lots SET state=?,version=?,updated_at=? WHERE id=? AND product_id=? AND shop_id=? AND version=?",lot.state(),lot.version(),Timestamp.from(lot.updatedAt()),lot.id(),lot.productId(),lot.shopId(),expected);
        if(n!=1)throw new CatalogFailure(409,"STALE_VERSION","Lot version has changed.");
    }
    @Override public void audit(InventoryLot lot,UUID actor,String action,String trace){jdbc.update("INSERT INTO catalog.inventory_audit(id,actor_id,lot_id,product_id,shop_id,action,quantity_delta,lot_version,trace_id) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),actor,lot.id(),lot.productId(),lot.shopId(),action,action.equals("CREATE_LOT")?lot.onHand():0,lot.version(),UUID.fromString(trace));}
    @Override public void complete(Key key,Result result){
        int n=jdbc.update("UPDATE catalog.inventory_requests SET state='COMPLETED',http_status=?,response_snapshot=?::jsonb,lot_id=? WHERE actor_id=? AND shop_id=? AND operation=? AND key=? AND state='IN_PROGRESS'",result.status(),json.writeValueAsString(result.lot()),result.lot().id(),key.actor(),key.shopId(),key.operation(),key.key());
        if(n!=1)throw new IllegalStateException("Inventory claim was lost.");
    }
    @Scheduled(fixedDelay=3600000,initialDelay=3600000)
    public void expireResults(){jdbc.update("DELETE FROM catalog.inventory_requests WHERE expires_at<=now()");}
}
