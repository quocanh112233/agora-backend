package com.agora.catalog;

import com.agora.catalog.adapter.outbound.JdbcReservationStore;
import com.agora.catalog.adapter.inbound.ReservationRpc;
import com.agora.catalog.adapter.inbound.CheckoutRpc;
import com.agora.catalog.configuration.CatalogGrpcServer;
import com.agora.contracts.catalog.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.agora.catalog.application.*;
import com.agora.catalog.domain.*;
import com.agora.catalog.domain.Reservation.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ReservationConsumptionTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    static DriverManagerDataSource source;static JdbcTemplate jdbc;
    UUID buyer=UUID.randomUUID(),shop=UUID.randomUUID(),product=UUID.randomUUID(),sku=UUID.randomUUID(),lot=UUID.randomUUID();
    CatalogGrpcServer server;ManagedChannel channel;JdbcReservationStore store;
    PublicShops shops;boolean inactive,offline;Clock clock=Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"),ZoneOffset.UTC);
    @BeforeAll static void migrate(){
        source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).defaultSchema("catalog").schemas("catalog").locations("classpath:db/migration").load().migrate();
    }
    @BeforeEach void seed(){seedOffer(product,sku,lot);}
    void seedOffer(UUID product,UUID sku,UUID lot){
        jdbc.update("INSERT INTO catalog.products VALUES(?,?,?,'Description','PRODUCE','FRESH','VN',null,'Cool','PUBLISHED',0,now(),now())",product,shop,"Product");
        jdbc.update("INSERT INTO catalog.skus VALUES(?,?,?,?,'Package',100,100,100,1,1,1,'PACKAGE',0)",sku,product,shop,sku.toString().toUpperCase(Locale.ROOT));
        UUID image=UUID.randomUUID();String id="agora/products/"+product+"/"+image;
        jdbc.update("INSERT INTO catalog.image_uploads(id,actor_id,shop_id,product_id,key,request_hash,public_id,state,mime,bytes,width,height,provider_version,format,url) VALUES(?,?,?,?,?,?,?,'UPLOADED','image/png',68,1,1,1,'png',?)",image,buyer,shop,product,UUID.randomUUID(),"0".repeat(64),id,"https://res.cloudinary.com/test/image/upload/v1/"+id+".png");
        jdbc.update("INSERT INTO catalog.product_images VALUES(?,?,?,0)",image,product,shop);
        jdbc.update("INSERT INTO catalog.inventory_lots(id,product_id,shop_id,sku_id,code,expires_on,on_hand,state,version,created_at,updated_at) VALUES(?,?,?,?,'LOT',current_date+10,3,'ACTIVE',0,now(),now())",lot,product,shop,sku);
    }
    @BeforeEach void start(){
        store=new JdbcReservationStore(jdbc,new DataSourceTransactionManager(source),clock);
        shops=ids->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();if(offline)throw new CatalogFailure(503,"MERCHANT_UNAVAILABLE","private");return inactive?Map.of():Map.of(shop,new PublicShops.Shop(shop,"Shop"));};
        server=new CatalogGrpcServer(org.mockito.Mockito.mock(CatalogOffersService.class),new ReservationRpc(new ReservationService(store,shops)),new CheckoutRpc(new CheckoutService(store,shops)),0,RpcFixture.TOKEN);
        server.start();channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.port()).usePlaintext().disableRetry().maxInboundMessageSize(131072).build();
    }
    @AfterEach void stop()throws Exception{if(channel!=null)channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);if(server!=null)server.stop();}
    CatalogCheckoutGrpc.CatalogCheckoutBlockingStub client(String token){
        var stub=CatalogCheckoutGrpc.newBlockingStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS);
        if(token==null)return stub;var headers=new Metadata();headers.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    CatalogCheckoutGrpc.CatalogCheckoutBlockingStub client(){return client(RpcFixture.TOKEN);}
    QuoteCheckoutRequest quote(){return QuoteCheckoutRequest.newBuilder().setShopId(shop.toString()).addLines(CheckoutLine.newBuilder().setProductId(product.toString()).setSkuId(sku.toString()).setQuantity(2)).build();}
    long dbMicros(){return jdbc.queryForObject("SELECT (extract(epoch FROM clock_timestamp())*1000000)::bigint",Long.class);}
    HoldCheckoutRequest hold(){return HoldCheckoutRequest.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(UUID.randomUUID().toString()).setShopId(shop.toString()).setNotAfterEpochMicros(dbMicros()+300000000).addLines(AcceptedCheckoutLine.newBuilder().setProductId(product.toString()).setSkuId(sku.toString()).setQuantity(2).setUnitPriceVnd(100).setProductVersion(0)).build();}
    void error(Runnable call,Status.Code code){assertThatThrownBy(call::run).isInstanceOfSatisfying(StatusRuntimeException.class,e->{assertThat(e.getStatus().getCode()).isEqualTo(code);assertThat(e.getStatus().getDescription()).isNull();});}
    long held(){return jdbc.queryForObject("SELECT held FROM catalog.inventory_lots WHERE id=?",Long.class,lot);}
    void closed(CheckoutHoldResult result,CheckoutClosureReason reason){assertThat(result.getResultCase()).isEqualTo(CheckoutHoldResult.ResultCase.CLOSED);assertThat(result.getClosed().getReason()).isEqualTo(reason);}
    CatalogReservationsGrpc.CatalogReservationsBlockingStub reservations(){
        var h=new Metadata();h.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),RpcFixture.TOKEN);
        return CatalogReservationsGrpc.newBlockingStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(h));
    }
    ConsumeReservationRequest consumeRequest(HoldCheckoutRequest hold,UUID capture){return ConsumeReservationRequest.newBuilder().setBuyerId(hold.getBuyerId()).setCheckoutId(hold.getCheckoutId()).setCaptureId(capture.toString()).build();}
    @Test void consumesAcceptedHoldAtomicallyAndReplaysWithoutChangingCounters(){
        jdbc.update("UPDATE catalog.inventory_lots SET allocated=1 WHERE id=?",lot);
        var request=hold();var held=client().holdCheckout(request).getReservation();UUID capture=UUID.randomUUID();
        long before=dbMicros();var result=reservations().consume(consumeRequest(request,capture));long after=dbMicros();
        assertThat(jdbc.queryForObject("SELECT allocated FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isOne();
        assertThat(result.getState()).isEqualTo(ReservationState.CONSUMED);assertThat(result.getConsumedCaptureId()).isEqualTo(capture.toString());
        assertThat(result.getConsumedAtEpochMicros()).isBetween(before,after);assertThat(result.getItemsList()).isEqualTo(held.getItemsList());
        assertThat(jdbc.queryForObject("SELECT on_hand FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isOne();assertThat(held()).isZero();
        assertThat(reservations().consume(consumeRequest(request,capture))).isEqualTo(result);
        var ref=ReservationReference.newBuilder().setBuyerId(request.getBuyerId()).setCheckoutId(request.getCheckoutId()).build();
        assertThat(reservations().get(ref)).isEqualTo(result);assertThat(reservations().release(ref)).isEqualTo(result);assertThat(client().holdCheckout(request).getReservation()).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.reservation_audit WHERE checkout_id=? AND action='CONSUME'",Long.class,UUID.fromString(request.getCheckoutId()))).isOne();
        assertThat(jdbc.queryForObject("SELECT version FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isEqualTo(2);
    }
    @Test void rejectsUnauthorizedMalformedOwnersRawHoldsAndCaptureReuse(){
        var r=hold();client().holdCheckout(r);UUID capture=UUID.randomUUID();var c=consumeRequest(r,capture);
        error(()->CatalogReservationsGrpc.newBlockingStub(channel).consume(ConsumeReservationRequest.getDefaultInstance()),Status.Code.PERMISSION_DENIED);
        error(()->reservations().consume(c.toBuilder().setCaptureId("1-1-1-1-1").build()),Status.Code.INVALID_ARGUMENT);
        error(()->reservations().consume(c.toBuilder().setBuyerId(UUID.randomUUID().toString()).build()),Status.Code.NOT_FOUND);
        reservations().consume(c);error(()->reservations().consume(consumeRequest(r,UUID.randomUUID())),Status.Code.ALREADY_EXISTS);
        var second=hold().toBuilder().setLines(0,hold().getLines(0).toBuilder().setProductId(product.toString()).setSkuId(sku.toString()).setQuantity(1)).build();client().holdCheckout(second);
        error(()->reservations().consume(consumeRequest(second,capture)),Status.Code.ALREADY_EXISTS);assertThat(held()).isOne();
        var raw=HoldReservationRequest.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(UUID.randomUUID().toString()).setShopId(shop.toString()).addLines(ReservationLine.newBuilder().setProductId(product.toString()).setSkuId(sku.toString()).setQuantity(1)).build();
        reservations().release(ReservationReference.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(second.getCheckoutId()).build());reservations().hold(raw);
        error(()->reservations().consume(ConsumeReservationRequest.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(raw.getCheckoutId()).setCaptureId(UUID.randomUUID().toString()).build()),Status.Code.FAILED_PRECONDITION);
    }
    @Test void hiddenProductAndMerchantOutageDoNotInvalidateReservedSnapshot(){
        var r=hold();client().holdCheckout(r);jdbc.update("UPDATE catalog.products SET status='HIDDEN',version=version+1 WHERE id=?",product);offline=true;
        assertThat(reservations().consume(consumeRequest(r,UUID.randomUUID())).getState()).isEqualTo(ReservationState.CONSUMED);
    }
    @Test void unavailableLotClosesPermanentlyWithoutReducingOnHand(){
        var r=hold();client().holdCheckout(r);jdbc.update("UPDATE catalog.inventory_lots SET state='BLOCKED' WHERE id=?",lot);
        var request=consumeRequest(r,UUID.randomUUID());var result=reservations().consume(request);
        assertThat(result.getState()).isEqualTo(ReservationState.RELEASED);assertThat(result.getReleaseReason()).isEqualTo(ReservationReleaseReason.UNAVAILABLE);assertThat(held()).isZero();
        assertThat(jdbc.queryForObject("SELECT on_hand FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isEqualTo(3);
        jdbc.update("UPDATE catalog.inventory_lots SET state='ACTIVE' WHERE id=?",lot);assertThat(reservations().consume(request)).isEqualTo(result);
        assertThat(client().holdCheckout(r).getReservation()).isEqualTo(result);
    }
    @Test void expiredLotCannotBeConsumedEvenWhenReservationDeadlineIsFuture(){
        var r=hold();client().holdCheckout(r);jdbc.update("UPDATE catalog.inventory_lots SET expires_on=(clock_timestamp() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date WHERE id=?",lot);
        var result=reservations().consume(consumeRequest(r,UUID.randomUUID()));assertThat(result.getState()).isEqualTo(ReservationState.RELEASED);assertThat(result.getReleaseReason()).isEqualTo(ReservationReleaseReason.UNAVAILABLE);assertThat(held()).isZero();
    }
    @Test void expiryAndCancellationCannotBeRevivedByCapture(){
        var r=hold();client().holdCheckout(r);
        jdbc.update("UPDATE catalog.reservations SET created_at=t.n-interval '16 minutes',expires_at=t.n-interval '1 minute' FROM (SELECT clock_timestamp() n) t WHERE checkout_id=?",UUID.fromString(r.getCheckoutId()));
        var request=consumeRequest(r,UUID.randomUUID());var expired=reservations().consume(request);assertThat(expired.getReleaseReason()).isEqualTo(ReservationReleaseReason.EXPIRED);assertThat(reservations().consume(request)).isEqualTo(expired);assertThat(held()).isZero();
        var other=hold();client().holdCheckout(other);var cancelled=reservations().release(ReservationReference.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(other.getCheckoutId()).build());
        assertThat(reservations().consume(consumeRequest(other,UUID.randomUUID()))).isEqualTo(cancelled);assertThat(held()).isZero();
    }
    void awaitBlocked(String prefix)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?",Integer.class,prefix+"%")>0)return;Thread.sleep(5);}
        fail("Expected blocked database statement");
    }
    @Test void lotLockWaitRechecksDeadlineBeforeConsuming()throws Exception{
        var r=hold();client().holdCheckout(r);jdbc.update("UPDATE catalog.reservations SET created_at=t.n-interval '899.4 seconds',expires_at=t.n+interval '0.6 second' FROM (SELECT clock_timestamp() n) t WHERE checkout_id=?",UUID.fromString(r.getCheckoutId()));
        try(var blocker=source.getConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()){
            blocker.setAutoCommit(false);ReservationStoreTests.lock(blocker,"SELECT id FROM catalog.inventory_lots WHERE id='"+lot+"' FOR UPDATE");
            var call=executor.submit(()->reservations().consume(consumeRequest(r,UUID.randomUUID())));awaitBlocked("SELECT id,sku_id,expires_on");jdbc.execute("SELECT pg_sleep(0.65)");blocker.commit();
            assertThat(call.get(5,TimeUnit.SECONDS).getReleaseReason()).isEqualTo(ReservationReleaseReason.EXPIRED);
        }assertThat(held()).isZero();assertThat(jdbc.queryForObject("SELECT on_hand FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isEqualTo(3);
    }
    @Test void consumeAndReleaseRaceHaveOneTerminalCounterEffect()throws Exception{
        var r=hold();client().holdCheckout(r);UUID capture=UUID.randomUUID();var ref=ReservationReference.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(r.getCheckoutId()).build();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var consuming=executor.submit(()->{gate.await();return reservations().consume(consumeRequest(r,capture));});var releasing=executor.submit(()->{gate.await();return reservations().release(ref);});gate.countDown();
            var result=consuming.get(5,TimeUnit.SECONDS);assertThat(releasing.get(5,TimeUnit.SECONDS)).isEqualTo(result);assertThat(reservations().get(ref)).isEqualTo(result);
            assertThat(held()).isZero();assertThat(jdbc.queryForObject("SELECT on_hand FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isEqualTo(result.getState()==ReservationState.CONSUMED?1:3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.reservation_audit WHERE checkout_id=?",Long.class,UUID.fromString(r.getCheckoutId()))).isEqualTo(2);
        }
    }
    @Test void sqlFailureAfterCounterWriteRollsBackAndSameCaptureCanRetry(){
        var r=hold();client().holdCheckout(r);var request=consumeRequest(r,UUID.randomUUID());
        jdbc.execute("CREATE FUNCTION catalog.reject_consumption() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='CONSUME' THEN RAISE EXCEPTION 'private'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_consumption BEFORE INSERT ON catalog.reservation_audit FOR EACH ROW EXECUTE FUNCTION catalog.reject_consumption()");
        try{error(()->reservations().consume(request),Status.Code.INTERNAL);}finally{jdbc.execute("DROP TRIGGER reject_consumption ON catalog.reservation_audit");jdbc.execute("DROP FUNCTION catalog.reject_consumption()");}
        assertThat(held()).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT on_hand FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT version FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isOne();
        assertThat(jdbc.queryForObject("SELECT consumed_capture_id FROM catalog.reservations WHERE checkout_id=?",UUID.class,UUID.fromString(r.getCheckoutId()))).isNull();
        assertThat(reservations().consume(request).getState()).isEqualTo(ReservationState.CONSUMED);
    }
    @Test void expiryWorkerSkipsConsumedEvenAfterDeadline(){
        var r=hold();client().holdCheckout(r);UUID capture=UUID.randomUUID();reservations().consume(consumeRequest(r,capture));
        jdbc.update("UPDATE catalog.reservations SET created_at=t.n-interval '16 minutes',expires_at=t.n-interval '1 minute',consumed_at=t.n-interval '2 minutes' FROM (SELECT clock_timestamp() n) t WHERE checkout_id=?",UUID.fromString(r.getCheckoutId()));
        new ReservationService(store,shops).expire();assertThat(held()).isZero();var current=reservations().get(ReservationReference.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(r.getCheckoutId()).build());
        assertThat(current.getState()).isEqualTo(ReservationState.CONSUMED);assertThat(current.getConsumedCaptureId()).isEqualTo(capture.toString());assertThat(jdbc.queryForObject("SELECT on_hand FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isOne();
    }

    @Test void oneCaptureCannotConsumeTwoConcurrentCheckouts()throws Exception{
        jdbc.update("UPDATE catalog.inventory_lots SET on_hand=6 WHERE id=?",lot);var first=hold();var second=hold();client().holdCheckout(first);client().holdCheckout(second);UUID capture=UUID.randomUUID();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var calls=List.of(first,second).stream().map(r->executor.submit(()->{gate.await();try{return reservations().consume(consumeRequest(r,capture)).getState().name();}catch(StatusRuntimeException e){return e.getStatus().getCode().name();}})).toList();gate.countDown();
            assertThat(List.of(calls.get(0).get(5,TimeUnit.SECONDS),calls.get(1).get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("CONSUMED","ALREADY_EXISTS");
        }
        assertThat(held()).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT on_hand FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isEqualTo(4);assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.reservations WHERE consumed_capture_id=?",Long.class,capture)).isOne();
    }
    @Test void failedLaterAllocationRollsBackEarlierCounterAndAudit(){
        UUID second=UUID.randomUUID();jdbc.update("UPDATE catalog.inventory_lots SET on_hand=1 WHERE id=?",lot);
        jdbc.update("INSERT INTO catalog.inventory_lots(id,product_id,shop_id,sku_id,code,expires_on,on_hand,state,version,created_at,updated_at) VALUES(?,?,?,?,'SECOND',current_date+11,1,'ACTIVE',0,now(),now())",second,product,shop,sku);
        var r=hold();client().holdCheckout(r);jdbc.update("UPDATE catalog.inventory_lots SET version=9007199254740991 WHERE id=?",second);var request=consumeRequest(r,UUID.randomUUID());
        error(()->reservations().consume(request),Status.Code.FAILED_PRECONDITION);
        assertThat(jdbc.queryForObject("SELECT on_hand FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isOne();assertThat(jdbc.queryForObject("SELECT held FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isOne();assertThat(jdbc.queryForObject("SELECT version FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.reservation_audit WHERE checkout_id=? AND action='CONSUME'",Long.class,UUID.fromString(r.getCheckoutId()))).isZero();
        jdbc.update("UPDATE catalog.inventory_lots SET version=1 WHERE id=?",second);assertThat(reservations().consume(request).getState()).isEqualTo(ReservationState.CONSUMED);
        assertThat(jdbc.queryForObject("SELECT sum(on_hand+held) FROM catalog.inventory_lots WHERE id IN (?,?)",Long.class,lot,second)).isZero();
    }
    @Test void consumeLockTimeoutDoesNotChangeHoldAndSameCaptureRetries()throws Exception{
        var r=hold();client().holdCheckout(r);var request=consumeRequest(r,UUID.randomUUID());
        try(var blocker=source.getConnection()){
            blocker.setAutoCommit(false);ReservationStoreTests.lock(blocker,"SELECT id FROM catalog.inventory_lots WHERE id='"+lot+"' FOR UPDATE");error(()->reservations().consume(request),Status.Code.UNAVAILABLE);blocker.rollback();
        }
        assertThat(held()).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT on_hand FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isEqualTo(3);assertThat(reservations().consume(request).getState()).isEqualTo(ReservationState.CONSUMED);
    }

}
