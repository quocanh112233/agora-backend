package com.agora.commerce;

import com.agora.commerce.application.*;
import com.agora.commerce.domain.*;
import com.agora.commerce.domain.CaptureApplication.*;
import com.agora.contracts.catalog.v1.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties="spring.config.import=")
@Import(CartApiTests.RemoteConfiguration.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CommercePaymentTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);p.add("agora.internal-token",()->CartRpcFixture.TOKEN);}
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired PaymentApplicationService payments;@Autowired PaymentStore store;@Autowired CheckoutStore checkout;@Autowired CheckoutService service;
    @Autowired JdbcTemplate jdbc;@Autowired JsonMapper json;@Autowired CartRpcFixture rpc;@Autowired javax.sql.DataSource dataSource;
    UUID buyer,order,checkoutId;Request request;
    @BeforeEach void setup(){
        jdbc.execute("TRUNCATE commerce.capture_applications,commerce.checkout_attempts,commerce.orders,commerce.checkout_previews,commerce.cart_requests,commerce.cart_items,commerce.carts");rpc.resetCheckout();rpc.offers.clear();
        buyer=UUID.randomUUID();var offer=rpc.offer();UUID sku=UUID.fromString(offer.getSkuId()),product=UUID.fromString(offer.getProductId());
        jdbc.update("INSERT INTO commerce.carts(account_id,version) VALUES(?,1)",buyer);
        jdbc.update("INSERT INTO commerce.cart_items(account_id,sku_id,product_id,quantity) VALUES(?,?,?,2)",buyer,sku,product);
        var preview=service.preview(buyer,UUID.randomUUID(),"a".repeat(64),CartService.cartId(buyer),1,UUID.fromString(offer.getShopId()),List.of(sku));
        var attempt=service.confirm(buyer,UUID.randomUUID(),preview.id());order=attempt.orderId();checkoutId=attempt.checkoutId();request=capture();
    }
    Request capture(){return new Request(UUID.randomUUID(),UUID.randomUUID(),buyer,order,"123456789",70000,"VND");}
    ReservationResponse held(){return rpc.decisions.get(checkoutId.toString()).getReservation();}
    void released(){rpc.settlementOverride.set(held().toBuilder().setState(ReservationState.RELEASED).setReleaseReason(ReservationReleaseReason.UNAVAILABLE).clearConsumedCaptureId().clearConsumedAtEpochMicros().build());}
    void retry(){jdbc.update("UPDATE commerce.capture_applications SET lease_until=clock_timestamp()-interval '1 second' WHERE state='PROCESSING'");}
    String state(){return checkout.order(buyer,order).state();}
    @Test void reviewEligibilityUsesPaidSnapshotAndOwnedLine(){
        var original=checkout.order(buyer,order);var item=original.items().getFirst();
        assertThatThrownBy(()->payments.reviewLine(buyer,order,item.skuId())).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(409));
        payments.apply(request);var line=payments.reviewLine(buyer,order,item.skuId());assertThat(line.productId()).isEqualTo(item.productId());assertThat(line.shopId()).isEqualTo(original.shopId());
        assertThatThrownBy(()->payments.reviewLine(UUID.randomUUID(),order,item.skuId())).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(()->payments.reviewLine(buyer,order,UUID.randomUUID())).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(404));
        jdbc.update("UPDATE commerce.orders SET expires_at=clock_timestamp()-interval '1 day' WHERE id=?",order);assertThat(payments.reviewLine(buyer,order,item.skuId())).isEqualTo(line);
    }
    @Test void captureIsDurableBeforeNetworkThenPaidAndReplayIsPermanent(){
        rpc.consumeHook=()->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((com.zaxxer.hikari.HikariDataSource)dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(store.receipt(buyer,order,request.captureId()).state()).isEqualTo("PROCESSING");assertThat(state()).isEqualTo("PAYMENT_PROCESSING");
        };
        var receipt=payments.apply(request);assertThat(receipt.state()).isEqualTo("APPLIED");assertThat(state()).isEqualTo("PAID");
        rpc.settlementFailure.set(io.grpc.Status.UNAVAILABLE);assertThat(payments.apply(request)).isEqualTo(receipt);assertThat(rpc.consumeRequests).hasSize(1);
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='commerce'",String.class)).doesNotContain("ledger_entries","refunds");
    }
    @Test void lostConsumeReplyNeverRefundsAndRecoveryUsesSameCapture(){
        rpc.afterConsumeFailure.set(io.grpc.Status.UNAVAILABLE);assertThat(payments.apply(request).state()).isEqualTo("PROCESSING");assertThat(state()).isEqualTo("PAYMENT_PROCESSING");
        rpc.afterConsumeFailure.set(null);retry();payments.recover();assertThat(payments.get(buyer,order,request.captureId()).state()).isEqualTo("APPLIED");assertThat(state()).isEqualTo("PAID");
        assertThat(rpc.consumeRequests).hasSize(2);assertThat(rpc.consumeRequests.getFirst()).isEqualTo(rpc.consumeRequests.getLast());
    }
    @Test void catalogReleasedExpiresOrderAndLateCaptureCannotReopen(){
        released();jdbc.update("UPDATE commerce.orders SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",order);payments.recover();assertThat(state()).isEqualTo("EXPIRED");
        assertThat(payments.apply(request).reason()).isEqualTo("ORDER_EXPIRED");assertThat(state()).isEqualTo("EXPIRED");assertThat(rpc.consumeRequests).isEmpty();
    }
    @Test void onlyDefinitiveReleasedProducesTypedRefund(){
        released();var receipt=payments.apply(request);assertThat(receipt.state()).isEqualTo("REFUND_REQUIRED");assertThat(receipt.reason()).isEqualTo("RESERVATION_RELEASED");assertThat(state()).isEqualTo("EXPIRED");
    }
    @Test void notFoundAndTransportErrorsKeepMoneyIdentityAndWinner(){
        for(var status:List.of(io.grpc.Status.NOT_FOUND,io.grpc.Status.UNAVAILABLE,io.grpc.Status.ALREADY_EXISTS)){
            rpc.settlementFailure.set(status);retry();assertThat(payments.apply(request).state()).isEqualTo("PROCESSING");assertThat(state()).isEqualTo("PAYMENT_PROCESSING");
        }
        assertThat(payments.get(buyer,order,request.captureId()).needsReconciliation()).isTrue();
        assertThat(payments.apply(capture()).reason()).isEqualTo("DUPLICATE_CAPTURE");
        assertThat(jdbc.queryForObject("SELECT winner_capture_id FROM commerce.orders WHERE id=?",UUID.class,order)).isEqualTo(request.captureId());
    }
    @Test void foreignConsumedCaptureStaysProcessingWithReconciliation(){
        rpc.settlementOverride.set(held().toBuilder().setState(ReservationState.CONSUMED).setConsumedCaptureId(UUID.randomUUID().toString()).setConsumedAtEpochMicros(held().getCreatedAtEpochMicros()+1).build());
        assertThat(payments.apply(request).needsReconciliation()).isTrue();assertThat(state()).isEqualTo("PAYMENT_PROCESSING");
    }
    @Test void captureHashAndOpaqueIdentityAreOwnerBound(){
        payments.apply(request);
        assertThatThrownBy(()->payments.get(UUID.randomUUID(),order,request.captureId())).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(()->payments.get(buyer,UUID.randomUUID(),request.captureId())).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(()->payments.apply(new Request(request.captureId(),request.attemptId(),UUID.randomUUID(),order,"123456789",70000,"VND"))).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(()->payments.apply(new Request(request.captureId(),UUID.randomUUID(),buyer,order,"123456789",70000,"VND"))).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(422));
        assertThatThrownBy(()->payments.get(buyer,order,UUID.randomUUID())).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(404));
    }
    @Test void wrongAmountHasReceiptWithoutWinnerOrConsume(){
        var wrong=new Request(request.captureId(),request.attemptId(),buyer,order,"123456789",69999,"VND");assertThat(payments.apply(wrong).reason()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(state()).isEqualTo("AWAITING_PAYMENT");assertThat(jdbc.queryForObject("SELECT winner_capture_id FROM commerce.orders WHERE id=?",UUID.class,order)).isNull();assertThat(rpc.consumeRequests).isEmpty();
    }
    @Test void secondCaptureOnPaidOrderHasIndependentRefundReceipt(){
        payments.apply(request);var second=capture();assertThat(payments.apply(second).reason()).isEqualTo("DUPLICATE_CAPTURE");assertThat(state()).isEqualTo("PAID");assertThat(payments.get(buyer,order,request.captureId()).state()).isEqualTo("APPLIED");
    }
    @Test void concurrentCapturesChooseOnePermanentWinner()throws Exception{
        var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);rpc.consumeHook=()->{entered.countDown();try{assertThat(proceed.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new AssertionError(e);}};
        var first=CompletableFuture.supplyAsync(()->payments.apply(request));
        try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(payments.apply(capture()).reason()).isEqualTo("DUPLICATE_CAPTURE");assertThat(payments.apply(request).state()).isEqualTo("PROCESSING");}finally{proceed.countDown();}
        assertThat(first.get(5,TimeUnit.SECONDS).state()).isEqualTo("APPLIED");assertThat(rpc.consumeRequests).hasSize(1);
    }
    @Test void staleLeaseAndCompletionRollbackCannotCorruptPaidOrReceipt(){
        store.record(request);var old=store.acquire(request.captureId()).orElseThrow();retry();var current=store.acquire(request.captureId()).orElseThrow();
        store.complete(old,"RELEASED",false);assertThat(state()).isEqualTo("PAYMENT_PROCESSING");
        jdbc.execute("ALTER TABLE commerce.capture_applications ADD CONSTRAINT injected_failure CHECK(state!='APPLIED') NOT VALID");
        try{assertThatThrownBy(()->store.complete(current,"CONSUMED",false)).isInstanceOf(org.springframework.dao.DataAccessException.class);}finally{jdbc.execute("ALTER TABLE commerce.capture_applications DROP CONSTRAINT injected_failure");}
        assertThat(state()).isEqualTo("PAYMENT_PROCESSING");assertThat(payments.get(buyer,order,request.captureId()).state()).isEqualTo("PROCESSING");
        store.complete(current,"CONSUMED",false);store.complete(old,"RELEASED",false);assertThat(state()).isEqualTo("PAID");
    }
    @Test void payableRequiresFreshHeldAndOwnedNonterminalOrder(){
        rpc.getHook=()->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(((com.zaxxer.hikari.HikariDataSource)dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();};
        assertThat(payments.payable(buyer,order).order().totalVnd()).isEqualTo(70000);
        assertThatThrownBy(()->payments.payable(UUID.randomUUID(),order)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(404));
        rpc.settlementFailure.set(io.grpc.Status.UNAVAILABLE);assertThatThrownBy(()->payments.payable(buyer,order)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(503));assertThat(state()).isEqualTo("AWAITING_PAYMENT");
        rpc.settlementFailure.set(null);released();assertThatThrownBy(()->payments.payable(buyer,order)).isInstanceOf(CartFailure.class);assertThat(state()).isEqualTo("EXPIRED");
    }
    @Test void consumedDuringExpiryRecoveryNeverExpiresAndPaidRemainsTerminal(){
        rpc.afterConsumeFailure.set(io.grpc.Status.UNAVAILABLE);payments.apply(request);rpc.afterConsumeFailure.set(null);retry();
        jdbc.update("UPDATE commerce.orders SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",order);payments.recover();assertThat(state()).isEqualTo("PAID");
        released();payments.recover();assertThat(state()).isEqualTo("PAID");
    }
    @Test void malformedSettlementMetadataCannotMarkPaidOrRefund(){
        var h=held();var invalid=List.of(h.toBuilder().setConsumedCaptureId(request.captureId().toString()).build(),h.toBuilder().setState(ReservationState.CONSUMED).build(),h.toBuilder().setState(ReservationState.CONSUMED).setConsumedCaptureId(request.captureId().toString()).setConsumedAtEpochMicros(h.getExpiresAtEpochMicros()).build(),h.toBuilder().setState(ReservationState.RELEASED).build(),h.toBuilder().setShopId(UUID.randomUUID().toString()).build(),h.toBuilder().setExpiresAtEpochMicros(h.getExpiresAtEpochMicros()+1).build());
        for(var bad:invalid){rpc.settlementOverride.set(bad);retry();assertThat(payments.apply(request).state()).isEqualTo("PROCESSING");assertThat(state()).isEqualTo("PAYMENT_PROCESSING");}
    }
    @Test void workerCatalogHeldAndUnknownNeverExpireFromLocalDeadline(){
        jdbc.update("UPDATE commerce.orders SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",order);
        payments.recover();assertThat(state()).isEqualTo("AWAITING_PAYMENT");
        rpc.settlementFailure.set(io.grpc.Status.UNAVAILABLE);jdbc.update("UPDATE commerce.orders SET reconciliation_after='-infinity' WHERE id=?",order);payments.recover();assertThat(state()).isEqualTo("AWAITING_PAYMENT");
        assertThat(checkout.orders(buyer,null).items().getFirst().state()).isEqualTo("AWAITING_PAYMENT");
    }
    @Test void consumeAndExpiryRacePreservesPaidTerminal()throws Exception {
        var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        rpc.consumeHook=()->{entered.countDown();try{assertThat(proceed.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new AssertionError(e);}};
        var future=CompletableFuture.supplyAsync(()->payments.apply(request));
        try{
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();jdbc.update("UPDATE commerce.orders SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",order);
            payments.recover();assertThat(state()).isEqualTo("PAYMENT_PROCESSING");
        }finally{proceed.countDown();}
        assertThat(future.get(5,TimeUnit.SECONDS).state()).isEqualTo("APPLIED");
        store.observe(store.order(buyer,order),"RELEASED",null);assertThat(state()).isEqualTo("PAID");
    }
    @Test void recordingFailureRollsBackWinnerAndRecoveryAfterCommittedReceiptWorks(){
        jdbc.execute("ALTER TABLE commerce.orders ADD CONSTRAINT injected_failure CHECK(state!='PAYMENT_PROCESSING') NOT VALID");
        try{assertThatThrownBy(()->payments.apply(request)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(503));}finally{jdbc.execute("ALTER TABLE commerce.orders DROP CONSTRAINT injected_failure");}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.capture_applications",Integer.class)).isZero();assertThat(state()).isEqualTo("AWAITING_PAYMENT");assertThat(rpc.consumeRequests).isEmpty();
        store.record(request);payments.recover();assertThat(payments.get(buyer,order,request.captureId()).state()).isEqualTo("APPLIED");
    }
    @Test void payableRefusesConsumedEvenWhenLocalOrderHasNotSettled(){
        rpc.settlementOverride.set(held().toBuilder().setState(ReservationState.CONSUMED).setConsumedCaptureId(UUID.randomUUID().toString()).setConsumedAtEpochMicros(held().getCreatedAtEpochMicros()+1).build());
        assertThatThrownBy(()->payments.payable(buyer,order)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.status()).isEqualTo(409));assertThat(state()).isEqualTo("AWAITING_PAYMENT");
    }
    @Test void amountRangeIncludesExactLimitsAndLegacyOrdersRemainReadable(){
        for(long amount:List.of(5000L,9999999999L))Checkout.paymentAmount(amount);
        for(long amount:List.of(0L,4999L,10000000000L))assertThatThrownBy(()->Checkout.paymentAmount(amount)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.code()).isEqualTo("PAYMENT_AMOUNT_UNSUPPORTED"));
        jdbc.update("UPDATE commerce.orders SET snapshot=jsonb_set(snapshot,'{totalVnd}','4999') WHERE id=?",order);
        assertThat(checkout.order(buyer,order).totalVnd()).isEqualTo(4999);assertThatThrownBy(()->payments.payable(buyer,order)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.code()).isEqualTo("PAYMENT_AMOUNT_UNSUPPORTED"));
    }

    @Test void lostLeaseAfterRemoteConsumeCannotCompleteUntilFreshRecovery(){
        rpc.consumeHook=()->jdbc.update("UPDATE commerce.capture_applications SET lease_until=clock_timestamp()-interval '1 second' WHERE capture_id=?",request.captureId());
        assertThat(payments.apply(request).state()).isEqualTo("PROCESSING");assertThat(state()).isEqualTo("PAYMENT_PROCESSING");
        rpc.consumeHook=()->{};payments.recover();assertThat(state()).isEqualTo("PAID");assertThat(rpc.consumeRequests).hasSize(2);
    }
    @Test void completionSqlFailureAfterRemoteConsumeRemainsRecoverable(){
        jdbc.execute("ALTER TABLE commerce.capture_applications ADD CONSTRAINT injected_failure CHECK(state!='APPLIED') NOT VALID");
        try{assertThat(payments.apply(request).state()).isEqualTo("PROCESSING");assertThat(state()).isEqualTo("PAYMENT_PROCESSING");}finally{jdbc.execute("ALTER TABLE commerce.capture_applications DROP CONSTRAINT injected_failure");}
        retry();payments.recover();assertThat(state()).isEqualTo("PAID");assertThat(payments.get(buyer,order,request.captureId()).state()).isEqualTo("APPLIED");
    }
    @Test void settlementRejectsUnknownStateItemBindingAllocationAndForbiddenMetadata(){
        var h=held();var item=h.getItems(0);var invalid=List.of(
            h.toBuilder().setStateValue(99).build(),h.toBuilder().setReleaseReason(ReservationReleaseReason.CANCELLED).build(),
            h.toBuilder().setBuyerId(UUID.randomUUID().toString()).build(),h.toBuilder().setCheckoutId(UUID.randomUUID().toString()).build(),
            h.toBuilder().clearItems().build(),h.toBuilder().addItems(item).build(),h.toBuilder().setItems(0,item.toBuilder().setProductId(UUID.randomUUID().toString())).build(),
            h.toBuilder().setItems(0,item.toBuilder().setQuantity(1)).build(),h.toBuilder().setItems(0,item.toBuilder().clearLots()).build(),
            h.toBuilder().setItems(0,item.toBuilder().setLots(0,item.getLots(0).toBuilder().setQuantity(0))).build(),
            h.toBuilder().setItems(0,item.toBuilder().setLots(0,item.getLots(0).toBuilder().setLotId("1-1-1-1-1"))).build(),
            h.toBuilder().setItems(0,item.toBuilder().clearLots().addLots(item.getLots(0).toBuilder().setQuantity(1)).addLots(item.getLots(0).toBuilder().setQuantity(1))).build(),
            h.toBuilder().setState(ReservationState.RELEASED).setReleaseReason(ReservationReleaseReason.UNAVAILABLE).setConsumedCaptureId(request.captureId().toString()).build(),
            h.toBuilder().setState(ReservationState.CONSUMED).setConsumedCaptureId(request.captureId().toString()).setConsumedAtEpochMicros(h.getCreatedAtEpochMicros()+1).setReleaseReason(ReservationReleaseReason.EXPIRED).build());
        for(var bad:invalid){rpc.settlementOverride.set(bad);retry();assertThat(payments.apply(request).state()).isEqualTo("PROCESSING");assertThat(state()).isEqualTo("PAYMENT_PROCESSING");}
    }

    @Test void internalListenerIsDisabledByDefault(){assertThat(context.getBeansOfType(com.agora.commerce.configuration.CommerceGrpcServer.class)).isEmpty();}

    @Test void expiredWinnerIsPermanentAndEveryNewCaptureGetsItsOwnReceipt(){
        released();payments.apply(request);var second=capture();assertThat(payments.apply(second).reason()).isEqualTo("ORDER_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT winner_capture_id FROM commerce.orders WHERE id=?",UUID.class,order)).isEqualTo(request.captureId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.capture_applications WHERE order_id=?",Integer.class,order)).isEqualTo(2);
        assertThat(payments.apply(request).reason()).isEqualTo("RESERVATION_RELEASED");assertThat(state()).isEqualTo("EXPIRED");assertThat(rpc.consumeRequests).hasSize(1);
    }
    @Test void captureReplayCannotChangeVerifiedTransactionOrAmount(){
        payments.apply(request);
        for(var changed:List.of(new Request(request.captureId(),request.attemptId(),buyer,order,"987654321",70000,"VND"),new Request(request.captureId(),request.attemptId(),buyer,order,"123456789",70001,"VND")))
            assertThatThrownBy(()->payments.apply(changed)).isInstanceOfSatisfying(CartFailure.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_MISMATCH"));
        assertThat(state()).isEqualTo("PAID");assertThat(rpc.consumeRequests).hasSize(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.capture_applications WHERE order_id=?",Integer.class,order)).isEqualTo(1);
    }

    @Test void counterConflictRequiresReconciliationWithoutRefundOrWinnerLoss(){
        rpc.settlementFailure.set(io.grpc.Status.FAILED_PRECONDITION);
        var receipt=payments.apply(request);assertThat(receipt.state()).isEqualTo("PROCESSING");assertThat(receipt.needsReconciliation()).isTrue();assertThat(state()).isEqualTo("PAYMENT_PROCESSING");
        assertThat(jdbc.queryForObject("SELECT winner_capture_id FROM commerce.orders WHERE id=?",UUID.class,order)).isEqualTo(request.captureId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.capture_applications WHERE state='REFUND_REQUIRED'",Integer.class)).isZero();
    }

}
