package com.agora.catalog;

import com.agora.catalog.adapter.outbound.JdbcReservationStore;
import com.agora.catalog.application.*;
import com.agora.catalog.domain.*;
import com.agora.catalog.domain.Reservation.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ReservationStoreTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    static JdbcTemplate jdbc;static DriverManagerDataSource dataSource;
    static final Instant START=Instant.parse("2026-10-24T03:00:00.123456789Z");
    final AtomicReference<Instant> now=new AtomicReference<>(START);
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
    ReservationStore store;ReservationService service;
    final UUID buyer=UUID.randomUUID(),shop=UUID.randomUUID();
    record Seed(UUID product,UUID sku){}
    @BeforeAll static void migrate(){
        dataSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());jdbc=new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).defaultSchema("catalog").schemas("catalog").locations("classpath:db/migration").load().migrate();
    }
    @BeforeEach void setup(){
        jdbc.execute("TRUNCATE catalog.reservation_audit,catalog.reservation_allocations,catalog.reservation_lines,catalog.reservations,catalog.inventory_audit,catalog.inventory_requests,catalog.inventory_lots,catalog.product_images,catalog.image_uploads,catalog.media_requests,catalog.audit_entries,catalog.idempotency_records,catalog.skus,catalog.products");
        store=new JdbcReservationStore(jdbc,new DataSourceTransactionManager(dataSource),clock);
        service=new ReservationService(store,ids->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return Map.of(shop,new PublicShops.Shop(shop,"Shop"));});
    }
    Seed seed(){
        UUID product=UUID.randomUUID(),sku=UUID.randomUUID(),image=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.products VALUES(?,?,?,'Description','PRODUCE','FRESH','VN',null,'Cool','PUBLISHED',0,now(),now())",product,shop,"Product");
        jdbc.update("INSERT INTO catalog.skus VALUES(?,?,?,?,'Package',100,100,100,1,1,1,'PACKAGE',0)",sku,product,shop,sku.toString().toUpperCase(Locale.ROOT));
        String publicId="agora/products/"+product+"/"+image;
        jdbc.update("INSERT INTO catalog.image_uploads(id,actor_id,shop_id,product_id,key,request_hash,public_id,state,mime,bytes,width,height,provider_version,format,url) VALUES(?,?,?,?,?,?,?,'UPLOADED','image/png',68,1,1,1,'png',?)",image,buyer,shop,product,UUID.randomUUID(),"0".repeat(64),publicId,"https://res.cloudinary.com/test/image/upload/v1/"+publicId+".png");
        jdbc.update("INSERT INTO catalog.product_images VALUES(?,?,?,0)",image,product,shop);return new Seed(product,sku);
    }
    UUID lot(Seed p,UUID id,String expiry,int quantity){
        jdbc.update("INSERT INTO catalog.inventory_lots(id,product_id,shop_id,sku_id,code,expires_on,on_hand,state,version,created_at,updated_at) VALUES(?,?,?,?,?,?::date,?,'ACTIVE',0,now(),now())",id,p.product(),shop,p.sku(),id.toString().toUpperCase(Locale.ROOT),expiry,quantity);return id;
    }
    UUID lot(Seed p,int quantity){return lot(p,UUID.randomUUID(),"2026-10-26",quantity);}
    Request request(Seed p,int qty){return new Request(buyer,UUID.randomUUID(),shop,List.of(new Line(p.product(),p.sku(),qty)));}
    long held(UUID lot){return jdbc.queryForObject("SELECT held FROM catalog.inventory_lots WHERE id=?",Long.class,lot);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM catalog."+table,Long.class);}
    void status(int expected,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(CatalogFailure.class,e->assertThat(e.status()).isEqualTo(expected));}
    @Test void fefoUsesExpiryThenUnsignedUuidAndKeepsOriginalMicrosecondTtl(){
        var p=seed();UUID first=lot(p,UUID.fromString("00000000-0000-0000-0000-000000000001"),"2026-10-25",2);
        UUID second=lot(p,UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),"2026-10-25",3),later=lot(p,5);var request=request(p,4);
        var r=service.hold(request);assertThat(r.items().getFirst().allocations()).containsExactly(new Allocation(first,2),new Allocation(second,2));
        assertThat(held(first)).isEqualTo(2);assertThat(held(second)).isEqualTo(2);assertThat(held(later)).isZero();
        assertThat(r.createdAt()).isEqualTo(Instant.parse("2026-10-24T03:00:00.123456Z"));assertThat(r.expiresAt()).isEqualTo(Instant.parse("2026-10-24T03:15:00.123456Z"));
        now.set(START.plusSeconds(60));assertThat(service.hold(request)).isEqualTo(r);
        assertThat(jdbc.queryForObject("SELECT sum(version) FROM catalog.inventory_lots",Long.class)).isEqualTo(2);assertThat(count("reservation_audit")).isEqualTo(2);
    }
    @Test void missingStockInAnySkuRollsBackAllCountersRowsAndAudit(){
        var a=seed();var b=seed();var lot=lot(a,3);lot(b,0);
        var r=new Request(buyer,UUID.randomUUID(),shop,List.of(new Line(a.product(),a.sku(),2),new Line(b.product(),b.sku(),1)));
        status(409,()->service.hold(r));assertThat(held(lot)).isZero();
        for(String table:List.of("reservations","reservation_lines","reservation_allocations","reservation_audit"))assertThat(count(table)).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(version) FROM catalog.inventory_lots",Long.class)).isZero();
    }
    @Test void ownerHashAndPermanentReplayAreCheckedBeforeDependencies(){
        var p=seed();var lot=lot(p,2);var r=request(p,1);service.hold(r);
        var offline=new ReservationService(store,ids->{throw new AssertionError("Merchant must not be called");});
        status(404,()->offline.hold(new Request(UUID.randomUUID(),r.checkoutId(),shop,r.lines())));
        assertThatThrownBy(()->offline.hold(new Request(buyer,r.checkoutId(),shop,List.of(new Line(p.product(),p.sku(),2))))).isInstanceOfSatisfying(CatalogFailure.class,e->{assertThat(e.status()).isEqualTo(422);assertThat(e.code()).isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH");});
        now.set(START.plus(Duration.ofDays(8)));var old=offline.hold(r);assertThat(old.state()).isEqualTo("RELEASED");assertThat(old.releaseReason()).isEqualTo("EXPIRED");assertThat(held(lot)).isZero();
        assertThat(offline.hold(r)).isEqualTo(old);assertThat(count("reservations")).isOne();assertThat(count("reservation_audit")).isEqualTo(2);
    }
    @Test void releaseBlockedLotAndHiddenProductExactlyOnceWithoutProductLock()throws Exception{
        var p=seed();var lot=lot(p,3);var r=request(p,2);service.hold(r);
        jdbc.update("UPDATE catalog.inventory_lots SET state='BLOCKED' WHERE id=?",lot);jdbc.update("UPDATE catalog.products SET status='HIDDEN' WHERE id=?",p.product());
        try(var connection=dataSource.getConnection()){
            connection.setAutoCommit(false);lock(connection,"SELECT id FROM catalog.products WHERE id='"+p.product()+"' FOR UPDATE");
            var released=service.release(buyer,r.checkoutId());assertThat(released.releaseReason()).isEqualTo("CANCELLED");assertThat(service.release(buyer,r.checkoutId())).isEqualTo(released);connection.rollback();
        }
        assertThat(held(lot)).isZero();assertThat(jdbc.queryForObject("SELECT version FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isEqualTo(2);assertThat(count("reservation_audit")).isEqualTo(2);
    }
    @Test void getAtExactExpiryReleasesAndHidesOtherOwners(){
        var p=seed();var lot=lot(p,1);var r=request(p,1);var held=service.hold(r);
        now.set(held.expiresAt().minusNanos(1000));assertThat(service.get(buyer,r.checkoutId()).state()).isEqualTo("HELD");
        now.set(held.expiresAt());status(404,()->service.get(UUID.randomUUID(),r.checkoutId()));assertThat(held(lot)).isOne();
        assertThat(service.get(buyer,r.checkoutId()).releaseReason()).isEqualTo("EXPIRED");assertThat(held(lot)).isZero();
        status(404,()->service.release(buyer,UUID.randomUUID()));
    }
    @Test void concurrentLastStockHasExactlyOneWinner()throws Exception{
        var p=seed();var lot=lot(p,1);var results=race(()->attempt(request(p,1)),()->attempt(request(p,1)));
        assertThat(results).containsExactlyInAnyOrder(200,409);assertThat(held(lot)).isOne();assertThat(count("reservations")).isOne();
    }
    int attempt(Request r){try{service.hold(r);return 200;}catch(CatalogFailure e){return e.status();}}
    @Test void concurrentSameCheckoutCreatesOneHold()throws Exception{
        var p=seed();var lot=lot(p,2);var r=request(p,1);
        var results=race(()->service.hold(r),()->service.hold(r));assertThat(results.get(0)).isEqualTo(results.get(1));assertThat(held(lot)).isOne();assertThat(count("reservation_audit")).isOne();
    }
    @Test void releaseRacingExpiryNeverDecrementsTwice()throws Exception{
        var p=seed();var lot=lot(p,1);var r=request(p,1);now.set(service.hold(r).expiresAt());
        race(()->service.release(buyer,r.checkoutId()),()->{service.expire();return service.get(buyer,r.checkoutId());});
        assertThat(held(lot)).isZero();assertThat(count("reservation_audit")).isEqualTo(2);assertThat(service.hold(r).state()).isEqualTo("RELEASED");
    }
    @Test void workerLimitsBatchToTwentyDistinctReservationsAndWorkersAreSafe()throws Exception{
        var p=seed();var lot=lot(p,30);for(int i=0;i<25;i++)service.hold(request(p,1));now.set(START.plusSeconds(901));
        assertThat(service.expire()).isEqualTo(20);assertThat(held(lot)).isEqualTo(5);
        var results=race(()->service.expire(),()->service.expire());assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(5);
        assertThat(held(lot)).isZero();assertThat(count("reservations")).isEqualTo(25);assertThat(count("reservation_audit")).isEqualTo(50);
    }
    @Test void moreThanThousandEligibleLotsIsExplicitResourceLimit(){
        var p=seed();jdbc.update("INSERT INTO catalog.inventory_lots(id,product_id,shop_id,sku_id,code,expires_on,on_hand,state,version,created_at,updated_at) SELECT gen_random_uuid(),?,?,?,'LOT_'||n,'2026-10-26',1,'ACTIVE',0,now(),now() FROM generate_series(1,1001) n",p.product(),shop,p.sku());
        status(429,()->service.hold(request(p,1)));assertThat(count("reservations")).isZero();assertThat(jdbc.queryForObject("SELECT sum(held) FROM catalog.inventory_lots",Long.class)).isZero();
    }
    @Test void postLockClockRejectsLotThatExpiredAcrossVietnamMidnight()throws Exception{
        var p=seed();var lot=lot(p,UUID.randomUUID(),"2026-10-25",1);now.set(Instant.parse("2026-10-24T16:59:59Z"));var r=request(p,1);
        try(var connection=dataSource.getConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()){
            connection.setAutoCommit(false);lock(connection,"SELECT id FROM catalog.inventory_lots WHERE id='"+lot+"' FOR UPDATE");
            var future=executor.submit(()->attempt(r));awaitBlocked();now.set(Instant.parse("2026-10-24T17:00:00Z"));connection.rollback();
            assertThat(future.get(5,TimeUnit.SECONDS)).isEqualTo(409);
        }assertThat(held(lot)).isZero();assertThat(count("reservations")).isZero();
    }
    @Test void postLockClockStartsFullTtlAfterWait()throws Exception{
        var p=seed();var lot=lot(p,1);var r=request(p,1);
        try(var connection=dataSource.getConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()){
            connection.setAutoCommit(false);lock(connection,"SELECT id FROM catalog.inventory_lots WHERE id='"+lot+"' FOR UPDATE");
            var future=executor.submit(()->service.hold(r));awaitBlocked();now.set(START.plusSeconds(120));connection.rollback();
            var held=future.get(5,TimeUnit.SECONDS);assertThat(held.createdAt()).isEqualTo(Instant.parse("2026-10-24T03:02:00.123456Z"));assertThat(held.expiresAt()).isEqualTo(Instant.parse("2026-10-24T03:17:00.123456Z"));
        }
    }
    @Test void identityLockCoversMissingRowAndTimesOutSafely()throws Exception{
        var p=seed();lot(p,1);var r=request(p,1);
        try(var connection=dataSource.getConnection()){
            connection.setAutoCommit(false);lock(connection,"SELECT pg_advisory_xact_lock(hashtextextended('reservation:"+r.checkoutId()+"',0))");
            long start=System.nanoTime();status(503,()->service.hold(r));assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofSeconds(1),Duration.ofSeconds(5));connection.rollback();
        }assertThat(count("reservations")).isZero();
    }
    @Test void databaseRejectsCrossSkuAllocationAndInvalidStateOrTimes(){
        var a=seed();var b=seed();lot(a,1);var other=lot(b,1);var r=request(a,1);service.hold(r);
        assertThatThrownBy(()->jdbc.update("UPDATE catalog.reservation_allocations SET lot_id=? WHERE checkout_id=?",other,r.checkoutId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for(String change:List.of("quantity=0"))assertThatThrownBy(()->jdbc.update("UPDATE catalog.reservation_allocations SET "+change+" WHERE checkout_id=?",r.checkoutId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for(String change:List.of("state='ALLOCATED'","expires_at=created_at","release_reason='EXPIRED'","state='RELEASED'"))assertThatThrownBy(()->jdbc.update("UPDATE catalog.reservations SET "+change+" WHERE checkout_id=?",r.checkoutId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void depletedEarlierFefoLotReleasedWhileHoldWaitsIsAllocated()throws Exception{
        var p=seed();
        UUID earliest=lot(p,UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),"2026-10-25",1);
        var previous=request(p,1);service.hold(previous);
        UUID later=lot(p,UUID.fromString("00000000-0000-0000-0000-000000000001"),"2026-10-26",1);
        var next=request(p,1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor();var blocker=dataSource.getConnection()){
            blocker.setAutoCommit(false);
            lock(blocker,"UPDATE catalog.inventory_lots SET state='BLOCKED' WHERE id='"+later+"'");
            var holding=executor.submit(()->service.hold(next));awaitBlocked();
            // The later lot sorts first by UUID, so Hold cannot own the earlier lot while waiting.
            assertThat(executor.submit(()->service.release(buyer,previous.checkoutId())).get(1,TimeUnit.SECONDS).state()).isEqualTo("RELEASED");
            blocker.commit();
            var result=holding.get(5,TimeUnit.SECONDS);
            assertThat(result.items().getFirst().allocations()).containsExactly(new Allocation(earliest,1));
            assertThat(service.hold(next)).isEqualTo(result);
        }
        assertThat(held(earliest)).isOne();assertThat(held(later)).isZero();
    }
    @Test void blockedEarlierFefoLotReopenedWhileHoldWaitsIsAllocated()throws Exception{
        var p=seed();
        UUID earliest=lot(p,UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),"2026-10-25",1);
        jdbc.update("UPDATE catalog.inventory_lots SET state='BLOCKED' WHERE id=?",earliest);
        UUID later=lot(p,UUID.fromString("00000000-0000-0000-0000-000000000001"),"2026-10-26",1);
        var r=request(p,1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor();var blocker=dataSource.getConnection()){
            blocker.setAutoCommit(false);lock(blocker,"UPDATE catalog.inventory_lots SET state='BLOCKED' WHERE id='"+later+"'");
            var holding=executor.submit(()->service.hold(r));awaitBlocked();
            assertThat(executor.submit(()->jdbc.update("UPDATE catalog.inventory_lots SET state='ACTIVE' WHERE id=?",earliest)).get(1,TimeUnit.SECONDS)).isOne();
            blocker.commit();var result=holding.get(5,TimeUnit.SECONDS);
            assertThat(result.items().getFirst().allocations()).containsExactly(new Allocation(earliest,1));
            assertThat(service.hold(r)).isEqualTo(result);
        }
        assertThat(held(earliest)).isOne();assertThat(held(later)).isZero();
    }
    @Test void cleanupPreservesProgrammingErrorsAndRollsBackReservation(){
        var p=seed();var lot=lot(p,1);var r=request(p,1);var reservation=service.hold(r);
        var brokenClock=org.mockito.Mockito.mock(Clock.class);var bug=new IllegalStateException("clock programming bug");
        org.mockito.Mockito.when(brokenClock.instant()).thenReturn(reservation.expiresAt()).thenThrow(bug);
        var broken=new JdbcReservationStore(jdbc,new DataSourceTransactionManager(dataSource),brokenClock);
        assertThatThrownBy(broken::expire).isSameAs(bug);
        assertThat(held(lot)).isOne();assertThat(count("reservation_audit")).isOne();
        assertThat(jdbc.queryForObject("SELECT state FROM catalog.reservations WHERE checkout_id=?",String.class,r.checkoutId())).isEqualTo("HELD");
    }
    @Test void cleanupSkipsContendedOldestIdentityAndReleasesOtherDueReservation()throws Exception{
        var p=seed();var lot=lot(p,2);var oldest=request(p,1);service.hold(oldest);
        now.set(START.plusSeconds(1));var other=request(p,1);service.hold(other);now.set(START.plusSeconds(902));
        try(var blocker=dataSource.getConnection()){
            blocker.setAutoCommit(false);
            lock(blocker,"SELECT pg_advisory_xact_lock(hashtextextended('reservation:"+oldest.checkoutId()+"',0))");
            assertThat(service.expire()).isOne();
            assertThat(jdbc.queryForObject("SELECT state FROM catalog.reservations WHERE checkout_id=?",String.class,oldest.checkoutId())).isEqualTo("HELD");
            assertThat(jdbc.queryForObject("SELECT state FROM catalog.reservations WHERE checkout_id=?",String.class,other.checkoutId())).isEqualTo("RELEASED");
            assertThat(held(lot)).isOne();blocker.rollback();
        }
        assertThat(service.expire()).isOne();assertThat(held(lot)).isZero();assertThat(count("reservation_audit")).isEqualTo(4);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"hide","edit","detach"})
    void holdRechecksEligibilityAfterWaitingForParentMutation(String mutation)throws Exception{
        var p=seed();var lot=lot(p,1);var r=request(p,1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor();var editor=dataSource.getConnection()){
            editor.setAutoCommit(false);lock(editor,"SELECT id FROM catalog.products WHERE id='"+p.product()+"' FOR UPDATE");
            var holding=executor.submit(()->attempt(r));awaitBlocked();
            switch(mutation){
                case "hide" -> lock(editor,"UPDATE catalog.products SET status='HIDDEN',version=version+1 WHERE id='"+p.product()+"'");
                case "edit" -> {
                    lock(editor,"UPDATE catalog.products SET status='HIDDEN',name='Edited',version=version+1 WHERE id='"+p.product()+"'");
                    lock(editor,"UPDATE catalog.skus SET price_vnd=200 WHERE id='"+p.sku()+"'");
                }
                case "detach" -> lock(editor,"DELETE FROM catalog.product_images WHERE product_id='"+p.product()+"'");
                default -> throw new AssertionError(mutation);
            }
            editor.commit();assertThat(holding.get(5,TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(held(lot)).isZero();assertThat(count("reservations")).isZero();assertThat(count("reservation_audit")).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"BLOCKED","ACTIVE"})
    void blockedAndDepletedLotsCountTowardThousandCandidateCap(String state){
        var p=seed();var available=lot(p,2);
        jdbc.update("INSERT INTO catalog.inventory_lots(id,product_id,shop_id,sku_id,code,expires_on,on_hand,state,version,created_at,updated_at) SELECT gen_random_uuid(),?,?,?,'LOT_'||n,'2026-10-26',?,?,0,now(),now() FROM generate_series(1,999) n",p.product(),shop,p.sku(),state.equals("BLOCKED")?1:0,state);
        assertThat(service.hold(request(p,1)).state()).isEqualTo("HELD");
        lot(p,0);status(429,()->service.hold(request(p,1)));
        assertThat(held(available)).isOne();assertThat(count("reservations")).isOne();assertThat(count("reservation_audit")).isOne();
    }
    static void lock(Connection connection,String sql)throws Exception{try(var statement=connection.createStatement()){statement.execute(sql);}}
    void awaitBlocked()throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(System.nanoTime()<deadline){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)>0)return;Thread.sleep(10);}
        fail("Reservation operation did not wait on the held database lock");
    }
    <T> List<T> race(Callable<T> first,Callable<T> second)throws Exception{
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var a=executor.submit(()->{gate.await();return first.call();});var b=executor.submit(()->{gate.await();return second.call();});gate.countDown();
            return List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
    }
}
