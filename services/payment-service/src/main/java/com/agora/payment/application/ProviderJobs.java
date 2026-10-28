package com.agora.payment.application;
import com.agora.payment.domain.Payment.*;
import java.time.Instant;
import java.util.*;
public interface ProviderJobs {
    record QueryWork(Target target,Instant originalCreated,UUID requestId,Instant requestedAt,long revision){}
    record RefundWork(Capture capture,String txnRef,Instant originalCreated,UUID requestId,Instant requestedAt,long revision,String providerTransactionNo){}
    List<UUID> pendingQueries();
    Optional<QueryWork> acquireQuery(UUID attempt);
    void completeQuery(QueryWork work,ProviderResult result);
    void scheduleQuery(Target target);
    List<UUID> pendingRefunds();
    Optional<RefundWork> acquireRefund(UUID capture);
    void completeRefund(RefundWork work,ProviderResult result);
}
