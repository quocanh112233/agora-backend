package com.agora.commerce.adapter.outbound;

import com.agora.commerce.application.*;
import com.agora.commerce.domain.*;
import com.agora.commerce.domain.CaptureApplication.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPaymentStore implements PaymentStore {
    private final JdbcTemplate jdbc;private final CheckoutStore checkout;
    public JdbcPaymentStore(JdbcTemplate jdbc,CheckoutStore checkout){this.jdbc=jdbc;this.checkout=checkout;}
    private Receipt read(ResultSet r)throws SQLException{return new Receipt(r.getObject("capture_id",UUID.class),r.getObject("buyer_id",UUID.class),r.getObject("order_id",UUID.class),r.getString("state"),r.getString("reason"),r.getBoolean("needs_reconciliation"));}
    private void lock(UUID buyer,UUID order){
        if(jdbc.query("SELECT id FROM commerce.orders WHERE id=? AND actor_id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),order,buyer).isEmpty())throw Checkout.missing();
    }
    @Override @Transactional(readOnly=true)
    public OwnedOrder order(UUID buyer,UUID id){
        var order=checkout.order(buyer,id);return new OwnedOrder(buyer,jdbc.queryForObject("SELECT checkout_id FROM commerce.orders WHERE id=?",UUID.class,id),order);
    }
    @Override @Transactional(readOnly=true)
    public Receipt receipt(UUID buyer,UUID order,UUID capture){return jdbc.query("SELECT * FROM commerce.capture_applications WHERE capture_id=? AND buyer_id=? AND order_id=?",(r,n)->read(r),capture,buyer,order).stream().findFirst().orElseThrow(Checkout::missing);}
    @Override @Transactional(timeout=10)
    public Receipt record(Request request){
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"commerce-capture:"+request.captureId());
        lock(request.buyerId(),request.orderId());
        var existing=jdbc.query("SELECT * FROM commerce.capture_applications WHERE capture_id=?",(r,n)->{
            if(!request.buyerId().equals(r.getObject("buyer_id",UUID.class))||!request.orderId().equals(r.getObject("order_id",UUID.class)))throw Checkout.missing();
            if(!request.hash().equals(r.getString("request_hash")))throw new CartFailure(422,"IDEMPOTENCY_MISMATCH","Capture identity was already used for another request.");
            return read(r);
        },request.captureId());
        if(!existing.isEmpty())return existing.getFirst();
        var owned=order(request.buyerId(),request.orderId());var o=owned.order();
        UUID winner=jdbc.queryForObject("SELECT winner_capture_id FROM commerce.orders WHERE id=?",UUID.class,o.id());
        String reason=o.state().equals("EXPIRED")?"ORDER_EXPIRED":o.state().equals("PAID")?"DUPLICATE_CAPTURE":
            o.totalVnd()!=request.amountVnd()||!o.currency().equals(request.currency())?"AMOUNT_MISMATCH":winner!=null?"DUPLICATE_CAPTURE":null;
        String state=reason==null?"PROCESSING":"REFUND_REQUIRED";
        jdbc.update("INSERT INTO commerce.capture_applications(capture_id,request_hash,buyer_id,order_id,attempt_id,provider_transaction_no,amount_vnd,currency,state,reason) VALUES(?,?,?,?,?,?,?,?,?,?)",request.captureId(),request.hash(),request.buyerId(),request.orderId(),request.attemptId(),request.providerTransactionNo(),request.amountVnd(),request.currency(),state,reason);
        if(reason==null)jdbc.update("UPDATE commerce.orders SET state='PAYMENT_PROCESSING',winner_capture_id=? WHERE id=?",request.captureId(),o.id());
        return receipt(request.buyerId(),request.orderId(),request.captureId());
    }
    @Override @Transactional(timeout=10)
    public Optional<Work> acquire(UUID capture){
        return jdbc.query("UPDATE commerce.capture_applications SET revision=revision+1,lease_until=clock_timestamp()+interval '30 seconds' WHERE capture_id=? AND state='PROCESSING' AND lease_until<=clock_timestamp() RETURNING *",(r,n)->new Work(read(r),r.getLong("revision"),order(r.getObject("buyer_id",UUID.class),r.getObject("order_id",UUID.class))),capture).stream().findFirst();
    }
    @Override @Transactional(timeout=10)
    public void complete(Work work,String catalogState,boolean reconciliation){
        var receipt=work.receipt();lock(receipt.buyerId(),receipt.orderId());
        var revisions=jdbc.query("SELECT revision FROM commerce.capture_applications WHERE capture_id=? AND state='PROCESSING' AND lease_until>clock_timestamp() FOR UPDATE",(r,n)->r.getLong(1),receipt.captureId());
        if(revisions.isEmpty()||revisions.getFirst()!=work.revision())return;
        String state=jdbc.queryForObject("SELECT state FROM commerce.orders WHERE id=?",String.class,receipt.orderId());
        UUID winner=jdbc.queryForObject("SELECT winner_capture_id FROM commerce.orders WHERE id=?",UUID.class,receipt.orderId());
        if(!receipt.captureId().equals(winner))throw new IllegalStateException("Capture winner mismatch.");
        if(catalogState.equals("CONSUMED")&&!state.equals("EXPIRED")){
            jdbc.update("UPDATE commerce.orders SET state='PAID' WHERE id=?",receipt.orderId());
            jdbc.update("UPDATE commerce.capture_applications SET state='APPLIED',needs_reconciliation=false WHERE capture_id=?",receipt.captureId());
        }else if(catalogState.equals("RELEASED")&&!state.equals("PAID")){
            jdbc.update("UPDATE commerce.orders SET state='EXPIRED' WHERE id=?",receipt.orderId());
            jdbc.update("UPDATE commerce.capture_applications SET state='REFUND_REQUIRED',reason='RESERVATION_RELEASED',needs_reconciliation=false WHERE capture_id=?",receipt.captureId());
        }else if(reconciliation)jdbc.update("UPDATE commerce.capture_applications SET needs_reconciliation=true WHERE capture_id=?",receipt.captureId());
    }
    @Override @Transactional(timeout=10)
    public void observe(OwnedOrder owned,String catalogState,UUID consumedCapture){
        var id=owned.order().id();lock(owned.buyerId(),id);
        String state=jdbc.queryForObject("SELECT state FROM commerce.orders WHERE id=?",String.class,id);
        if(state.equals("PAID")||state.equals("EXPIRED"))return;
        if(catalogState.equals("RELEASED")){
            jdbc.update("UPDATE commerce.orders SET state='EXPIRED' WHERE id=?",id);
            jdbc.update("UPDATE commerce.capture_applications SET state='REFUND_REQUIRED',reason='RESERVATION_RELEASED',needs_reconciliation=false WHERE capture_id=(SELECT winner_capture_id FROM commerce.orders WHERE id=?) AND state='PROCESSING'",id);
        }else if(catalogState.equals("CONSUMED")){
            jdbc.update("UPDATE commerce.capture_applications SET needs_reconciliation=true WHERE order_id=? AND state='PROCESSING' AND capture_id<>?",id,consumedCapture);
        }
    }
    @Override @Transactional(readOnly=true)
    public List<UUID> pending(){return jdbc.query("SELECT capture_id FROM commerce.capture_applications WHERE state='PROCESSING' AND lease_until<=clock_timestamp() ORDER BY lease_until,capture_id LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
    @Override @Transactional(timeout=10)
    public List<OwnedOrder> due(){
        return jdbc.query("UPDATE commerce.orders SET reconciliation_after=clock_timestamp()+interval '10 seconds' WHERE id IN (SELECT id FROM commerce.orders WHERE state IN ('AWAITING_PAYMENT','PAYMENT_PROCESSING') AND expires_at<=clock_timestamp() AND reconciliation_after<=clock_timestamp() ORDER BY reconciliation_after,expires_at,id FOR UPDATE SKIP LOCKED LIMIT 20) RETURNING actor_id,id",(r,n)->order(r.getObject(1,UUID.class),r.getObject(2,UUID.class)));
    }
}
