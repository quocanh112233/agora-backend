package com.agora.payment.application;
import com.agora.payment.domain.*;
import com.agora.payment.domain.Payment.*;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
@Service
public class PaymentService {
    private final PaymentStore store;private final PaymentOrders orders;
    public PaymentService(PaymentStore store,PaymentOrders orders){this.store=store;this.orders=orders;}
    public Created create(UUID actor,UUID key,UUID order,String ip){return storage(()->{
        var prior=store.byKey(actor,key);if(prior.isPresent()){if(!prior.get().orderId().equals(order))throw new PaymentFailure(422,"IDEMPOTENCY_MISMATCH","Request key belongs to another order.");return new Created(prior.get(),false);}
        return store.create(actor,key,orders.payable(actor,order),ip);
    });}
    public Attempt byKey(UUID actor,UUID key){return storage(()->store.byKey(actor,key).orElseThrow(Payment::missing));}
    public Attempt byId(UUID actor,UUID id){return storage(()->store.byId(actor,id));}
    private <T>T storage(Supplier<T> action){try{return action.get();}catch(DataAccessException|TransactionException e){throw Payment.unavailable();}}
}
