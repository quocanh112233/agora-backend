package com.agora.payment.application;
import com.agora.payment.domain.*;
import com.agora.payment.domain.Payment.Target;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
@Service
public class PaymentNotifications {
    private final PaymentProvider provider;private final PaymentStore store;private final ProviderJobs jobs;
    public PaymentNotifications(PaymentProvider provider,PaymentStore store,ProviderJobs jobs){this.provider=provider;this.store=store;this.jobs=jobs;}
    public String ipn(String raw){
        try {
            PaymentProvider.Authenticated ref;
            try{ref=provider.authenticate(raw);}catch(IllegalArgumentException e){return "97";}
            var target=store.target(ref.txnRef());if(target.isEmpty())return "01";
            Payment.Notice notice;try{notice=ref.bind(target.get().amountVnd());}catch(IllegalArgumentException e){store.reconcile(target.get());return "04";}
            return store.recordNotice(target.get(),notice);
        }catch(PaymentFailure|DataAccessException|TransactionException e){return "99";}
    }
    public Target returned(String raw){
        try{var ref=provider.authenticate(raw);var target=store.target(ref.txnRef()).orElseThrow(()->new PaymentFailure(400,"BAD_REQUEST","Invalid payment return."));ref.bindReturn(target.amountVnd());jobs.scheduleQuery(target);return target;}
        catch(IllegalArgumentException e){throw new PaymentFailure(400,"BAD_REQUEST","Invalid payment return.");}
        catch(DataAccessException|TransactionException e){throw Payment.unavailable();}
    }
}
