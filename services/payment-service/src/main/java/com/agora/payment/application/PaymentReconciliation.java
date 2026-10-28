package com.agora.payment.application;
import com.agora.payment.domain.PaymentFailure;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
@Service
public class PaymentReconciliation {
    private final PaymentStore store;private final PaymentOrders orders;
    public PaymentReconciliation(PaymentStore store,PaymentOrders orders){this.store=store;this.orders=orders;}
    @Scheduled(fixedDelay=10000,initialDelayString="${agora.payment.worker-initial-delay-ms:10000}")
    public void recover(){
        try{for(UUID capture:store.pending()){
            try{store.acquire(capture).ifPresent(work->store.complete(work,orders.apply(work.capture())));}
            catch(PaymentFailure|DataAccessException|TransactionException e){warn(e);}
        }}catch(DataAccessException|TransactionException e){warn(e);}
    }
    private void warn(RuntimeException e){org.slf4j.LoggerFactory.getLogger(PaymentReconciliation.class).warn("Payment application remains pending type={}",e.getClass().getSimpleName());}
}
