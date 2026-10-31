package com.agora.commerce;
import com.agora.commerce.application.*;
import com.agora.commerce.domain.*;
import com.agora.commerce.domain.CaptureApplication.OwnedOrder;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReviewEligibilityTests {
    final PaymentStore store=mock(PaymentStore.class);
    final ReservationSettlement settlement=mock(ReservationSettlement.class);
    final PaymentApplicationService service=new PaymentApplicationService(store,settlement);
    final UUID buyer=UUID.randomUUID(),order=UUID.randomUUID(),sku=UUID.randomUUID(),product=UUID.randomUUID(),shop=UUID.randomUUID();
    void order(String state){var item=new Checkout.Item(product,sku,"Fish","Pack",1000,90000,1,90000);var value=new Checkout.Order(order,state,shop,"Shop","VND",90000,Instant.now(),Instant.now().minusSeconds(60),true,List.of(item));when(store.order(buyer,order)).thenReturn(new OwnedOrder(buyer,UUID.randomUUID(),value));}
    @Test void paidLineRemainsEligibleAfterOriginalHoldExpires(){order("PAID");var line=service.reviewLine(buyer,order,sku);assertThat(line.productId()).isEqualTo(product);assertThat(line.shopId()).isEqualTo(shop);assertThat(line.buyerId()).isEqualTo(buyer);assertThat(line.orderId()).isEqualTo(order);assertThat(line.skuId()).isEqualTo(sku);verifyNoInteractions(settlement);}
    @Test void eachNonPaidStateIsRejected(){for(String state:List.of("AWAITING_PAYMENT","PAYMENT_PROCESSING","EXPIRED")){order(state);assertThatThrownBy(()->service.reviewLine(buyer,order,sku)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(409));}verifyNoInteractions(settlement);}
    @Test void skuMustBelongToPaidOrder(){order("PAID");assertThatThrownBy(()->service.reviewLine(buyer,order,UUID.randomUUID())).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(404));}
    @Test void foreignOrMissingOrderIsNotFound(){when(store.order(buyer,order)).thenThrow(Checkout.missing());assertThatThrownBy(()->service.reviewLine(buyer,order,sku)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(404));}
    @Test void databaseFailureFailsClosed(){when(store.order(buyer,order)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private"));assertThatThrownBy(()->service.reviewLine(buyer,order,sku)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(503));}
}
