package com.agora.commerce;

import com.agora.contracts.catalog.v1.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.config.import=")
@Import(CartApiTests.RemoteConfiguration.class)
class CartApiTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);p.add("agora.internal-token",()->CartRpcFixture.TOKEN);}
    @TestConfiguration(proxyBeanMethods=false) static class RemoteConfiguration {
        @Bean(destroyMethod="close") CartRpcFixture cartRpcFixture()throws Exception{return new CartRpcFixture();}
        @Bean(destroyMethod="") @Primary @Qualifier("identityChannel") io.grpc.ManagedChannel testIdentityChannel(CartRpcFixture r){return r.identityChannel;}
        @Bean(destroyMethod="") @Primary @Qualifier("catalogChannel") io.grpc.ManagedChannel testCatalogChannel(CartRpcFixture r){return r.catalogChannel;}
    }
    @Autowired CartRpcFixture rpc;@Autowired JdbcTemplate jdbc;@Autowired JsonMapper json;@LocalServerPort int port;
    @MockitoSpyBean javax.sql.DataSource dataSource;
    final HttpClient http=HttpClient.newHttpClient();String session;
    @BeforeEach void reset(){jdbc.execute("TRUNCATE commerce.cart_requests,commerce.cart_items,commerce.carts");rpc.catalogFailure.set(null);rpc.identityFailure.set(null);rpc.override.set(null);rpc.catalogCalls.set(0);rpc.catalogHook=()->{};rpc.offers.clear();session=rpc.account();}
    HttpRequest request(String account,String method,String path,String body,String key,boolean csrf){
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/cart"+path));
        if(account!=null)b.header("Cookie","AGORA_SESSION="+account);if(csrf)b.header("X-CSRF-TOKEN","csrf-fixture");if(key!=null)b.header("Idempotency-Key",key);
        if(body!=null)b.header("Content-Type","application/json");return b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> send(String account,String method,String path,String body,String key,boolean csrf)throws Exception{return http.send(request(account,method,path,body,key,csrf),HttpResponse.BodyHandlers.ofString());}
    JsonNode tree(HttpResponse<String> r){return json.readTree(r.body());}
    HttpResponse<String> get(String account)throws Exception{return send(account,"GET","",null,null,false);}
    String cartId(String account)throws Exception{return tree(get(account)).path("cartId").asString();}
    String putBody(String id,CartOffer offer,int quantity,long version){return json.createObjectNode().put("cartId",id).put("productId",offer.getProductId()).put("quantity",quantity).put("expectedVersion",version).toString();}
    String deleteBody(String id,long version){return json.createObjectNode().put("cartId",id).put("expectedVersion",version).toString();}
    HttpResponse<String> put(String id,CartOffer offer,int quantity,long version)throws Exception{return send(session,"PUT","/items/"+offer.getSkuId(),putBody(id,offer,quantity,version),UUID.randomUUID().toString(),true);}
    @Test void emptyAuthenticatedGetDoesNotWriteAndProjectionIsFreshAndPrivate()throws Exception{
        var empty=get(session);assertThat(empty.statusCode()).isEqualTo(200);assertThat(empty.headers().firstValue("Cache-Control")).hasValue("no-store");
        String id=tree(empty).path("cartId").asString();assertThat(id).matches("[0-9a-f-]{36}");assertThat(tree(empty).path("version").asLong()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.carts",Integer.class)).isZero();
        var offer=rpc.offer();assertThat(put(id,offer,3,0).statusCode()).isEqualTo(200);
        rpc.offers.put(UUID.fromString(offer.getSkuId()),offer.toBuilder().setPriceVnd(45000).build());
        var cart=tree(get(session));assertThat(cart.propertyNames()).containsExactlyInAnyOrder("cartId","version","items");var item=cart.path("items").get(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("productId","skuId","quantity","offer");assertThat(item.path("offer").path("priceVnd").asLong()).isEqualTo(45000);
        assertThat(cart.toString()).doesNotContain("ownerAccountId","contactPhone","pickupAddress","onHand","held","allocated","uploadId",rpc.sessions.get(session).toString());
        assertThat(tree(get(rpc.account())).path("items").size()).isZero();
    }
    @Test void lostPutResponseReplaysWithoutCatalogAfterHideOrOutageAndHashMismatchFailsBeforeRpc()throws Exception{
        String id=cartId(session);var offer=rpc.offer();String key=UUID.randomUUID().toString(),body=putBody(id,offer,2,0),path="/items/"+offer.getSkuId();
        var first=send(session,"PUT",path,body,key,true);assertThat(first.statusCode()).isEqualTo(200);int calls=rpc.catalogCalls.get();rpc.catalogFailure.set(io.grpc.Status.UNAVAILABLE);
        var replay=send(session,"PUT",path,body,key,true);assertThat(replay.statusCode()).isEqualTo(200);assertThat(replay.body()).isEqualTo(first.body());assertThat(rpc.catalogCalls.get()).isEqualTo(calls);
        assertThat(send(session,"PUT",path,putBody(id,offer,3,0),key,true).statusCode()).isEqualTo(422);
        assertThat(send(session,"PUT",path,body,key,false).statusCode()).isEqualTo(403);rpc.sessions.remove(session);assertThat(send(session,"PUT",path,body,key,true).statusCode()).isEqualTo(401);
    }
    @Test void currentAvailabilityAndMissingOffersDoNotReserveStockAndRemainRemovable()throws Exception{
        String id=cartId(session);var offer=rpc.offer();assertThat(put(id,offer,99,0).statusCode()).isEqualTo(200);
        rpc.offers.put(UUID.fromString(offer.getSkuId()),offer.toBuilder().setInStock(false).build());assertThat(tree(get(session)).path("items").get(0).path("offer").path("inStock").asBoolean()).isFalse();
        assertThat(put(id,offer,2,1).statusCode()).isEqualTo(409);rpc.offers.clear();assertThat(tree(get(session)).path("items").get(0).path("offer").isNull()).isTrue();
        var removed=send(session,"DELETE","/items/"+offer.getSkuId(),deleteBody(id,1),UUID.randomUUID().toString(),true);assertThat(removed.statusCode()).isEqualTo(200);assertThat(tree(removed).path("version").asLong()).isEqualTo(2);
    }
    @Test void deletesMoreThanOneKnownLineWhileCatalogIsDown()throws Exception{
        String id=cartId(session);var first=rpc.offer();var second=rpc.offer();assertThat(put(id,first,1,0).statusCode()).isEqualTo(200);assertThat(put(id,second,1,1).statusCode()).isEqualTo(200);
        rpc.catalogFailure.set(io.grpc.Status.UNAVAILABLE);assertThat(get(session).statusCode()).isEqualTo(503);int calls=rpc.catalogCalls.get();
        for(var entry:List.of(Map.entry(first,2L),Map.entry(second,3L))){var r=send(session,"DELETE","/items/"+entry.getKey().getSkuId(),deleteBody(id,entry.getValue()),UUID.randomUUID().toString(),true);assertThat(r.statusCode()).isEqualTo(200);}
        assertThat(rpc.catalogCalls.get()).isEqualTo(calls);assertThat(get(session).statusCode()).isEqualTo(200);assertThat(tree(get(session)).path("items").size()).isZero();
    }
    @Test void staleCartIdentityCannotMutateAChangedAccountEvenWithEqualVersion()throws Exception{
        String oldId=cartId(session),other=rpc.account(),otherId=cartId(other);assertThat(otherId).isNotEqualTo(oldId);var offer=rpc.offer();
        assertThat(send(other,"PUT","/items/"+offer.getSkuId(),putBody(oldId,offer,1,0),UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
        assertThat(send(other,"DELETE","/items/"+offer.getSkuId(),deleteBody(oldId,0),UUID.randomUUID().toString(),true).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.carts",Integer.class)).isZero();
    }
    @Test void enforcesTwentySkuCapAndOptimisticVersionsAcrossShops()throws Exception{
        String id=cartId(session);for(int i=0;i<20;i++)assertThat(put(id,rpc.offer(),1,i).statusCode()).isEqualTo(200);
        var extra=rpc.offer();assertThat(put(id,extra,1,20).statusCode()).isEqualTo(409);assertThat(put(id,extra,1,0).statusCode()).isEqualTo(409);
        assertThat(tree(get(session)).path("items").size()).isEqualTo(20);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.cart_items",Integer.class)).isEqualTo(20);
    }
    @Test void strictJsonPathsKeysQueriesAndFieldsAreValidated()throws Exception{
        String id=cartId(session);var offer=rpc.offer();String path="/items/"+offer.getSkuId(),valid=putBody(id,offer,1,0),key=UUID.randomUUID().toString();
        for(String body:List.of(valid.replace("\"quantity\":1","\"quantity\":0"),valid.replace("\"quantity\":1","\"quantity\":100"),valid.replace("\"quantity\":1","\"quantity\":1.5"),valid.replace("\"expectedVersion\":0","\"expectedVersion\":9007199254740992"),valid.replace("\"productId\"","\"unknown\"")))assertThat(send(session,"PUT",path,body,key,true).statusCode()).isEqualTo(422);
        assertThat(send(session,"PUT",path,"{",key,true).statusCode()).isEqualTo(400);
        assertThat(send(session,"PUT",path,valid+" {}",key,true).statusCode()).isEqualTo(400);
        assertThat(send(session,"PUT",path,valid.replace("\"quantity\":1","\"quantity\":1,\"quantity\":2"),key,true).statusCode()).isEqualTo(400);
        assertThat(send(session,"PUT","/items/invalid",valid,key,true).statusCode()).isEqualTo(422);
        assertThat(send(session,"PUT",path,valid,"1-1-1-1-1",true).statusCode()).isEqualTo(422);
        assertThat(send(session,"GET","?page=0",null,null,false).statusCode()).isEqualTo(422);
    }
    @Test void malformedOrMismatchedCatalogPayloadsFailClosed()throws Exception{
        String id=cartId(session);var offer=rpc.offer();
        for(CartOffer wrong:List.of(offer.toBuilder().setSkuId(UUID.randomUUID().toString()).build(),offer.toBuilder().setShopName(" ").build(),offer.toBuilder().setPriceVnd(0).build(),offer.toBuilder().setThumbnail(offer.getThumbnail().toBuilder().setUrl("https://evil.test/image.png")).build())){
            rpc.override.set(ResolveOffersResponse.newBuilder().addOffers(wrong).build());assertThat(put(id,offer,1,0).statusCode()).isEqualTo(503);
        }
        rpc.override.set(ResolveOffersResponse.newBuilder().addOffers(offer).addOffers(offer).build());assertThat(put(id,offer,1,0).statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.cart_items",Integer.class)).isZero();
    }
    @Test void sameKeyConcurrencyCommitsExactlyOneMutation()throws Exception{
        String id=cartId(session);var offer=rpc.offer();String key=UUID.randomUUID().toString(),body=putBody(id,offer,4,0),path="/items/"+offer.getSkuId();
        var a=http.sendAsync(request(session,"PUT",path,body,key,true),HttpResponse.BodyHandlers.ofString());var b=http.sendAsync(request(session,"PUT",path,body,key,true),HttpResponse.BodyHandlers.ofString());
        assertThat(a.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(200);assertThat(b.get(10,TimeUnit.SECONDS).body()).isEqualTo(a.get().body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.cart_requests",Integer.class)).isEqualTo(1);assertThat(tree(get(session)).path("version").asLong()).isEqualTo(1);
    }
    @Test void sameVersionConcurrentDifferentIntentsHaveOneWinner()throws Exception{
        String id=cartId(session);var offer=rpc.offer();String path="/items/"+offer.getSkuId();
        var a=http.sendAsync(request(session,"PUT",path,putBody(id,offer,2,0),UUID.randomUUID().toString(),true),HttpResponse.BodyHandlers.ofString());var b=http.sendAsync(request(session,"PUT",path,putBody(id,offer,3,0),UUID.randomUUID().toString(),true),HttpResponse.BodyHandlers.ofString());
        assertThat(List.of(a.get(10,TimeUnit.SECONDS).statusCode(),b.get(10,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.cart_requests",Integer.class)).isEqualTo(1);assertThat(tree(get(session)).path("version").asLong()).isEqualTo(1);
    }
    @Test void failedRequestRecordInsertRollsBackCartHeaderItemsAndVersion()throws Exception{
        String id=cartId(session);var offer=rpc.offer();jdbc.execute("ALTER TABLE commerce.cart_requests ADD CONSTRAINT injected_failure CHECK(false) NOT VALID");
        try{assertThat(put(id,offer,1,0).statusCode()).isEqualTo(500);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.carts",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.cart_items",Integer.class)).isZero();}
        finally{jdbc.execute("ALTER TABLE commerce.cart_requests DROP CONSTRAINT injected_failure");}
    }
    @Test void databaseOutageAndIdentityOutageAreSafeAndDoNotBypassAuthentication()throws Exception{
        assertThat(get(null).statusCode()).isEqualTo(401);rpc.identityFailure.set(io.grpc.Status.UNAVAILABLE.withDescription("private identity host"));assertThat(get(session).statusCode()).isEqualTo(503);rpc.identityFailure.set(null);
        org.mockito.Mockito.doThrow(new java.sql.SQLException("private JDBC password","08006")).when(dataSource).getConnection();
        try{var failed=get(session);assertThat(failed.statusCode()).isEqualTo(503);assertThat(failed.body()).doesNotContain("JDBC","password","host");}finally{org.mockito.Mockito.reset(dataSource);}
    }
    @Test void catalogRpcRunsAfterCartDatabaseConnectionsHaveBeenReleased()throws Exception{
        String id=cartId(session);var offer=rpc.offer();
        rpc.catalogHook=()->assertThat(((com.zaxxer.hikari.HikariDataSource)dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(put(id,offer,2,0).statusCode()).isEqualTo(200);assertThat(get(session).statusCode()).isEqualTo(200);
    }
    @Test void deleteReplayDoesNotRemoveALineReaddedAfterTheOriginalDelete()throws Exception{
        String id=cartId(session);var offer=rpc.offer();assertThat(put(id,offer,1,0).statusCode()).isEqualTo(200);
        String key=UUID.randomUUID().toString(),body=deleteBody(id,1),path="/items/"+offer.getSkuId();
        var removed=send(session,"DELETE",path,body,key,true);assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(put(id,offer,3,2).statusCode()).isEqualTo(200);
        assertThat(send(session,"DELETE",path,body,key,true).body()).isEqualTo(removed.body());
        var current=tree(get(session));assertThat(current.path("version").asLong()).isEqualTo(3);assertThat(current.path("items").get(0).path("quantity").asInt()).isEqualTo(3);
    }
    @Test void retentionCleanupKeepsRecentKeysAndRemovesOnlyExpiredRecords()throws Exception{
        String id=cartId(session);var offer=rpc.offer();assertThat(put(id,offer,1,0).statusCode()).isEqualTo(200);assertThat(put(id,offer,2,1).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE commerce.cart_requests SET created_at=now()-interval '8 days' WHERE response_version=1");
        new com.agora.commerce.adapter.outbound.JdbcCartStore(jdbc).cleanupRequests();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce.cart_requests",Integer.class)).isEqualTo(1);
        assertThat(tree(get(session)).path("version").asLong()).isEqualTo(2);
    }
}
