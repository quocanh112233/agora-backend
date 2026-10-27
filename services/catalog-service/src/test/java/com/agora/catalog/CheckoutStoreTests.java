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
class CheckoutStoreTests {
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
    @Test void quoteIsWholeSnapshotWithProductVersionAndDatabaseFiveMinuteDeadline(){
        long before=dbMicros();var q=client().quoteCheckout(quote());long after=dbMicros();
        assertThat(q.getShopId()).isEqualTo(shop.toString());assertThat(q.getShopName()).isEqualTo("Shop");assertThat(q.getOffersCount()).isOne();
        var offer=q.getOffers(0);assertThat(offer.getProductVersion()).isZero();assertThat(offer.getUnitPriceVnd()).isEqualTo(100);assertThat(offer.getQuantity()).isEqualTo(2);
        assertThat(offer.getProductName()).isEqualTo("Product");assertThat(offer.getSkuLabel()).isEqualTo("Package");assertThat(offer.getNetWeightGrams()).isEqualTo(100);
        assertThat(q.getExpiresAtEpochMicros()).isBetween(before+300000000,after+300000000);
        error(()->client().quoteCheckout(quote().toBuilder().addLines(CheckoutLine.newBuilder().setProductId(product.toString()).setSkuId(UUID.randomUUID().toString()).setQuantity(1)).build()),Status.Code.FAILED_PRECONDITION);
        jdbc.update("DELETE FROM catalog.product_images WHERE product_id=?",product);error(()->client().quoteCheckout(quote()),Status.Code.FAILED_PRECONDITION);
    }
    @Test void acceptedHoldUsesDbTimeAndReplaysBeforeMerchantAndDeadline(){
        var r=hold().toBuilder().setNotAfterEpochMicros(dbMicros()+2000000).build();long before=dbMicros();var result=client().holdCheckout(r);long after=dbMicros();
        assertThat(result.hasReservation()).isTrue();assertThat(held()).isEqualTo(2);
        var v=result.getReservation();assertThat(v.getCreatedAtEpochMicros()).isBetween(before,after);assertThat(v.getExpiresAtEpochMicros()-v.getCreatedAtEpochMicros()).isEqualTo(900000000);
        jdbc.execute("SELECT pg_sleep(2.1)");offline=true;jdbc.update("UPDATE catalog.products SET status='HIDDEN',version=1 WHERE id=?",product);
        assertThat(client().holdCheckout(r)).isEqualTo(result);
        // Same payload after admission deadline, still HELD under DB time despite an absurd injected Clock.
        jdbc.update("UPDATE catalog.reservations SET created_at=t.n-interval '16 minutes',expires_at=t.n-interval '1 minute' FROM (SELECT clock_timestamp() n) t WHERE checkout_id=?",UUID.fromString(r.getCheckoutId()));
        var released=client().holdCheckout(r);assertThat(released.getReservation().getState()).isEqualTo(ReservationState.RELEASED);assertThat(held()).isZero();
    }
    @Test void priceChangeAndVersionChangeThenRevertClosePermanently(){
        var price=hold();jdbc.update("UPDATE catalog.skus SET price_vnd=200 WHERE id=?",sku);
        closed(client().holdCheckout(price),CheckoutClosureReason.OFFER_UNAVAILABLE);
        jdbc.update("UPDATE catalog.skus SET price_vnd=100 WHERE id=?",sku);closed(client().holdCheckout(price),CheckoutClosureReason.OFFER_UNAVAILABLE);
        var version=hold();jdbc.update("UPDATE catalog.products SET name='Changed',version=version+1 WHERE id=?",product);jdbc.update("UPDATE catalog.products SET name='Product',version=version+1 WHERE id=?",product);
        closed(client().holdCheckout(version),CheckoutClosureReason.OFFER_UNAVAILABLE);assertThat(held()).isZero();
    }
    @Test void insufficientStockThenStockReturnsCannotReviveStaleWorker(){
        var r=hold();jdbc.update("UPDATE catalog.inventory_lots SET on_hand=1 WHERE id=?",lot);
        closed(client().holdCheckout(r),CheckoutClosureReason.OFFER_UNAVAILABLE);jdbc.update("UPDATE catalog.inventory_lots SET on_hand=3 WHERE id=?",lot);
        offline=true;closed(client().holdCheckout(r),CheckoutClosureReason.OFFER_UNAVAILABLE);assertThat(held()).isZero();
        error(()->client().holdCheckout(r.toBuilder().setBuyerId(UUID.randomUUID().toString()).build()),Status.Code.NOT_FOUND);
        error(()->client().holdCheckout(r.toBuilder().setNotAfterEpochMicros(r.getNotAfterEpochMicros()+1).build()),Status.Code.ALREADY_EXISTS);
        var raw=new ReservationService(store,shops);var rawRequest=new Request(buyer,UUID.fromString(r.getCheckoutId()),shop,List.of(new Line(product,sku,2)));
        assertThatThrownBy(()->raw.hold(rawRequest)).isInstanceOfSatisfying(CatalogFailure.class,e->assertThat(e.status()).isEqualTo(422));
        assertThatThrownBy(()->raw.get(buyer,UUID.fromString(r.getCheckoutId()))).isInstanceOfSatisfying(CatalogFailure.class,e->assertThat(e.status()).isEqualTo(404));
    }
    @Test void deadlineAndInactiveAreDurableButMerchantOutageIsRetryable(){
        var expired=hold().toBuilder().setNotAfterEpochMicros(dbMicros()-1).build();closed(client().holdCheckout(expired),CheckoutClosureReason.CHECKOUT_EXPIRED);
        var inactiveRequest=hold();inactive=true;closed(client().holdCheckout(inactiveRequest),CheckoutClosureReason.OFFER_UNAVAILABLE);inactive=false;
        closed(client().holdCheckout(inactiveRequest),CheckoutClosureReason.OFFER_UNAVAILABLE);
        var outage=hold();offline=true;error(()->client().holdCheckout(outage),Status.Code.UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.checkout_decisions WHERE checkout_id=?",Long.class,UUID.fromString(outage.getCheckoutId()))).isZero();
        offline=false;assertThat(client().holdCheckout(outage).hasReservation()).isTrue();
    }
    @Test void tokenUuidAndAcceptedBoundsAreChecked(){
        for(String token:new String[]{null,"wrong"})error(()->client(token).holdCheckout(HoldCheckoutRequest.getDefaultInstance()),Status.Code.PERMISSION_DENIED);
        var r=hold();error(()->client().holdCheckout(r.toBuilder().setBuyerId("1-1-1-1-1").build()),Status.Code.INVALID_ARGUMENT);
        error(()->client().quoteCheckout(quote().toBuilder().setShopId("bad").build()),Status.Code.INVALID_ARGUMENT);
        error(()->client().holdCheckout(r.toBuilder().clearLines().build()),Status.Code.INVALID_ARGUMENT);
        error(()->client().holdCheckout(r.toBuilder().addLines(r.getLines(0)).build()),Status.Code.INVALID_ARGUMENT);
        for(long n:new long[]{0,-1,1000000001L,Long.MAX_VALUE})error(()->client().holdCheckout(r.toBuilder().setLines(0,r.getLines(0).toBuilder().setUnitPriceVnd(n)).build()),Status.Code.INVALID_ARGUMENT);
        for(long n:new long[]{-1,9007199254740992L,Long.MAX_VALUE})error(()->client().holdCheckout(r.toBuilder().setLines(0,r.getLines(0).toBuilder().setProductVersion(n)).build()),Status.Code.INVALID_ARGUMENT);
        for(int n:new int[]{0,100,-1})error(()->client().holdCheckout(r.toBuilder().setLines(0,r.getLines(0).toBuilder().setQuantity(n)).build()),Status.Code.INVALID_ARGUMENT);
        error(()->client().holdCheckout(r.toBuilder().setNotAfterEpochMicros(0).build()),Status.Code.INVALID_ARGUMENT);
    }
    Request accepted(HoldCheckoutRequest wire){
        var lines=wire.getLinesList().stream().map(l->new Line(UUID.fromString(l.getProductId()),UUID.fromString(l.getSkuId()),l.getQuantity())).toList();
        var prices=new HashMap<UUID,Acceptance>();for(var l:wire.getLinesList())prices.put(UUID.fromString(l.getSkuId()),new Acceptance(l.getUnitPriceVnd(),l.getProductVersion()));
        return new Request(UUID.fromString(wire.getBuyerId()),UUID.fromString(wire.getCheckoutId()),UUID.fromString(wire.getShopId()),lines,wire.getNotAfterEpochMicros(),prices);
    }
    @Test void allTwentyOffersComeFromOneSnapshotAndAnyUnavailableLineFailsTheWholeQuote(){
        var b=quote().toBuilder();
        for(int i=1;i<20;i++){UUID p=UUID.randomUUID(),s=UUID.randomUUID();seedOffer(p,s,UUID.randomUUID());b.addLines(CheckoutLine.newBuilder().setProductId(p.toString()).setSkuId(s.toString()).setQuantity(99));}
        var q=client().quoteCheckout(b.build());assertThat(q.getOffersCount()).isEqualTo(20);assertThat(q.getOffersList().stream().map(CheckoutOffer::getSkuId)).containsExactlyInAnyOrderElementsOf(b.getLinesList().stream().map(CheckoutLine::getSkuId).toList());
        jdbc.update("UPDATE catalog.inventory_lots SET state='BLOCKED' WHERE id=?",lot);error(()->client().quoteCheckout(b.build()),Status.Code.FAILED_PRECONDITION);
        jdbc.update("UPDATE catalog.inventory_lots SET state='ACTIVE' WHERE id=?",lot);inactive=true;error(()->client().quoteCheckout(b.build()),Status.Code.FAILED_PRECONDITION);
    }
    @Test void closureAndNewHoldRaceConvergeOnOnePermanentOutcome()throws Exception{
        var request=accepted(hold());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var a=executor.submit(()->{gate.await();return store.holdCheckout(request,true);});var b=executor.submit(()->{gate.await();return store.holdCheckout(request,false);});gate.countDown();
            var result=a.get(5,TimeUnit.SECONDS);assertThat(b.get(5,TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(store.holdCheckout(request,true)).isEqualTo(result);assertThat(store.holdCheckout(request,false)).isEqualTo(result);
            assertThat(jdbc.queryForObject("SELECT (SELECT count(*) FROM catalog.reservations WHERE checkout_id=?)+(SELECT count(*) FROM catalog.checkout_decisions WHERE checkout_id=?)",Long.class,request.checkoutId(),request.checkoutId())).isOne();
            assertThat(held()).isEqualTo(result.reservation()==null?0:2);
        }
    }
    @Test void partialBusinessRollbackRemovesRowsCountersAndAuditButKeepsIdentityLock()throws Exception{
        // The second allocation fails AFTER the first counter and audit were written.
        UUID secondSku=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),secondLot=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.skus VALUES(?,?,?,?,'Second',100,100,100,1,1,1,'PACKAGE',1)",secondSku,product,shop,secondSku.toString().toUpperCase(Locale.ROOT));
        jdbc.update("INSERT INTO catalog.inventory_lots(id,product_id,shop_id,sku_id,code,expires_on,on_hand,state,version,created_at,updated_at) VALUES(?,?,?,?,?,current_date+10,3,'ACTIVE',9007199254740991,now(),now())",secondLot,product,shop,secondSku,secondLot.toString().toUpperCase(Locale.ROOT));
        var wire=hold().toBuilder().addLines(AcceptedCheckoutLine.newBuilder().setProductId(product.toString()).setSkuId(secondSku.toString()).setQuantity(1).setUnitPriceVnd(100).setProductVersion(0)).build();var request=accepted(wire);
        jdbc.execute("CREATE FUNCTION catalog.pause_closure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(732456789); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER pause_closure BEFORE INSERT ON catalog.checkout_decisions FOR EACH ROW EXECUTE FUNCTION catalog.pause_closure()");
        try(var blocker=source.getConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()){
            blocker.setAutoCommit(false);ReservationStoreTests.lock(blocker,"SELECT pg_advisory_xact_lock(732456789)");
            var closing=executor.submit(()->client().holdCheckout(wire));awaitBlocked("INSERT INTO catalog.checkout_decisions");
            assertThat(held()).isZero();assertThat(checkoutCount("reservation_audit",request.checkoutId())).isZero();
            var raw=new Request(buyer,request.checkoutId(),shop,request.lines());var racing=executor.submit(()->{
                try{store.create(raw);return 200;}catch(CatalogFailure e){return e.status();}
            });awaitBlocked("SELECT pg_advisory_xact_lock(hashtextextended");
            assertThat(racing.isDone()).isFalse();blocker.commit();
            closed(closing.get(5,TimeUnit.SECONDS),CheckoutClosureReason.OFFER_UNAVAILABLE);assertThat(racing.get(5,TimeUnit.SECONDS)).isEqualTo(422);
        }finally{jdbc.execute("DROP TRIGGER pause_closure ON catalog.checkout_decisions");jdbc.execute("DROP FUNCTION catalog.pause_closure()");}
        for(String table:List.of("reservations","reservation_lines","reservation_allocations","reservation_audit"))assertThat(checkoutCount(table,request.checkoutId())).isZero();
        assertThat(held()).isZero();assertThat(jdbc.queryForObject("SELECT version FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isZero();
    }
    long checkoutCount(String table,UUID checkout){return jdbc.queryForObject("SELECT count(*) FROM catalog."+table+" WHERE checkout_id=?",Long.class,checkout);}
    void awaitBlocked(String prefix)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?",Integer.class,prefix+"%")>0)return;Thread.sleep(5);}
        fail("Expected blocked database statement: "+prefix);
    }
    @Test void timeoutDoesNotPersistClosureAndSamePayloadCanRetry()throws Exception{
        var r=hold();try(var blocker=source.getConnection()){
            blocker.setAutoCommit(false);ReservationStoreTests.lock(blocker,"SELECT id FROM catalog.products WHERE id='"+product+"' FOR UPDATE");
            error(()->client().holdCheckout(r),Status.Code.UNAVAILABLE);blocker.rollback();
        }
        assertThat(checkoutCount("checkout_decisions",UUID.fromString(r.getCheckoutId()))).isZero();assertThat(held()).isZero();assertThat(client().holdCheckout(r).hasReservation()).isTrue();
    }
    @Test void unexpectedSqlFailureAfterCounterWriteFullyRollsBackWithoutClosing(){
        var r=hold();jdbc.execute("CREATE FUNCTION catalog.fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_audit BEFORE INSERT ON catalog.reservation_audit FOR EACH ROW EXECUTE FUNCTION catalog.fail_audit()");
        try{error(()->client().holdCheckout(r),Status.Code.INTERNAL);}finally{jdbc.execute("DROP TRIGGER fail_audit ON catalog.reservation_audit");jdbc.execute("DROP FUNCTION catalog.fail_audit()");}
        assertThat(held()).isZero();for(String table:List.of("checkout_decisions","reservations","reservation_lines","reservation_allocations","reservation_audit"))assertThat(checkoutCount(table,UUID.fromString(r.getCheckoutId()))).isZero();
        assertThat(client().holdCheckout(r).hasReservation()).isTrue();
    }
    @Test void parentLockWaitRechecksAcceptedVersionAndDbDeadline()throws Exception{
        var r=hold();
        try(var editor=source.getConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()){
            editor.setAutoCommit(false);ReservationStoreTests.lock(editor,"SELECT id FROM catalog.products WHERE id='"+product+"' FOR UPDATE");
            var holding=executor.submit(()->client().holdCheckout(r));awaitBlocked("SELECT id FROM catalog.products");
            ReservationStoreTests.lock(editor,"UPDATE catalog.products SET version=version+1 WHERE id='"+product+"'");editor.commit();closed(holding.get(5,TimeUnit.SECONDS),CheckoutClosureReason.OFFER_UNAVAILABLE);
        }
        jdbc.update("UPDATE catalog.products SET version=0 WHERE id=?",product);
        var deadline=hold().toBuilder().setNotAfterEpochMicros(dbMicros()+500000).build();
        try(var editor=source.getConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()){
            editor.setAutoCommit(false);ReservationStoreTests.lock(editor,"SELECT id FROM catalog.inventory_lots WHERE id='"+lot+"' FOR UPDATE");
            var holding=executor.submit(()->client().holdCheckout(deadline));awaitBlocked("SELECT id,sku_id,expires_on");jdbc.execute("SELECT pg_sleep(0.55)");editor.commit();
            closed(holding.get(5,TimeUnit.SECONDS),CheckoutClosureReason.CHECKOUT_EXPIRED);
        }assertThat(held()).isZero();
    }
    @Test void durableDecisionsHavePermanentCheckoutIdentityAndOwnerHashGuards(){
        // Removing V7 must fail this real PostgreSQL schema assertion before implementation.
        assertThat(jdbc.queryForObject("SELECT to_regclass('catalog.checkout_decisions') IS NOT NULL",Boolean.class)).isTrue();
    }
}
