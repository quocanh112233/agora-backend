package com.agora.catalog.adapter.outbound;

import com.agora.catalog.application.ReservationStore;
import com.agora.catalog.application.CheckoutStore;
import com.agora.catalog.domain.Checkout.*;
import com.agora.catalog.domain.*;
import com.agora.catalog.domain.Reservation.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcReservationStore implements ReservationStore,CheckoutStore {
    private static final ZoneId VIETNAM=ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public JdbcReservationStore(JdbcTemplate jdbc,PlatformTransactionManager transactions,Clock clock){
        this.jdbc=jdbc;this.clock=clock;transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }
    private <T> T tx(Supplier<T> operation){return transaction.execute(status->{
        jdbc.execute("SET LOCAL lock_timeout='2s'");jdbc.execute("SET LOCAL statement_timeout='5s'");
        return operation.get();
    });}
    private Instant databaseInstant(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private Instant now(Header h){return h.notAfter()==null?instant():databaseInstant();}
    private Instant now(Request request){return request.accepted()?databaseInstant():instant();}
    private Instant deadline(Request request){long micros=request.notAfterEpochMicros();return Instant.ofEpochSecond(micros/1000000,(micros%1000000)*1000);}
    private void checkDeadline(Request request){if(request.accepted()&&!databaseInstant().isBefore(deadline(request)))throw new CatalogFailure(409,"CHECKOUT_EXPIRED","Checkout has expired.");}
    private Instant instant(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    private void lockIdentity(UUID checkout){
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},"reservation:"+checkout);
    }
    private record Header(UUID checkout,UUID buyer,UUID shop,String hash,String state,Instant created,Instant expires,String reason,Timestamp notAfter,UUID capture,Timestamp consumedAt){}
    private Optional<Header> header(UUID checkout){
        lockIdentity(checkout);
        return jdbc.query("SELECT * FROM catalog.reservations WHERE checkout_id=? FOR UPDATE",(r,i)->new Header(
                r.getObject("checkout_id",UUID.class),r.getObject("buyer_id",UUID.class),r.getObject("shop_id",UUID.class),r.getString("request_hash"),
                r.getString("state"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),r.getString("release_reason"),r.getTimestamp("accepted_not_after"),r.getObject("consumed_capture_id",UUID.class),r.getTimestamp("consumed_at")),checkout).stream().findFirst();
    }
    private Header owned(UUID buyer,UUID checkout){var h=header(checkout).orElseThrow(JdbcReservationStore::missing);if(!h.buyer().equals(buyer))throw missing();return h;}
    private Optional<Reservation> replayLocked(Request request){
        var found=header(request.checkoutId());if(found.isEmpty()){var closed=decisionLocked(request);if(closed.isPresent())throw new CatalogFailure(409,"CHECKOUT_CLOSED","Checkout is closed.");return Optional.empty();}var h=found.get();
        if(!h.buyer().equals(request.buyerId()))throw missing();
        if(!h.hash().equals(request.hash()))throw new CatalogFailure(422,"IDEMPOTENCY_PAYLOAD_MISMATCH","Checkout was used for another request.");
        return Optional.of(current(h,false));
    }
    @Override public Optional<Reservation> replay(Request request){return tx(()->replayLocked(request));}
    @Override public Reservation get(UUID buyer,UUID checkout){return tx(()->current(owned(buyer,checkout),false));}
    @Override public Reservation release(UUID buyer,UUID checkout){return tx(()->current(owned(buyer,checkout),true));}

    private record Lot(UUID id,UUID sku,LocalDate expires,String state,long available){}
    @Override public Reservation create(Request request){return tx(()->{
        var replay=replayLocked(request);if(replay.isPresent())return replay.get();
        return createLocked(request);
    });}
    private Reservation createLocked(Request request){
        var products=request.lines().stream().map(Line::productId).distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        for(var product:products){
            var found=jdbc.query("SELECT id FROM catalog.products WHERE id=? FOR UPDATE",(r,i)->r.getObject(1,UUID.class),product);
            if(found.isEmpty())throw unavailable();
        }
        for(var line:request.lines()){
            boolean eligible=Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM catalog.products p JOIN catalog.skus s ON s.product_id=p.id AND s.shop_id=p.shop_id
                    WHERE p.id=? AND p.shop_id=? AND p.status='PUBLISHED' AND s.id=?
                    AND EXISTS(SELECT 1 FROM catalog.product_images pi JOIN catalog.image_uploads u ON u.id=pi.upload_id
                               WHERE pi.product_id=p.id AND u.state='UPLOADED'))
                    """,Boolean.class,line.productId(),request.shopId(),line.skuId()));
            if(!eligible)throw unavailable();
            if(request.accepted()){
                var accepted=request.acceptedLines().get(line.skuId());
                boolean matches=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM catalog.products p JOIN catalog.skus s ON s.product_id=p.id WHERE p.id=? AND s.id=? AND p.version=? AND s.price_vnd=?)",Boolean.class,line.productId(),line.skuId(),accepted.productVersion(),accepted.unitPriceVnd()));
                if(!matches)throw unavailable();
            }
        }
        var args=new ArrayList<Object>();args.add(request.shopId());args.add(LocalDate.ofInstant(now(request),VIETNAM));request.lines().forEach(line->args.add(line.skuId()));
        String placeholders=String.join(",",Collections.nCopies(request.lines().size(),"?"));
        // Include depleted/blocked lots: they can become eligible while this statement waits for a lock.
        // shortcut: cap all unexpired candidates at 1000; partition inventory if requests outgrow this bound.
        var lots=jdbc.query("SELECT id,sku_id,expires_on,state,on_hand-held-allocated AS available FROM catalog.inventory_lots WHERE shop_id=? AND expires_on>? AND sku_id IN ("+placeholders+") ORDER BY id LIMIT 1001 FOR UPDATE",
                (r,i)->new Lot(r.getObject("id",UUID.class),r.getObject("sku_id",UUID.class),r.getDate("expires_on").toLocalDate(),r.getString("state"),r.getLong("available")),args.toArray());
        if(lots.size()>1000)throw new CatalogFailure(429,"RESERVATION_RESOURCE_LIMIT","Reservation candidate lot limit exceeded.");
        checkDeadline(request);
        Instant now=now(request);LocalDate today=LocalDate.ofInstant(now,VIETNAM);
        var eligible=lots.stream().filter(lot->lot.state().equals("ACTIVE")&&lot.available()>0&&lot.expires().isAfter(today)).sorted(Comparator.comparing(Lot::expires).thenComparing(lot->lot.id().toString())).toList();
        var items=new ArrayList<Item>();
        for(var line:request.lines()){
            int remaining=line.quantity();var allocations=new ArrayList<Allocation>();
            for(var lot:eligible){
                if(!lot.sku().equals(line.skuId()))continue;
                int quantity=(int)Math.min(remaining,lot.available());allocations.add(new Allocation(lot.id(),quantity));remaining-=quantity;
                if(remaining==0)break;
            }
            if(remaining!=0)throw new CatalogFailure(409,"INSUFFICIENT_STOCK","Insufficient eligible stock.");
            items.add(new Item(line.productId(),line.skuId(),line.quantity(),allocations));
        }
        jdbc.update("INSERT INTO catalog.reservations(checkout_id,buyer_id,shop_id,request_hash,state,created_at,expires_at) VALUES(?,?,?,?,'HELD',?,?)",
                request.checkoutId(),request.buyerId(),request.shopId(),request.hash(),Timestamp.from(now),Timestamp.from(now.plusSeconds(900)));
        if(request.accepted())jdbc.update("UPDATE catalog.reservations SET accepted_not_after=? WHERE checkout_id=?",Timestamp.from(deadline(request)),request.checkoutId());
        for(var item:items){
            jdbc.update("INSERT INTO catalog.reservation_lines(checkout_id,product_id,sku_id,quantity) VALUES(?,?,?,?)",request.checkoutId(),item.productId(),item.skuId(),item.quantity());
            for(var allocation:item.allocations()){
                jdbc.update("INSERT INTO catalog.reservation_allocations(checkout_id,sku_id,lot_id,quantity) VALUES(?,?,?,?)",request.checkoutId(),item.skuId(),allocation.lotId(),allocation.quantity());
                adjust(request.checkoutId(),item.skuId(),allocation,allocation.quantity(),"HOLD",now);
            }
        }
        return new Reservation(request.checkoutId(),request.buyerId(),request.shopId(),"HELD",now,now.plusSeconds(900),null,items);
    }
    private Optional<String> decisionLocked(Request request){
        var rows=jdbc.query("SELECT buyer_id,request_hash,closure_reason FROM catalog.checkout_decisions WHERE checkout_id=?",(r,i)->{
            if(!r.getObject("buyer_id",UUID.class).equals(request.buyerId()))throw missing();
            if(!r.getString("request_hash").equals(request.hash()))throw new CatalogFailure(422,"IDEMPOTENCY_PAYLOAD_MISMATCH","Checkout was used for another request.");
            return r.getString("closure_reason");
        },request.checkoutId());
        return rows.stream().findFirst();
    }
    private Optional<Result> checkoutReplayLocked(Request request){
        var found=header(request.checkoutId());
        if(found.isPresent()){
            var h=found.get();if(!h.buyer().equals(request.buyerId()))throw missing();
            if(!h.hash().equals(request.hash()))throw new CatalogFailure(422,"IDEMPOTENCY_PAYLOAD_MISMATCH","Checkout was used for another request.");
            return Optional.of(Result.held(current(h,false)));
        }
        return decisionLocked(request).map(Result::closed);
    }
    private Result closeLocked(Request request,String reason){
        jdbc.update("INSERT INTO catalog.checkout_decisions(checkout_id,buyer_id,shop_id,request_hash,closure_reason,closed_at) VALUES(?,?,?,?,?,clock_timestamp())",request.checkoutId(),request.buyerId(),request.shopId(),request.hash(),reason);
        return Result.closed(reason);
    }
    @Override public Optional<Result> replayCheckout(Request request){return tx(()->{
        var replay=checkoutReplayLocked(request);if(replay.isPresent())return replay;
        if(!databaseInstant().isBefore(deadline(request)))return Optional.of(closeLocked(request,"CHECKOUT_EXPIRED"));
        return Optional.empty();
    });}
    @Override public Result holdCheckout(Request request,boolean shopActive){return transaction.execute(status->{
        jdbc.execute("SET LOCAL lock_timeout='2s'");jdbc.execute("SET LOCAL statement_timeout='5s'");
        var replay=checkoutReplayLocked(request);if(replay.isPresent())return replay.get();
        if(!databaseInstant().isBefore(deadline(request)))return closeLocked(request,"CHECKOUT_EXPIRED");
        if(!shopActive)return closeLocked(request,"OFFER_UNAVAILABLE");
        // Identity lock precedes the savepoint so business rollback cannot release it.
        Object savepoint=status.createSavepoint();
        try{
            var result=Result.held(createLocked(request));status.releaseSavepoint(savepoint);return result;
        }catch(CatalogFailure failure){
            if(failure.status()!=409||!Set.of("PRODUCT_UNAVAILABLE","INSUFFICIENT_STOCK","RESERVATION_STOCK_CONFLICT","CHECKOUT_EXPIRED").contains(failure.code()))throw failure;
            status.rollbackToSavepoint(savepoint);status.releaseSavepoint(savepoint);
            return closeLocked(request,failure.code().equals("CHECKOUT_EXPIRED")?"CHECKOUT_EXPIRED":"OFFER_UNAVAILABLE");
        }
    });}
    @Override public Quote quote(QuoteRequest request){return tx(()->{
        var args=new ArrayList<Object>();args.add(request.shopId());request.lines().forEach(line->args.add(line.skuId()));
        String placeholders=String.join(",",Collections.nCopies(request.lines().size(),"?"));
        var rows=jdbc.query("""
            WITH t AS MATERIALIZED (SELECT clock_timestamp() AS n)
            SELECT p.id AS product_id,s.id AS sku_id,p.name,s.label,s.price_vnd,s.net_weight_grams,p.version,t.n+interval '5 minutes' AS expires
            FROM catalog.products p JOIN catalog.skus s ON s.product_id=p.id AND s.shop_id=p.shop_id CROSS JOIN t
            WHERE p.shop_id=? AND p.status='PUBLISHED' AND s.id IN (%s)
            AND EXISTS(SELECT 1 FROM catalog.product_images pi JOIN catalog.image_uploads u ON u.id=pi.upload_id AND u.product_id=pi.product_id AND u.shop_id=pi.shop_id WHERE pi.product_id=p.id AND u.state='UPLOADED')
            AND EXISTS(SELECT 1 FROM catalog.inventory_lots l WHERE l.sku_id=s.id AND l.state='ACTIVE' AND l.expires_on>(t.n AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AND l.on_hand-l.held-l.allocated>0)
            ORDER BY s.id
            """.formatted(placeholders),(r,i)->{
                UUID sku=r.getObject("sku_id",UUID.class);
                var line=request.lines().stream().filter(l->l.skuId().equals(sku)).findFirst().orElseThrow();
                if(!line.productId().equals(r.getObject("product_id",UUID.class)))throw unavailable();
                return new Quote(request.shopId(),null,List.of(new Offer(line.productId(),line.skuId(),line.quantity(),r.getString("name"),r.getString("label"),r.getLong("price_vnd"),r.getInt("net_weight_grams"),r.getLong("version"))),r.getTimestamp("expires").toInstant());
            },args.toArray());
        if(rows.size()!=request.lines().size())throw unavailable();
        return new Quote(request.shopId(),null,rows.stream().map(q->q.offers().getFirst()).toList(),rows.getFirst().expiresAt());
    });}
    private List<Item> items(UUID checkout){
        return jdbc.query("SELECT * FROM catalog.reservation_lines WHERE checkout_id=? ORDER BY sku_id",(r,i)->{
            UUID sku=r.getObject("sku_id",UUID.class);
            var allocations=jdbc.query("SELECT a.lot_id,a.quantity FROM catalog.reservation_allocations a JOIN catalog.inventory_lots l ON l.id=a.lot_id WHERE a.checkout_id=? AND a.sku_id=? ORDER BY l.expires_on,l.id",
                    (a,n)->new Allocation(a.getObject("lot_id",UUID.class),a.getInt("quantity")),checkout,sku);
            return new Item(r.getObject("product_id",UUID.class),sku,r.getInt("quantity"),allocations);
        },checkout);
    }
    private List<Lot> lockLots(List<Item> items){
        return items.stream().flatMap(item->item.allocations().stream()).sorted(Comparator.comparing(a->a.lotId().toString())).map(a->jdbc.queryForObject(
            "SELECT id,sku_id,expires_on,state,on_hand-held-allocated AS available FROM catalog.inventory_lots WHERE id=? FOR UPDATE",
            (r,i)->new Lot(r.getObject("id",UUID.class),r.getObject("sku_id",UUID.class),r.getDate("expires_on").toLocalDate(),r.getString("state"),r.getLong("available")),a.lotId())).toList();
    }
    private Reservation released(Header h,List<Item> items,String reason,Instant now){
        for(var item:items)for(var allocation:item.allocations())adjust(h.checkout(),item.skuId(),allocation,-allocation.quantity(),reason.equals("EXPIRED")?"EXPIRE":"RELEASE",now);
        jdbc.update("UPDATE catalog.reservations SET state='RELEASED',released_at=?,release_reason=? WHERE checkout_id=?",Timestamp.from(now),reason,h.checkout());
        return new Reservation(h.checkout(),h.buyer(),h.shop(),"RELEASED",h.created(),h.expires(),reason,items);
    }
    private Reservation current(Header h,boolean release){
        var items=items(h.checkout());
        if(h.state().equals("HELD")&&(release||!now(h).isBefore(h.expires()))){
            lockLots(items);Instant now=now(h);
            return released(h,items,now.isBefore(h.expires())?"CANCELLED":"EXPIRED",now);
        }
        return new Reservation(h.checkout(),h.buyer(),h.shop(),h.state(),h.created(),h.expires(),h.reason(),items,h.capture(),h.consumedAt()==null?null:h.consumedAt().toInstant());
    }
    @Override public Reservation consume(UUID buyer,UUID checkout,UUID capture){return tx(()->{
        // Serialize reuse of one financial capture before taking a checkout's existing lock.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},"capture:"+capture);
        var h=owned(buyer,checkout);
        if(h.notAfter()==null)throw new CatalogFailure(409,"RESERVATION_NOT_PAYABLE","Only accepted checkout reservations can be consumed.");
        if(h.state().equals("CONSUMED")){
            if(!capture.equals(h.capture()))throw new CatalogFailure(422,"CAPTURE_MISMATCH","Reservation was consumed by another capture.");
            return current(h,false);
        }
        if(h.state().equals("RELEASED"))return current(h,false);
        if(!jdbc.query("SELECT checkout_id FROM catalog.reservations WHERE consumed_capture_id=?",(r,i)->r.getObject(1,UUID.class),capture).isEmpty())
            throw new CatalogFailure(422,"CAPTURE_ALREADY_USED","Capture was used for another reservation.");
        var items=items(checkout);var lots=lockLots(items);Instant now=databaseInstant();
        if(!now.isBefore(h.expires()))return released(h,items,"EXPIRED",now);
        var today=LocalDate.ofInstant(now,VIETNAM);
        if(lots.stream().anyMatch(l->!l.state().equals("ACTIVE")||!l.expires().isAfter(today)))return released(h,items,"UNAVAILABLE",now);
        for(var item:items)for(var a:item.allocations()){
            int changed=jdbc.update("UPDATE catalog.inventory_lots SET held=held-?,on_hand=on_hand-?,version=version+1,updated_at=? WHERE id=? AND sku_id=? AND held>=? AND on_hand>=? AND version<9007199254740991",
                a.quantity(),a.quantity(),Timestamp.from(now),a.lotId(),item.skuId(),a.quantity(),a.quantity());
            if(changed!=1)throw new CatalogFailure(409,"RESERVATION_STOCK_CONFLICT","Lot counters cannot be consumed.");
            jdbc.update("INSERT INTO catalog.reservation_audit(id,checkout_id,sku_id,lot_id,action,quantity,lot_version,created_at) SELECT ?,?,?,?,'CONSUME',?,version,? FROM catalog.inventory_lots WHERE id=?",
                UUID.randomUUID(),checkout,item.skuId(),a.lotId(),a.quantity(),Timestamp.from(now),a.lotId());
        }
        jdbc.update("UPDATE catalog.reservations SET state='CONSUMED',consumed_capture_id=?,consumed_at=? WHERE checkout_id=?",capture,Timestamp.from(now),checkout);
        return new Reservation(checkout,buyer,h.shop(),"CONSUMED",h.created(),h.expires(),null,items,capture,now);
    });}
    private void adjust(UUID checkout,UUID sku,Allocation allocation,int delta,String action,Instant now){
        int changed=jdbc.update("UPDATE catalog.inventory_lots SET held=held+?,version=version+1,updated_at=? WHERE id=? AND held+? >= 0 AND held+?+allocated <= on_hand AND version<9007199254740991",
                delta,Timestamp.from(now),allocation.lotId(),delta,delta);
        if(changed!=1)throw new CatalogFailure(409,"RESERVATION_STOCK_CONFLICT","Lot counters cannot be updated.");
        jdbc.update("INSERT INTO catalog.reservation_audit(id,checkout_id,sku_id,lot_id,action,quantity,lot_version,created_at) SELECT ?,?,?,?,?,?,version,? FROM catalog.inventory_lots WHERE id=?",
                UUID.randomUUID(),checkout,sku,allocation.lotId(),action,allocation.quantity(),Timestamp.from(now),allocation.lotId());
    }
    @Override public int expire(){
        var due=tx(()->jdbc.query("SELECT checkout_id FROM catalog.reservations WHERE state='HELD' AND expires_at<=CASE WHEN accepted_not_after IS NULL THEN ? ELSE clock_timestamp() END ORDER BY expires_at,checkout_id LIMIT 20",
                (r,i)->r.getObject(1,UUID.class),Timestamp.from(instant())));
        int released=0;
        for(var checkout:due){
            try{
                released+=tx(()->{
                    var found=header(checkout);if(found.isEmpty())return 0;var h=found.get();
                    if(!h.state().equals("HELD")||now(h).isBefore(h.expires()))return 0;
                    return current(h,false).state().equals("RELEASED")?1:0;
                });
            }catch(TransientDataAccessException|DataAccessResourceFailureException|CannotCreateTransactionException|TransactionTimedOutException failure){
                // The reservation transaction has rolled back; let other due identities make progress.
                org.slf4j.LoggerFactory.getLogger(JdbcReservationStore.class).warn("Reservation expiry deferred type={}",failure.getClass().getSimpleName());
            }catch(UncategorizedSQLException failure){
                String state=failure.getSQLException().getSQLState();
                if(!"55P03".equals(state)&&!"57014".equals(state))throw failure;
                org.slf4j.LoggerFactory.getLogger(JdbcReservationStore.class).warn("Reservation expiry deferred type={}",failure.getClass().getSimpleName());
            }
        }
        return released;
    }
    private static CatalogFailure missing(){return new CatalogFailure(404,"RESERVATION_NOT_FOUND","Reservation was not found.");}
    private static CatalogFailure unavailable(){return new CatalogFailure(409,"PRODUCT_UNAVAILABLE","Product or SKU is not available for this shop.");}
}
