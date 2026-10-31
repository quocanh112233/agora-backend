package com.agora.payment.adapter.outbound;
import com.agora.payment.application.PaymentStore;
import com.agora.payment.domain.*;
import com.agora.payment.domain.Payment.*;
import java.net.URI;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public class JdbcPaymentStore implements PaymentStore {
    private final JdbcTemplate jdbc;
    private final boolean enabled,queryEnabled;
    private final String merchant,secret,returnUrl;
    public JdbcPaymentStore(JdbcTemplate jdbc,@Value("${agora.vnpay.query-enabled:false}") boolean queryEnabled,@Value("${agora.vnpay.enabled:false}") boolean enabled,@Value("${agora.vnpay.tmn-code:}") String merchant,@Value("${agora.vnpay.hash-secret:}") String secret,@Value("${agora.vnpay.return-url}") String returnUrl){this.jdbc=jdbc;this.queryEnabled=queryEnabled;this.enabled=enabled;this.merchant=merchant;this.secret=secret;this.returnUrl=returnUrl;}
    private Attempt read(ResultSet r)throws SQLException {
        String state=r.getString("state");boolean live=r.getBoolean("live");
        return new Attempt(r.getObject("id",UUID.class),r.getObject("order_id",UUID.class),r.getObject("request_key",UUID.class),state.equals("PENDING")&&!live?"EXPIRED":state,r.getTimestamp("expires_at").toInstant(),state.equals("PENDING")&&live&&r.getBoolean("can_pay")?r.getString("redirect_url"):null,r.getString("application_state"),r.getString("refund_state"),r.getBoolean("reconciliation_required"));
    }
    private static final String SELECT="SELECT a.*,(a.needs_reconciliation OR EXISTS(SELECT 1 FROM payment.captures flagged WHERE flagged.attempt_id=a.id AND flagged.needs_reconciliation)) AS reconciliation_required,expires_at>clock_timestamp() AS live,NOT EXISTS(SELECT 1 FROM payment.captures paid WHERE paid.order_id=a.order_id) AS can_pay,(SELECT CASE WHEN bool_or(c.application_state='REFUND_REQUIRED') THEN 'REFUND_REQUIRED' WHEN bool_or(c.application_state='PROCESSING') THEN 'PROCESSING' ELSE 'APPLIED' END FROM payment.captures c WHERE c.attempt_id=a.id HAVING count(*)>0) AS application_state,(SELECT CASE WHEN bool_or(f.state='NEEDS_RECONCILIATION') THEN 'NEEDS_RECONCILIATION' WHEN bool_and(f.state='SUCCEEDED') THEN 'SUCCEEDED' WHEN bool_or(f.state='PENDING') THEN 'PENDING' ELSE 'RESERVED' END FROM payment.refunds f JOIN payment.captures c ON c.id=f.capture_id WHERE c.attempt_id=a.id HAVING count(*)>0) AS refund_state FROM payment.attempts a ";
    @Override @Transactional(readOnly=true) public Optional<Attempt> byKey(UUID actor,UUID key){return jdbc.query(SELECT+"WHERE actor_id=? AND request_key=?",(r,n)->read(r),actor,key).stream().findFirst();}
    @Override @Transactional(readOnly=true) public Attempt byId(UUID actor,UUID id){return jdbc.query(SELECT+"WHERE actor_id=? AND id=?",(r,n)->read(r),actor,id).stream().findFirst().orElseThrow(Payment::missing);}
    @Override @Transactional(timeout=10) public Created create(UUID actor,UUID key,Payable order,String ip){
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"payment-actor:"+actor);
        var prior=byKey(actor,key);if(prior.isPresent()){if(!prior.get().orderId().equals(order.orderId()))throw new PaymentFailure(422,"IDEMPOTENCY_MISMATCH","Request key belongs to another order.");return new Created(prior.get(),false);}
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment.attempts WHERE order_id=? AND (state IN ('PENDING','EXPIRED','SUCCEEDED') OR needs_reconciliation))",Boolean.class,order.orderId())))throw Payment.conflict();
        if(!enabled)throw Payment.unavailable();
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();if(!order.expiresAt().isAfter(now.plusSeconds(1)))throw Payment.conflict();
        UUID id=UUID.randomUUID();String txn=id.toString().replace("-","");VnpayCodec.Redirect redirect;
        try{redirect=new VnpayCodec(merchant,secret,URI.create(returnUrl)).redirect(txn,order.amountVnd(),now,order.expiresAt(),ip);}catch(IllegalArgumentException e){throw Payment.unavailable();}
        jdbc.update("INSERT INTO payment.attempts(id,actor_id,order_id,request_key,checkout_id,shop_id,txn_ref,amount_vnd,currency,state,created_at,expires_at,ip_address,redirect_url) VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?)",id,actor,order.orderId(),key,order.checkoutId(),order.shopId(),txn,order.amountVnd(),"VND",Timestamp.from(now),Timestamp.from(order.expiresAt()),ip,redirect.url().toString());
        if(queryEnabled)jdbc.update("INSERT INTO payment.provider_queries(attempt_id,next_run_at) VALUES(?,?)",id,Timestamp.from(order.expiresAt().plusSeconds(5)));
        return new Created(byId(actor,id),true);
    }
    @Override @Transactional(readOnly=true) public Optional<Target> target(String txnRef){
        return jdbc.query("SELECT id,actor_id,order_id,request_key,txn_ref,amount_vnd FROM payment.attempts WHERE txn_ref=?",(r,n)->new Target(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getString(5),r.getLong(6)),txnRef).stream().findFirst();
    }
    private void actorLock(UUID actor){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"payment-actor:"+actor);}
    private void wake(UUID... attempts){Arrays.stream(attempts).distinct().sorted().forEach(id->jdbc.update("INSERT INTO payment.query_wakeups(attempt_id) VALUES(?) ON CONFLICT DO NOTHING",id));}
    @Override @Transactional(timeout=10) public void reconcile(Target target){actorLock(target.actorId());jdbc.update("UPDATE payment.attempts SET needs_reconciliation=true WHERE id=?",target.id());wake(target.id());}
    @Override @Transactional(timeout=10) public String recordNotice(Target target,Notice notice){
        actorLock(target.actorId());
        String transaction=notice.transactionNo().replaceFirst("^0+(?!$)","");
        if(notice.outcome().equals("SUCCEEDED"))jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"payment-provider:"+merchant+":"+transaction);
        String state=jdbc.queryForObject("SELECT state FROM payment.attempts WHERE id=? AND actor_id=? FOR NO KEY UPDATE",String.class,target.id(),target.actorId());
        if(notice.outcome().equals("SUCCEEDED")){
            var existing=jdbc.query("SELECT attempt_id FROM payment.captures WHERE merchant=? AND provider_transaction_no=?",(r,n)->r.getObject(1,UUID.class),merchant,transaction);
            if(!existing.isEmpty()){
                if(existing.getFirst().equals(target.id()))return "02";
                jdbc.update("UPDATE payment.attempts SET needs_reconciliation=true WHERE id=?",target.id());
                jdbc.update("UPDATE payment.captures SET needs_reconciliation=true WHERE merchant=? AND provider_transaction_no=?",merchant,transaction);wake(target.id(),existing.getFirst());return "99";
            }
            UUID capture=UUID.randomUUID();
            jdbc.update("INSERT INTO payment.captures(id,attempt_id,actor_id,order_id,merchant,provider_transaction_no,amount_vnd,currency) VALUES(?,?,?,?,?,?,?,'VND')",capture,target.id(),target.actorId(),target.orderId(),merchant,transaction,target.amountVnd());
            jdbc.update("INSERT INTO payment.ledger_entries(capture_id,kind,amount_vnd) VALUES(?,'CAPTURE',?)",capture,target.amountVnd());
            jdbc.update("INSERT INTO payment.capture_jobs(capture_id) VALUES(?)",capture);
            jdbc.update("UPDATE payment.attempts SET state='SUCCEEDED' WHERE id=?",target.id());
        }else if(notice.outcome().equals("FAILED")&&!state.equals("SUCCEEDED"))jdbc.update("UPDATE payment.attempts SET state='FAILED' WHERE id=?",target.id());
        else if(notice.outcome().equals("UNKNOWN")){jdbc.update("UPDATE payment.attempts SET needs_reconciliation=true WHERE id=?",target.id());wake(target.id());}
        jdbc.update("INSERT INTO payment.observations(attempt_id,transaction_no,response_code,transaction_status) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",target.id(),transaction,notice.responseCode(),notice.transactionStatus());
        return "00";
    }

    @Override @Transactional(readOnly=true) public List<UUID> pending(){return jdbc.query("SELECT capture_id FROM payment.capture_jobs WHERE state='PENDING' AND lease_until<=clock_timestamp() ORDER BY lease_until,capture_id LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
    @Override @Transactional(timeout=10) public Optional<Work> acquire(UUID capture){
        var revisions=jdbc.query("UPDATE payment.capture_jobs SET revision=revision+1,lease_until=clock_timestamp()+interval '30 seconds' WHERE capture_id=? AND state='PENDING' AND lease_until<=clock_timestamp() RETURNING revision",(r,n)->r.getLong(1),capture);
        if(revisions.isEmpty())return Optional.empty();
        return Optional.of(jdbc.queryForObject("SELECT * FROM payment.captures WHERE id=?",(r,n)->new Work(new Capture(r.getObject("id",UUID.class),r.getObject("attempt_id",UUID.class),r.getObject("actor_id",UUID.class),r.getObject("order_id",UUID.class),r.getString("provider_transaction_no"),r.getLong("amount_vnd")),revisions.getFirst()),capture));
    }
    @Override @Transactional(timeout=10) public void complete(Work work,Application result){
        var capture=work.capture();actorLock(capture.actorId());
        var revisions=jdbc.query("SELECT revision FROM payment.capture_jobs WHERE capture_id=? AND state='PENDING' AND lease_until>clock_timestamp() FOR UPDATE",(r,n)->r.getLong(1),capture.id());
        if(revisions.isEmpty()||revisions.getFirst()!=work.revision())return;
        jdbc.update("UPDATE payment.captures SET application_state=?,needs_reconciliation=needs_reconciliation OR ? WHERE id=?",result.state(),result.needsReconciliation(),capture.id());
        if(result.needsReconciliation())jdbc.update("UPDATE payment.attempts SET needs_reconciliation=true WHERE id=?",capture.attemptId());
        if(result.state().equals("REFUND_REQUIRED")){
            jdbc.update("INSERT INTO payment.refunds(capture_id,amount_vnd,request_id) VALUES(?,?,?) ON CONFLICT(capture_id) DO NOTHING",capture.id(),capture.amountVnd(),UUID.randomUUID());
            jdbc.update("INSERT INTO payment.ledger_entries(capture_id,kind,amount_vnd) VALUES(?,'REFUND_RESERVED',?) ON CONFLICT DO NOTHING",capture.id(),capture.amountVnd());
        }
        if(!result.state().equals("PROCESSING"))jdbc.update("UPDATE payment.capture_jobs SET state='DONE' WHERE capture_id=?",capture.id());
    }

}
