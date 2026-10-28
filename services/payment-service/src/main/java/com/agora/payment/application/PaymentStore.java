package com.agora.payment.application;
import com.agora.payment.domain.Payment.*;
import java.util.*;
public interface PaymentStore {
    Optional<Attempt> byKey(UUID actor,UUID key);
    Attempt byId(UUID actor,UUID id);
    Optional<Target> target(String txnRef);
    String recordNotice(Target target,Notice notice);
    void reconcile(Target target);
    java.util.List<UUID> pending();
    Optional<Work> acquire(UUID capture);
    void complete(Work work,Application result);
    Created create(UUID actor,UUID key,Payable order,String ip);
}
