package com.agora.commerce;

import com.agora.commerce.application.*;
import com.agora.commerce.domain.*;
import com.agora.commerce.domain.CaptureApplication.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class PaymentRecoveryTests {
    @Test void foreignConsumedIdentityCannotDispatchAnotherOrdersCapture(){
        var store=mock(PaymentStore.class);var catalog=mock(ReservationSettlement.class);
        var order=new OwnedOrder(UUID.randomUUID(),UUID.randomUUID(),new Checkout.Order(UUID.randomUUID(),"AWAITING_PAYMENT",UUID.randomUUID(),"Shop","VND",70000,Instant.now(),Instant.now(),false,List.of()));
        UUID foreignCapture=UUID.randomUUID();
        var otherOrder=new OwnedOrder(UUID.randomUUID(),UUID.randomUUID(),new Checkout.Order(UUID.randomUUID(),"PAYMENT_PROCESSING",UUID.randomUUID(),"Other shop","VND",70000,Instant.now(),Instant.now(),false,List.of()));
        var receipt=new Receipt(foreignCapture,otherOrder.buyerId(),otherOrder.order().id(),"PROCESSING",null,false);
        when(store.pending()).thenReturn(List.of());when(store.due()).thenReturn(List.of(order));
        when(catalog.get(order)).thenReturn(new ReservationSettlement.Result("CONSUMED",foreignCapture));
        when(store.acquire(foreignCapture)).thenReturn(Optional.of(new Work(receipt,1,otherOrder)));
        when(catalog.consume(otherOrder,foreignCapture)).thenReturn(new ReservationSettlement.Result("HELD",null));
        new PaymentApplicationService(store,catalog).recover();
        verify(catalog,never()).consume(otherOrder,foreignCapture);
        verify(store).observe(order,"CONSUMED",foreignCapture);
    }
}
