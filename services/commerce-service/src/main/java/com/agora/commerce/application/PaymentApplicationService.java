package com.agora.commerce.application;

import com.agora.commerce.domain.*;
import com.agora.commerce.domain.CaptureApplication.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class PaymentApplicationService {
    private final PaymentStore store;private final ReservationSettlement catalog;
    public PaymentApplicationService(PaymentStore store,ReservationSettlement catalog){this.store=store;this.catalog=catalog;}
    public OwnedOrder payable(UUID buyer,UUID order){return storage(()->{
        var owned=store.order(buyer,order);Checkout.paymentAmount(owned.order().totalVnd());
        if(!owned.order().state().equals("AWAITING_PAYMENT"))throw notPayable();
        var reservation=catalog.get(owned);store.observe(owned,reservation.state(),reservation.consumedCapture());
        if(!reservation.state().equals("HELD"))throw notPayable();
        var current=store.order(buyer,order);if(!current.order().state().equals("AWAITING_PAYMENT"))throw notPayable();return current;
    });}
    public record ReviewLine(UUID buyerId,UUID orderId,UUID skuId,UUID productId,UUID shopId) {}
    public ReviewLine reviewLine(UUID buyer,UUID order,UUID sku){return storage(()->{
        var owned=store.order(buyer,order);var value=owned.order();
        if(!value.state().equals("PAID"))throw new CartFailure(409,"REVIEW_NOT_ELIGIBLE","Only paid order lines can be reviewed.");
        var item=value.items().stream().filter(i->i.skuId().equals(sku)).findFirst().orElseThrow(Checkout::missing);
        return new ReviewLine(buyer,order,sku,item.productId(),value.shopId());
    });}
    public Receipt apply(Request request){return storage(()->{
        var receipt=store.record(request);if(receipt.state().equals("PROCESSING"))process(receipt.captureId());
        return store.receipt(request.buyerId(),request.orderId(),request.captureId());
    });}
    public Receipt get(UUID buyer,UUID order,UUID capture){return storage(()->store.receipt(buyer,order,capture));}
    private void process(UUID capture){
        try{store.acquire(capture).ifPresent(work->{
            try{
                var result=catalog.consume(work.order(),capture);
                boolean conflict=result.state().equals("CONSUMED")&&!capture.equals(result.consumedCapture());
                store.complete(work,conflict?"UNKNOWN":result.state(),conflict);
            }catch(CartFailure e){if(e.status()!=503)throw e;if(e.code().equals("CAPTURE_CONFLICT"))store.complete(work,"UNKNOWN",true);}
        });}catch(DataAccessException|TransactionException e){warn(e);}
    }
    @Scheduled(fixedDelay=10000,initialDelay=10000)
    public void recover(){
        try{
            for(UUID capture:store.pending())process(capture);
            for(var order:store.due()){
                try{var result=catalog.get(order);store.observe(order,result.state(),result.consumedCapture());
                }catch(CartFailure e){if(e.status()!=503)throw e;}
            }
        }catch(DataAccessException|TransactionException e){warn(e);}
    }
    private static CartFailure notPayable(){return new CartFailure(409,"ORDER_NOT_PAYABLE","Order is not available for a new payment.");}
    private static void warn(RuntimeException e){org.slf4j.LoggerFactory.getLogger(PaymentApplicationService.class).warn("Capture reconciliation remains pending type={}",e.getClass().getSimpleName());}
    private <T>T storage(Supplier<T> action){try{return action.get();}catch(DataAccessException|TransactionException e){throw new CartFailure(503,"COMMERCE_UNAVAILABLE","Payment application storage is unavailable.");}}
}
