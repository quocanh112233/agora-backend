package com.agora.payment.application;
import com.agora.payment.domain.Payment.ProviderResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="agora.vnpay.query-enabled",havingValue="true")
public class ProviderReconciliation {
    private final ProviderJobs jobs;private final PaymentProvider provider;
    public ProviderReconciliation(ProviderJobs jobs,PaymentProvider provider){this.jobs=jobs;this.provider=provider;}
    @Scheduled(fixedDelay=10000,initialDelayString="${agora.payment.worker-initial-delay-ms:10000}")
    public void recover(){
        try {
            for(var id:jobs.pendingQueries())try{jobs.acquireQuery(id).ifPresent(work->{ProviderResult result;try{result=provider.query(work);}catch(RuntimeException e){result=ProviderResult.unknown();}jobs.completeQuery(work,result);});}catch(RuntimeException e){warn(e);}
            for(var id:jobs.pendingRefunds())try{jobs.acquireRefund(id).ifPresent(work->{ProviderResult result;try{result=provider.refund(work);}catch(RuntimeException e){result=ProviderResult.unknown();}jobs.completeRefund(work,result);});}catch(RuntimeException e){warn(e);}
        }catch(RuntimeException e){warn(e);}
    }
    private void warn(RuntimeException e){org.slf4j.LoggerFactory.getLogger(ProviderReconciliation.class).warn("Provider reconciliation remains pending type={}",e.getClass().getSimpleName());}
}
