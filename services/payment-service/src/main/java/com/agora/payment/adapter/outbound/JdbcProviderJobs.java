package com.agora.payment.adapter.outbound;
import com.agora.payment.application.*;
import com.agora.payment.domain.Payment.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public class JdbcProviderJobs implements ProviderJobs {
    private final JdbcTemplate jdbc;private final PaymentStore payments;
    public JdbcProviderJobs(JdbcTemplate jdbc,PaymentStore payments){this.jdbc=jdbc;this.payments=payments;}
    private void actorLock(UUID actor){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"payment-actor:"+actor);}
    @Override @Transactional(readOnly=true) public List<UUID> pendingQueries(){return jdbc.query("""
        SELECT q.attempt_id FROM payment.provider_queries q JOIN payment.attempts a ON a.id=q.attempt_id
        WHERE q.lease_until<=clock_timestamp() AND (
            EXISTS(SELECT 1 FROM payment.query_wakeups w WHERE w.attempt_id=q.attempt_id)
            OR (NOT q.done AND q.next_run_at<=clock_timestamp() AND (
                q.requested_at IS NOT NULL OR a.state IN ('PENDING','EXPIRED') OR a.needs_reconciliation
                OR EXISTS(SELECT 1 FROM payment.captures flagged WHERE flagged.attempt_id=a.id AND flagged.needs_reconciliation))))
        ORDER BY q.next_run_at,q.attempt_id LIMIT 20
        """,(r,n)->r.getObject(1,UUID.class));}
    @Override @Transactional(timeout=10) public Optional<QueryWork> acquireQuery(UUID attempt){
        var rows=jdbc.query("UPDATE payment.provider_queries q SET done=false,revision=revision+1,request_id=?,requested_at=clock_timestamp(),next_run_at=clock_timestamp(),lease_until=clock_timestamp()+interval '30 seconds' WHERE attempt_id=? AND lease_until<=clock_timestamp() AND ((NOT done AND next_run_at<=clock_timestamp()) OR EXISTS(SELECT 1 FROM payment.query_wakeups w WHERE w.attempt_id=q.attempt_id)) RETURNING revision",(r,n)->r.getLong(1),UUID.randomUUID(),attempt);
        if(rows.isEmpty())return Optional.empty();
        jdbc.update("DELETE FROM payment.query_wakeups WHERE attempt_id=?",attempt);
        return Optional.of(jdbc.queryForObject("SELECT a.*,q.request_id,q.requested_at,q.revision FROM payment.attempts a JOIN payment.provider_queries q ON q.attempt_id=a.id WHERE a.id=?",(r,n)->new QueryWork(new Target(attempt,r.getObject("actor_id",UUID.class),r.getObject("order_id",UUID.class),r.getObject("request_key",UUID.class),r.getString("txn_ref"),r.getLong("amount_vnd")),r.getTimestamp("created_at").toInstant(),r.getObject("request_id",UUID.class),r.getTimestamp("requested_at").toInstant(),r.getLong("revision")),attempt));
    }
    @Override @Transactional(timeout=10) public void completeQuery(QueryWork work,ProviderResult result){
        actorLock(work.target().actorId());
        var revisions=jdbc.query("SELECT revision FROM payment.provider_queries WHERE attempt_id=? AND NOT done AND lease_until>clock_timestamp() FOR UPDATE",(r,n)->r.getLong(1),work.target().id());if(revisions.isEmpty()||revisions.getFirst()!=work.revision())return;
        boolean done=false;
        if(result.succeeded())done=Set.of("00","02").contains(payments.recordNotice(work.target(),new Notice(result.transactionNo(),"00","00","SUCCEEDED")));
        else jdbc.update("UPDATE payment.attempts SET needs_reconciliation=true WHERE id=?",work.target().id());
        jdbc.update("UPDATE payment.provider_queries SET done=?,next_run_at=clock_timestamp()+interval '30 seconds',lease_until=clock_timestamp() WHERE attempt_id=?",done,work.target().id());
    }
    @Override @Transactional(timeout=10) public void scheduleQuery(Target target){jdbc.update("INSERT INTO payment.query_wakeups(attempt_id) VALUES(?) ON CONFLICT DO NOTHING",target.id());}
    @Override @Transactional(readOnly=true) public List<UUID> pendingRefunds(){return jdbc.query("SELECT capture_id FROM payment.refunds WHERE state IN ('RESERVED','PENDING') AND next_run_at<=clock_timestamp() AND lease_until<=clock_timestamp() ORDER BY next_run_at,capture_id LIMIT 20",(r,n)->r.getObject(1,UUID.class));}
    @Override @Transactional(timeout=10) public Optional<RefundWork> acquireRefund(UUID capture){
        var snapshots=jdbc.query("SELECT c.*,a.txn_ref,a.created_at AS original_created FROM payment.captures c JOIN payment.attempts a ON a.id=c.attempt_id WHERE c.id=?",(r,n)->new RefundWork(new Capture(capture,r.getObject("attempt_id",UUID.class),r.getObject("actor_id",UUID.class),r.getObject("order_id",UUID.class),r.getString("provider_transaction_no"),r.getLong("amount_vnd")),r.getString("txn_ref"),r.getTimestamp("original_created").toInstant(),null,null,0,null),capture);
        if(snapshots.isEmpty())return Optional.empty();var snapshot=snapshots.getFirst();actorLock(snapshot.capture().actorId());
        var states=jdbc.query("SELECT state FROM payment.refunds WHERE capture_id=? AND state IN ('RESERVED','PENDING') AND next_run_at<=clock_timestamp() AND lease_until<=clock_timestamp() FOR UPDATE",(r,n)->r.getString(1),capture);if(states.isEmpty())return Optional.empty();
        var work=jdbc.queryForObject("UPDATE payment.refunds SET state='PENDING',requested_at=COALESCE(requested_at,clock_timestamp()),revision=revision+1,lease_until=clock_timestamp()+interval '30 seconds' WHERE capture_id=? RETURNING *",(r,n)->new RefundWork(snapshot.capture(),snapshot.txnRef(),snapshot.originalCreated(),r.getObject("request_id",UUID.class),r.getTimestamp("requested_at").toInstant(),r.getLong("revision"),r.getString("provider_transaction_no")),capture);
        if(states.getFirst().equals("PENDING")&&work.providerTransactionNo()==null){finishRefund(work,ProviderResult.unknown());return Optional.empty();}return Optional.of(work);
    }
    @Override @Transactional(timeout=10) public void completeRefund(RefundWork work,ProviderResult result){actorLock(work.capture().actorId());finishRefund(work,result);}
    private void finishRefund(RefundWork work,ProviderResult result){
        var revisions=jdbc.query("SELECT revision FROM payment.refunds WHERE capture_id=? AND state='PENDING' AND lease_until>clock_timestamp() FOR UPDATE",(r,n)->r.getLong(1),work.capture().id());if(revisions.isEmpty()||revisions.getFirst()!=work.revision())return;
        String state=result.succeeded()?"SUCCEEDED":!result.transactionNo().isEmpty()&&!result.transactionNo().matches("0+")&&Set.of("05","06").contains(result.status())?"PENDING":"NEEDS_RECONCILIATION";
        jdbc.update("UPDATE payment.refunds SET state=?,provider_transaction_no=COALESCE(?,provider_transaction_no),next_run_at=clock_timestamp()+interval '30 seconds' WHERE capture_id=?",state,result.transactionNo().isEmpty()?null:result.transactionNo().replaceFirst("^0+(?!$)",""),work.capture().id());
        if(state.equals("SUCCEEDED"))jdbc.update("INSERT INTO payment.ledger_entries(capture_id,kind,amount_vnd) VALUES(?,'REFUND_SUCCEEDED',?) ON CONFLICT DO NOTHING",work.capture().id(),work.capture().amountVnd());
        if(state.equals("NEEDS_RECONCILIATION")){jdbc.update("UPDATE payment.attempts SET needs_reconciliation=true WHERE id=?",work.capture().attemptId());jdbc.update("UPDATE payment.captures SET needs_reconciliation=true WHERE id=?",work.capture().id());}
    }
}
