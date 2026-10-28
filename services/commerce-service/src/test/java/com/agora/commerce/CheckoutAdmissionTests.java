package com.agora.commerce;

import com.agora.commerce.application.CartService;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.agora.commerce.application.*;
import com.agora.commerce.domain.Checkout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.config.import=")
@Import(CartApiTests.RemoteConfiguration.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutAdmissionTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);p.add("agora.internal-token",()->CartRpcFixture.TOKEN);}
    @Autowired CartRpcFixture rpc; @Autowired JdbcTemplate jdbc; @Autowired JsonMapper json; @LocalServerPort int port;
    @Autowired CheckoutCatalog checkoutCatalog;@Autowired CheckoutStore checkoutStore;@Autowired CheckoutService checkoutService;@Autowired javax.sql.DataSource dataSource;
    @BeforeEach void resetCheckout(){jdbc.execute("TRUNCATE commerce.capture_applications,commerce.checkout_attempts,commerce.orders,commerce.checkout_previews,commerce.cart_requests,commerce.cart_items,commerce.carts");rpc.resetCheckout();rpc.offers.clear();rpc.identityFailure.set(null);}
    @Test void authenticatedBuyerCanPreviewSelectedCartAtServerPrice()throws Exception {
        String session=rpc.account();UUID actor=rpc.sessions.get(session);var offer=rpc.offer();
        jdbc.update("INSERT INTO commerce.carts(account_id,version) VALUES(?,1)",actor);
        jdbc.update("INSERT INTO commerce.cart_items(account_id,sku_id,product_id,quantity) VALUES(?,?,?,2)",actor,UUID.fromString(offer.getSkuId()),UUID.fromString(offer.getProductId()));
        String body=json.createObjectNode().put("cartId",CartService.cartId(actor).toString()).put("cartVersion",1).put("shopId",offer.getShopId()).set("skuIds",json.createArrayNode().add(offer.getSkuId())).toString();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/checkout-previews")).header("Cookie","AGORA_SESSION="+session).header("X-CSRF-TOKEN","csrf-fixture").header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("totalVnd").asLong()).isEqualTo(70000);
        assertThat(response.body()).doesNotContain("productVersion","lotId",actor.toString());
    }

    HttpResponse<String> call(String session,String method,String path,String body,String key,boolean csrf)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me"+path));
        if(session!=null)b.header("Cookie","AGORA_SESSION="+session);if(csrf)b.header("X-CSRF-TOKEN","csrf-fixture");if(key!=null)b.header("Idempotency-Key",key);
        if(body!=null)b.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    record Setup(String session,UUID actor,String preview){}
    Setup preview()throws Exception {
        rpc.resetCheckout();String session=rpc.account();UUID actor=rpc.sessions.get(session);var offer=rpc.offer();
        jdbc.update("INSERT INTO commerce.carts(account_id,version) VALUES(?,1)",actor);
        jdbc.update("INSERT INTO commerce.cart_items(account_id,sku_id,product_id,quantity) VALUES(?,?,?,2)",actor,UUID.fromString(offer.getSkuId()),UUID.fromString(offer.getProductId()));
        String body="{\"cartId\":\""+CartService.cartId(actor)+"\",\"cartVersion\":1,\"shopId\":\""+offer.getShopId()+"\",\"skuIds\":[\""+offer.getSkuId()+"\"]}";
        var r=call(session,"POST","/checkout-previews",body,UUID.randomUUID().toString(),true);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return new Setup(session,actor,json.readTree(r.body()).path("id").asString());
    }
    String confirmBody(Setup s){return "{\"previewId\":\""+s.preview()+"\"}";}
    @Test void confirmationReplaysSnapshotAndEnforcesOwnershipAndCsrf()throws Exception {
        var s=preview();String key=UUID.randomUUID().toString();var first=call(s.session(),"POST","/checkouts",confirmBody(s),key,true);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);var a=json.readTree(first.body());assertThat(a.path("state").asString()).isEqualTo("ORDER_CREATED");
        String id=a.path("orderId").asString();var detail=call(s.session(),"GET","/orders/"+id,null,null,false);assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(json.readTree(detail.body()).path("totalVnd").asLong()).isEqualTo(70000);assertThat(json.readTree(detail.body()).path("cartCleared").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.cart_items WHERE account_id=?",Integer.class,s.actor())).isZero();
        rpc.holdFailure.set(io.grpc.Status.UNAVAILABLE);rpc.offers.clear();assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key,true).body()).isEqualTo(first.body());
        assertThat(call(s.session(),"GET","/checkouts/"+key,null,null,false).body()).isEqualTo(first.body());assertThat(rpc.holdCalls.get()).isEqualTo(1);
        assertThat(call(s.session(),"GET","/orders/"+id,null,null,false).body()).isEqualTo(detail.body());
        String other=rpc.account();assertThat(call(other,"GET","/orders/"+id,null,null,false).statusCode()).isEqualTo(404);assertThat(call(other,"GET","/checkouts/"+key,null,null,false).statusCode()).isEqualTo(404);
        assertThat(json.readTree(call(other,"GET","/orders",null,null,false).body()).path("items").size()).isZero();
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key,false).statusCode()).isEqualTo(403);
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
        assertThat(call(s.session(),"POST","/checkouts","{\"previewId\":\""+UUID.randomUUID()+"\"}",key,true).statusCode()).isEqualTo(422);
    }
    @Test void lostHoldResponseLeavesDurableAttemptAndRecoversWithIdenticalPayload()throws Exception {
        var s=preview();String key=UUID.randomUUID().toString();rpc.afterHoldFailure.set(io.grpc.Status.UNAVAILABLE);
        rpc.holdHook=()->assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.checkout_attempts WHERE actor_id=? AND state='PROCESSING'",Integer.class,s.actor())).isEqualTo(1);
        var pending=call(s.session(),"POST","/checkouts",confirmBody(s),key,true);assertThat(pending.statusCode()).isEqualTo(202);assertThat(json.readTree(pending.body()).path("state").asString()).isEqualTo("PROCESSING");
        rpc.afterHoldFailure.set(null);jdbc.update("UPDATE commerce.checkout_attempts SET lease_until=clock_timestamp()-interval '1 second' WHERE actor_id=?",s.actor());
        var recovered=call(s.session(),"POST","/checkouts",confirmBody(s),key,true);assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(200);assertThat(json.readTree(recovered.body()).path("state").asString()).isEqualTo("ORDER_CREATED");
        assertThat(rpc.holdRequests).hasSize(2);assertThat(rpc.holdRequests.get(1)).isEqualTo(rpc.holdRequests.get(0));
    }
    @Test void mutableRejectionRemainsProcessingButTypedClosureIsTerminal()throws Exception {
        var s=preview();String key=UUID.randomUUID().toString();rpc.holdFailure.set(io.grpc.Status.FAILED_PRECONDITION);
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key,true).statusCode()).isEqualTo(202);
        rpc.holdFailure.set(null);rpc.holdOverride.set(com.agora.contracts.catalog.v1.CheckoutHoldResult.newBuilder().setClosed(com.agora.contracts.catalog.v1.CheckoutClosed.newBuilder().setReason(com.agora.contracts.catalog.v1.CheckoutClosureReason.OFFER_UNAVAILABLE)).build());
        jdbc.update("UPDATE commerce.checkout_attempts SET lease_until=clock_timestamp()-interval '1 second' WHERE actor_id=?",s.actor());
        var result=call(s.session(),"POST","/checkouts",confirmBody(s),key,true);assertThat(result.statusCode()).isEqualTo(200);assertThat(json.readTree(result.body()).path("failureCode").asString()).isEqualTo("OFFER_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.orders WHERE actor_id=?",Integer.class,s.actor())).isZero();
    }
    @Test void cartMutationDuringRpcIsPreservedAndQuoteExpiryIsExact()throws Exception {
        var s=preview();rpc.holdHook=()->jdbc.update("UPDATE commerce.carts SET version=version+1 WHERE account_id=?",s.actor());
        var result=call(s.session(),"POST","/checkouts",confirmBody(s),UUID.randomUUID().toString(),true);assertThat(result.statusCode()).isEqualTo(200);
        var order=call(s.session(),"GET","/orders/"+json.readTree(result.body()).path("orderId").asString(),null,null,false);
        assertThat(json.readTree(order.body()).path("cartCleared").asBoolean()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.cart_items WHERE account_id=?",Integer.class,s.actor())).isEqualTo(1);
    }

    @Test void foreignPreviewIsNotFoundEvenWhenAccountHasNoCart()throws Exception {
        var s=preview();assertThat(call(rpc.account(),"POST","/checkouts",confirmBody(s),UUID.randomUUID().toString(),true).statusCode()).isEqualTo(404);
    }
    @Test void previewReplayRetainsExactCatalogExpiryAndSurvivesCartAndCatalogChanges()throws Exception {
        var s=preview();var offer=rpc.offers.values().iterator().next();String key=UUID.randomUUID().toString();rpc.quoteExpiry=CartRpcFixture.micros()+123456789;
        String body="{\"cartId\":\""+CartService.cartId(s.actor())+"\",\"cartVersion\":1,\"shopId\":\""+offer.getShopId()+"\",\"skuIds\":[\""+offer.getSkuId()+"\"]}";
        var first=call(s.session(),"POST","/checkout-previews",body,key,true);assertThat(first.statusCode()).isEqualTo(200);
        assertThat(Checkout.micros(java.time.Instant.parse(json.readTree(first.body()).path("expiresAt").asString()))).isEqualTo(rpc.quoteExpiry);
        jdbc.update("UPDATE commerce.carts SET version=version+1 WHERE account_id=?",s.actor());rpc.quoteFailure.set(io.grpc.Status.UNAVAILABLE);int calls=rpc.quoteCalls.get();
        assertThat(call(s.session(),"POST","/checkout-previews",body,key,true).body()).isEqualTo(first.body());assertThat(rpc.quoteCalls.get()).isEqualTo(calls);
        assertThat(call(s.session(),"POST","/checkout-previews",body.replace("\"cartVersion\":1","\"cartVersion\":2"),key,true).statusCode()).isEqualTo(422);
    }
    @Test void strictFieldsJsonQueriesAndAuthenticationCoverAllOperations()throws Exception {
        var s=preview();String body=confirmBody(s),key=UUID.randomUUID().toString();
        assertThat(call(null,"GET","/orders",null,null,false).statusCode()).isEqualTo(401);
        assertThat(call(s.session(),"POST","/checkouts",body,key,false).statusCode()).isEqualTo(403);
        for(String invalid:List.of(body.replace("previewId","other"),body.replace("}",",\"price\":1}"),"[]","{\"previewId\":1}"))assertThat(call(s.session(),"POST","/checkouts",invalid,key,true).statusCode()).isEqualTo(422);
        for(String invalid:List.of("{",body+" {}",body.replace("}",",\"previewId\":\""+s.preview()+"\"}")))assertThat(call(s.session(),"POST","/checkouts",invalid,key,true).statusCode()).isEqualTo(400);
        assertThat(call(s.session(),"POST","/checkouts",body,"1-1-1-1-1",true).statusCode()).isEqualTo(422);
        for(String path:List.of("/orders?extra=1","/orders?cursor=","/orders?cursor=x&cursor=y","/orders/nope","/checkouts/nope","/checkouts/"+key+"?x=1"))assertThat(call(s.session(),"GET",path,null,null,false).statusCode()).isEqualTo(422);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/orders")).header("Cookie","AGORA_SESSION="+s.session()+"; AGORA_SESSION="+s.session()).GET().build();
        assertThat(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(rpc.holdCalls.get()).isZero();
    }
    @Test void staleAndExpiredPreviewsCannotStartAttempt()throws Exception {
        var s=preview();jdbc.update("UPDATE commerce.carts SET version=2 WHERE account_id=?",s.actor());
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE commerce.carts SET version=1 WHERE account_id=?",s.actor());jdbc.update("UPDATE commerce.checkout_previews SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",UUID.fromString(s.preview()));
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);assertThat(rpc.holdCalls.get()).isZero();
    }
    @Test void staleLeaseCannotCompleteAndOrderInsertFailureRollsBackCartAndAttempt()throws Exception {
        var s=preview();UUID key=UUID.randomUUID();checkoutStore.claim(s.actor(),key,UUID.fromString(s.preview()));var old=checkoutStore.acquire(s.actor(),key).orElseThrow();
        assertThat(checkoutStore.acquire(s.actor(),key)).isEmpty();jdbc.update("UPDATE commerce.checkout_attempts SET lease_until=clock_timestamp()-interval '1 second' WHERE actor_id=?",s.actor());
        var current=checkoutStore.acquire(s.actor(),key).orElseThrow();assertThat(current.revision()).isGreaterThan(old.revision());
        checkoutStore.complete(old,new CheckoutCatalog.Decision("OFFER_UNAVAILABLE",null));assertThat(checkoutStore.attempt(s.actor(),key).state()).isEqualTo("PROCESSING");
        jdbc.execute("ALTER TABLE commerce.orders ADD CONSTRAINT injected_failure CHECK(false) NOT VALID");
        try{assertThatThrownBy(()->checkoutStore.complete(current,new CheckoutCatalog.Decision(null,java.time.Instant.now().plusSeconds(900)))).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{jdbc.execute("ALTER TABLE commerce.orders DROP CONSTRAINT injected_failure");}
        assertThat(checkoutStore.attempt(s.actor(),key).state()).isEqualTo("PROCESSING");assertThat(jdbc.queryForObject("SELECT version FROM commerce.carts WHERE account_id=?",Long.class,s.actor())).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.cart_items WHERE account_id=?",Integer.class,s.actor())).isEqualTo(1);
        checkoutStore.complete(current,new CheckoutCatalog.Decision(null,java.time.Instant.now().plusSeconds(900)));assertThat(checkoutStore.attempt(s.actor(),key).state()).isEqualTo("ORDER_CREATED");
        checkoutStore.complete(old,new CheckoutCatalog.Decision("OFFER_UNAVAILABLE",null));assertThat(checkoutStore.attempt(s.actor(),key).state()).isEqualTo("ORDER_CREATED");
    }
    @Test void concurrentConfirmationsHaveOneClaimAndOneOrder()throws Exception {
        var s=preview();String key=UUID.randomUUID().toString();var ready=new java.util.concurrent.CountDownLatch(1);var proceed=new java.util.concurrent.CountDownLatch(1);
        rpc.holdHook=()->{ready.countDown();try{if(!proceed.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Hold not released");}catch(InterruptedException e){throw new AssertionError(e);}};
        var original=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return call(s.session(),"POST","/checkouts",confirmBody(s),key,true);}catch(Exception e){throw new RuntimeException(e);}});
        try{
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key,true).statusCode()).isEqualTo(202);
            assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
        }finally{proceed.countDown();}
        assertThat(original.get(5,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);assertThat(rpc.holdCalls.get()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.orders WHERE actor_id=?",Integer.class,s.actor())).isEqualTo(1);
    }
    @Test void recoveryWorkerReplaysHeldEvenAfterAcceptedDeadlinePasses()throws Exception {
        var s=preview();String key=UUID.randomUUID().toString();rpc.afterHoldFailure.set(io.grpc.Status.UNAVAILABLE);
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key,true).statusCode()).isEqualTo(202);var original=rpc.holdRequests.getFirst();
        jdbc.update("UPDATE commerce.checkout_previews SET expires_at=clock_timestamp()-interval '1 minute' WHERE id=?",UUID.fromString(s.preview()));
        jdbc.update("UPDATE commerce.checkout_attempts SET lease_until=clock_timestamp()-interval '1 second' WHERE actor_id=?",s.actor());rpc.afterHoldFailure.set(null);checkoutService.recover();
        assertThat(checkoutStore.attempt(s.actor(),UUID.fromString(key)).state()).isEqualTo("ORDER_CREATED");assertThat(rpc.holdRequests.getLast()).isEqualTo(original);
    }
    @Test void rpcRunsWithoutLocalTransactionAndAttemptExistsBeforeHold()throws Exception {
        var s=preview();rpc.holdHook=()->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((com.zaxxer.hikari.HikariDataSource)dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.checkout_attempts WHERE actor_id=?",Integer.class,s.actor())).isEqualTo(1);
        };
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),UUID.randomUUID().toString(),true).statusCode()).isEqualTo(200);
    }
    @Test void malformedHoldResultRemainsProcessingAndHeldUsesCatalogAuthorityDespiteLocalExpiry()throws Exception {
        var s=preview();String key=UUID.randomUUID().toString();rpc.holdOverride.set(com.agora.contracts.catalog.v1.CheckoutHoldResult.getDefaultInstance());
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key,true).statusCode()).isEqualTo(202);
        rpc.holdOverride.set(null);rpc.afterHoldFailure.set(io.grpc.Status.UNAVAILABLE);jdbc.update("UPDATE commerce.checkout_attempts SET lease_until=clock_timestamp()-interval '1 second' WHERE actor_id=?",s.actor());
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key,true).statusCode()).isEqualTo(202);
        var held=rpc.decisions.values().iterator().next().getReservation();long now=CartRpcFixture.micros();rpc.holdOverride.set(com.agora.contracts.catalog.v1.CheckoutHoldResult.newBuilder().setReservation(held.toBuilder().setCreatedAtEpochMicros(now-901000000).setExpiresAtEpochMicros(now-1000000)).build());
        rpc.afterHoldFailure.set(null);jdbc.update("UPDATE commerce.checkout_attempts SET lease_until=clock_timestamp()-interval '1 second' WHERE actor_id=?",s.actor());
        var result=call(s.session(),"POST","/checkouts",confirmBody(s),key,true);assertThat(result.statusCode()).isEqualTo(200);assertThat(json.readTree(result.body()).path("state").asString()).isEqualTo("ORDER_CREATED");
        var order=call(s.session(),"GET","/orders/"+json.readTree(result.body()).path("orderId").asString(),null,null,false);assertThat(json.readTree(order.body()).path("state").asString()).isEqualTo("AWAITING_PAYMENT");
    }
    @Test void localDeadlineDoesNotExpireOrderAndListUsesPrivateStableCursor()throws Exception {
        var s=preview();var result=call(s.session(),"POST","/checkouts",confirmBody(s),UUID.randomUUID().toString(),true);UUID id=UUID.fromString(json.readTree(result.body()).path("orderId").asString());
        jdbc.update("UPDATE commerce.orders SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
        assertThat(json.readTree(call(s.session(),"GET","/orders/"+id,null,null,false).body()).path("state").asString()).isEqualTo("AWAITING_PAYMENT");
        // Duplicate immutable snapshots under distinct attempts to exercise the 20-row page boundary.
        for(int n=0;n<21;n++){
            UUID preview=UUID.randomUUID(),checkout=UUID.randomUUID(),order=UUID.randomUUID(),key=UUID.randomUUID();
            jdbc.update("INSERT INTO commerce.checkout_previews SELECT ?,actor_id,?,request_hash,snapshot,expires_at FROM commerce.checkout_previews WHERE id=?",preview,key,UUID.fromString(s.preview()));
            jdbc.update("INSERT INTO commerce.checkout_attempts(actor_id,request_key,preview_id,checkout_id) VALUES(?,?,?,?)",s.actor(),key,preview,checkout);
            jdbc.update("INSERT INTO commerce.orders(id,actor_id,checkout_id,snapshot,created_at,expires_at,cart_cleared) SELECT ?,actor_id,?,snapshot,created_at,expires_at,cart_cleared FROM commerce.orders WHERE id=?",order,checkout,id);
        }
        var page=json.readTree(call(s.session(),"GET","/orders",null,null,false).body());assertThat(page.path("items").size()).isEqualTo(20);String cursor=page.path("nextCursor").asString();
        assertThat(page.path("items").get(0).propertyNames()).containsExactlyInAnyOrder("id","state","shopId","shopName","currency","totalVnd","createdAt","expiresAt","itemCount");
        var second=json.readTree(call(s.session(),"GET","/orders?cursor="+cursor,null,null,false).body());assertThat(second.path("items").size()).isEqualTo(2);assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(call(rpc.account(),"GET","/orders?cursor="+cursor,null,null,false).statusCode()).isEqualTo(422);
    }

    @Test void checkoutCatalogBoundaryRejectsUnsafeVersionsTtlReasonsAndDuplicateLots()throws Exception {
        var s=preview();UUID key=UUID.randomUUID();checkoutStore.claim(s.actor(),key,UUID.fromString(s.preview()));var work=checkoutStore.acquire(s.actor(),key).orElseThrow();
        checkoutCatalog.hold(work);var held=rpc.decisions.values().iterator().next().getReservation();var item=held.getItems(0);
        var malformed=List.of(held.toBuilder().setExpiresAtEpochMicros(held.getExpiresAtEpochMicros()+1).build(),held.toBuilder().setReleaseReason(com.agora.contracts.catalog.v1.ReservationReleaseReason.EXPIRED).build(),held.toBuilder().setState(com.agora.contracts.catalog.v1.ReservationState.RELEASED).build(),held.toBuilder().setItems(0,item.toBuilder().clearLots().addLots(item.getLots(0).toBuilder().setQuantity(1)).addLots(item.getLots(0).toBuilder().setQuantity(1))).build());
        for(var bad:malformed){rpc.holdOverride.set(com.agora.contracts.catalog.v1.CheckoutHoldResult.newBuilder().setReservation(bad).build());assertThatThrownBy(()->checkoutCatalog.hold(work)).isInstanceOf(com.agora.commerce.domain.CartFailure.class);}
        var offer=rpc.offers.values().iterator().next();
        rpc.quoteOverride.set(com.agora.contracts.catalog.v1.QuoteCheckoutResponse.newBuilder().setShopId(offer.getShopId()).setShopName(offer.getShopName()).setExpiresAtEpochMicros(CartRpcFixture.micros()+300000000).addOffers(com.agora.contracts.catalog.v1.CheckoutOffer.newBuilder().setProductId(offer.getProductId()).setSkuId(offer.getSkuId()).setQuantity(2).setProductName(offer.getProductName()).setSkuLabel(offer.getSkuLabel()).setNetWeightGrams(offer.getNetWeightGrams()).setUnitPriceVnd(offer.getPriceVnd()).setProductVersion(9007199254740992L)).build());
        assertThatThrownBy(()->checkoutCatalog.quote(UUID.randomUUID(),CartService.cartId(s.actor()),1,UUID.fromString(offer.getShopId()),List.of(new com.agora.commerce.domain.Cart.Item(UUID.fromString(offer.getProductId()),UUID.fromString(offer.getSkuId()),2)))).isInstanceOf(com.agora.commerce.domain.CartFailure.class);
    }
    @Test void checkoutBoundaryRejectsConsumptionMetadataOnHeldAndAcceptsUnavailableRelease()throws Exception {
        var s=preview();UUID key=UUID.randomUUID();checkoutStore.claim(s.actor(),key,UUID.fromString(s.preview()));var work=checkoutStore.acquire(s.actor(),key).orElseThrow();checkoutCatalog.hold(work);
        var held=rpc.decisions.values().iterator().next().getReservation();
        rpc.holdOverride.set(com.agora.contracts.catalog.v1.CheckoutHoldResult.newBuilder().setReservation(held.toBuilder().setConsumedCaptureId(UUID.randomUUID().toString()).setConsumedAtEpochMicros(held.getCreatedAtEpochMicros())).build());
        assertThatThrownBy(()->checkoutCatalog.hold(work)).isInstanceOf(com.agora.commerce.domain.CartFailure.class);
        rpc.holdOverride.set(com.agora.contracts.catalog.v1.CheckoutHoldResult.newBuilder().setReservation(held.toBuilder().setState(com.agora.contracts.catalog.v1.ReservationState.RELEASED).setReleaseReason(com.agora.contracts.catalog.v1.ReservationReleaseReason.UNAVAILABLE)).build());
        assertThat(checkoutCatalog.hold(work).failure()).isEqualTo("RESERVATION_RELEASED");
    }

    @Test void newPreviewRangeIsCheckedBeforeWriteButExistingPreviewReplays()throws Exception {
        var s=preview();var offer=rpc.offers.values().iterator().next();UUID sku=UUID.fromString(offer.getSkuId());
        String body="{\"cartId\":\""+CartService.cartId(s.actor())+"\",\"cartVersion\":1,\"shopId\":\""+offer.getShopId()+"\",\"skuIds\":[\""+offer.getSkuId()+"\"]}";
        rpc.offers.put(sku,offer.toBuilder().setPriceVnd(2499).build());String key=UUID.randomUUID().toString();
        assertThat(call(s.session(),"POST","/checkout-previews",body,key,true).statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.checkout_previews WHERE actor_id=?",Integer.class,s.actor())).isEqualTo(1);
        rpc.offers.put(sku,offer.toBuilder().setPriceVnd(2500).build());assertThat(call(s.session(),"POST","/checkout-previews",body,key,true).statusCode()).isEqualTo(200);
        rpc.offers.put(sku,offer.toBuilder().setPriceVnd(1).build());assertThat(call(s.session(),"POST","/checkout-previews",body,key,true).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE commerce.cart_items SET quantity=99 WHERE account_id=?",s.actor());rpc.offers.put(sku,offer.toBuilder().setPriceVnd(1000000000).build());
        assertThat(call(s.session(),"POST","/checkout-previews",body,UUID.randomUUID().toString(),true).statusCode()).isEqualTo(422);
    }
    @Test void unclaimedLegacyAmountRejectedButExistingAttemptStillReconciles()throws Exception {
        var s=preview();UUID preview=UUID.fromString(s.preview()),key=UUID.randomUUID();
        jdbc.update("UPDATE commerce.checkout_previews SET snapshot=jsonb_set(snapshot,'{preview,totalVnd}','4999') WHERE id=?",preview);
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key.toString(),true).statusCode()).isEqualTo(422);assertThat(rpc.holdCalls.get()).isZero();
        jdbc.update("UPDATE commerce.checkout_previews SET snapshot=jsonb_set(snapshot,'{preview,totalVnd}','10000000000') WHERE id=?",preview);
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key.toString(),true).statusCode()).isEqualTo(422);assertThat(rpc.holdCalls.get()).isZero();
        jdbc.update("UPDATE commerce.checkout_previews SET snapshot=jsonb_set(snapshot,'{preview,totalVnd}','70000') WHERE id=?",preview);
        rpc.afterHoldFailure.set(io.grpc.Status.UNAVAILABLE);assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key.toString(),true).statusCode()).isEqualTo(202);
        jdbc.update("UPDATE commerce.checkout_previews SET snapshot=jsonb_set(snapshot,'{preview,totalVnd}','4999') WHERE id=?",preview);
        rpc.afterHoldFailure.set(null);jdbc.update("UPDATE commerce.checkout_attempts SET lease_until=clock_timestamp()-interval '1 second' WHERE actor_id=?",s.actor());
        assertThat(call(s.session(),"POST","/checkouts",confirmBody(s),key.toString(),true).statusCode()).isEqualTo(200);
    }

}
