package com.agora.catalog;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.config.import=")
@Import({CatalogApiTests.RemoteConfiguration.class,MediaApiTests.StorageConfiguration.class})
class InventoryApiTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);p.add("agora.internal-token",()->RpcFixture.TOKEN);
    }
    @Autowired RpcFixture rpc; @Autowired JdbcTemplate jdbc; @Autowired JsonMapper json;
    @MockitoBean Clock clock;
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    static String key(){return UUID.randomUUID().toString();}
    @BeforeEach void resetClock(){when(clock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));when(clock.instant()).thenReturn(Instant.parse("2026-10-24T03:00:00Z"));}
    HttpResponse<String> request(String method,String path,String body,String session,String key,String csrf)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/seller/products"+path)).header("Content-Type","application/json");
        if(session!=null)b.header("Cookie","AGORA_SESSION="+session);
        if(key!=null)b.header("Idempotency-Key",key);
        if(csrf!=null)b.header("X-CSRF-TOKEN",csrf);
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> request(String m,String p,String b,String s,String k)throws Exception{return request(m,p,b,s,k,"masked-csrf-fixture");}
    JsonNode tree(HttpResponse<String> r){return json.readTree(r.body());}
    JsonNode product(String s)throws Exception {
        var r=request("POST","",CatalogApiTests.BODY.replace("FISH-500",key().toUpperCase(Locale.ROOT)),s,key());
        assertThat(r.statusCode()).isEqualTo(201);return tree(r);
    }
    String path(JsonNode p){return "/"+p.path("id").asString()+"/lots";}
    String body(JsonNode p,String code){return "{\"skuId\":\""+p.path("skus").get(0).path("id").asString()+"\",\"code\":\""+code+"\",\"quantity\":12,\"producedOn\":null,\"expiresOn\":\"2026-10-25\"}";}
    JsonNode lot(JsonNode p,String s,String code)throws Exception {var r=request("POST",path(p),body(p,code),s,key());assertThat(r.statusCode()).isEqualTo(201);return tree(r);}
    String change(String status,long version){return "{\"status\":\""+status+"\",\"expectedVersion\":"+version+"}";}
    String statusPath(JsonNode p,JsonNode lot){return path(p)+"/"+lot.path("id").asString()+"/status";}
    @Test void createsListsBlocksReopensAndPreservesProductVersionSkusAndCounts()throws Exception {
        String s=rpc.seller();var p=product(s);var l=lot(p,s,"LOT_1");
        assertThat(l.path("onHand").asLong()).isEqualTo(12);assertThat(l.path("available").asLong()).isEqualTo(12);
        assertThat(l.path("held").asLong()).isZero();assertThat(l.path("allocated").asLong()).isZero();
        assertThat(l.path("version").asLong()).isZero();assertThat(l.path("status").asString()).isEqualTo("ACTIVE");
        var listed=request("GET",path(p),null,s,null);assertThat(listed.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(tree(listed).path("items").size()).isEqualTo(1);
        var blocked=request("PUT",statusPath(p,l),change("BLOCKED",0),s,key());assertThat(blocked.statusCode()).isEqualTo(200);
        assertThat(tree(blocked).path("status").asString()).isEqualTo("BLOCKED");
        var opened=request("PUT",statusPath(p,l),change("ACTIVE",1),s,key());assertThat(opened.statusCode()).isEqualTo(200);
        assertThat(tree(opened).path("onHand").asLong()).isEqualTo(12);
        assertThat(tree(request("GET","/"+p.path("id").asString(),null,s,null))).isEqualTo(p);
    }
    @Test void rejectsMalformedOversizeUnknownFieldsInvalidDatesAndForgedCounters()throws Exception {
        String s=rpc.seller();var p=product(s);String b=body(p,"DATES");
        for(String bad:new String[]{"", "{", b+"{}",b.replace("\"quantity\":12","\"quantity\":12,\"quantity\":13")," ".repeat(2049)})
            assertThat(request("POST",path(p),bad,s,key()).statusCode()).isEqualTo(400);
        for(String bad:new String[]{"null","[]",b.replace("\"quantity\":12","\"quantity\":1.5"),b.replace("\"quantity\":12","\"quantity\":0"),b.replace("\"quantity\":12","\"quantity\":1000001"),b.replace("2026-10-25","2026-02-30"),b.replace("2026-10-25","2026-10-24"),b.replace("null","\"2026-10-25\""),b.replace("null","\"0000-01-01\""),b.replace("\"quantity\":12","\"quantity\":12,\"held\":0"),b.replace("DATES","bad code")})
            assertThat(request("POST",path(p),bad,s,key()).statusCode()).isEqualTo(422);
    }
    @Test void checksCurrentPermissionCsrfAndOwnershipBeforeReadsAndReplay()throws Exception {
        String s=rpc.seller(),other=rpc.seller();var p=product(s);String k=key(),b=body(p,"AUTH");
        assertThat(request("POST",path(p),b,s,k).statusCode()).isEqualTo(201);
        assertThat(request("GET",path(p),null,null,null).statusCode()).isEqualTo(401);
        assertThat(request("POST",path(p),b,s,key(),null).statusCode()).isEqualTo(403);
        assertThat(request("GET",path(p),null,other,null).statusCode()).isEqualTo(404);
        assertThat(request("POST",path(p),b,other,k).statusCode()).isEqualTo(404);
        var shop=rpc.shops.remove(rpc.sessions.get(s));
        try{assertThat(request("POST",path(p),b,s,k).statusCode()).isEqualTo(403);}finally{rpc.shops.put(rpc.sessions.get(s),shop);}
        rpc.sessions.remove(s);assertThat(request("POST",path(p),b,s,k).statusCode()).isEqualTo(401);
    }
    @Test void replaysAcrossVietnamMidnightButFreshReadsExpireAndCannotUnblock()throws Exception {
        String s=rpc.seller();var p=product(s);String k=key(),b=body(p,"MIDNIGHT");
        var created=request("POST",path(p),b,s,k);assertThat(created.statusCode()).isEqualTo(201);var l=tree(created);
        assertThat(request("PUT",statusPath(p,l),change("BLOCKED",0),s,key()).statusCode()).isEqualTo(200);
        String openKey=key();var opened=request("PUT",statusPath(p,l),change("ACTIVE",1),s,openKey);assertThat(opened.statusCode()).isEqualTo(200);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-24T17:00:00Z"));
        assertThat(tree(request("POST",path(p),b,s,k))).isEqualTo(l);
        assertThat(tree(request("PUT",statusPath(p,l),change("ACTIVE",1),s,openKey))).isEqualTo(tree(opened));
        assertThat(tree(request("GET",path(p),null,s,null)).path("items").get(0).path("status").asString()).isEqualTo("EXPIRED");
        assertThat(request("PUT",statusPath(p,l),change("BLOCKED",2),s,key()).statusCode()).isEqualTo(200);
        assertThat(request("PUT",statusPath(p,l),change("ACTIVE",3),s,key()).statusCode()).isEqualTo(422);
    }
    @Test void bindsKeyToProductLotPayloadAndGuardsDuplicateCodeStateAndVersion()throws Exception {
        String s=rpc.seller();var p=product(s);var a=lot(p,s,"A");var b=lot(p,s,"B");String k=key();
        assertThat(request("POST",path(p),body(p,"A"),s,key()).statusCode()).isEqualTo(409);
        assertThat(request("PUT",statusPath(p,a),change("BLOCKED",0),s,k).statusCode()).isEqualTo(200);
        assertThat(request("PUT",statusPath(p,b),change("BLOCKED",0),s,k).statusCode()).isEqualTo(422);
        assertThat(request("PUT",statusPath(p,a),change("ACTIVE",1),s,k).statusCode()).isEqualTo(422);
        assertThat(request("PUT",statusPath(p,a),change("ACTIVE",0),s,key()).statusCode()).isEqualTo(409);
        assertThat(request("PUT",statusPath(p,a),change("BLOCKED",1),s,key()).statusCode()).isEqualTo(409);
        assertThat(request("POST",path(p),body(p,"OTHER").replace(p.path("skus").get(0).path("id").asString(),UUID.randomUUID().toString()),s,key()).statusCode()).isEqualTo(404);
    }
    @Test void referencedSkuSurvivesPriceLabelReorderButCannotBeDeletedOrRepacked()throws Exception {
        String s=rpc.seller();var p=product(s);var l=lot(p,s,"REFERENCE");
        var input=(tools.jackson.databind.node.ObjectNode)json.readTree(CatalogApiTests.BODY);
        var sku=(tools.jackson.databind.node.ObjectNode)p.path("skus").get(0).deepCopy();sku.remove(List.of("id","saleUnit"));
        sku.put("priceVnd",65000).put("label","Tên gói mới");
        var another=sku.deepCopy();another.put("code","SECOND_"+key().replace("-","").substring(0,8).toUpperCase(Locale.ROOT));
        input.set("skus",json.createArrayNode().add(another).add(sku));input.put("expectedVersion",0);
        var updated=request("PUT","/"+p.path("id").asString(),input.toString(),s,key());assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(tree(updated).path("skus").get(1).path("id")).isEqualTo(p.path("skus").get(0).path("id"));
        assertThat(tree(request("GET",path(p),null,s,null)).path("items").get(0).path("id")).isEqualTo(l.path("id"));
        input.put("expectedVersion",1);sku.put("netWeightGrams",499);
        assertThat(request("PUT","/"+p.path("id").asString(),input.toString(),s,key()).statusCode()).isEqualTo(409);
        input.set("skus",json.createArrayNode().add(another));
        assertThat(request("PUT","/"+p.path("id").asString(),input.toString(),s,key()).statusCode()).isEqualTo(409);
        assertThat(tree(request("GET","/"+p.path("id").asString(),null,s,null))).isEqualTo(tree(updated));
    }
    @Test void auditFailureRollsBackLotAndKeyAndRetryCreatesExactlyOnce()throws Exception {
        String s=rpc.seller();var p=product(s);String k=key(),b=body(p,"ROLLBACK");
        jdbc.execute("CREATE FUNCTION catalog.reject_inventory_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER reject_inventory_audit BEFORE INSERT ON catalog.inventory_audit FOR EACH ROW EXECUTE FUNCTION catalog.reject_inventory_audit()");
        try{assertThat(request("POST",path(p),b,s,k).statusCode()).isEqualTo(500);}finally{jdbc.execute("DROP TRIGGER reject_inventory_audit ON catalog.inventory_audit");jdbc.execute("DROP FUNCTION catalog.reject_inventory_audit()");}
        assertThat(tree(request("GET",path(p),null,s,null)).path("items").size()).isZero();
        assertThat(request("POST",path(p),b,s,k).statusCode()).isEqualTo(201);
        assertThat(request("POST",path(p),b,s,k).statusCode()).isEqualTo(201);
        assertThat(tree(request("GET",path(p),null,s,null)).path("items").size()).isEqualTo(1);
    }
    @Test void createLotAndRemoveSkuRaceHasOneWinnerAndNoOrphan()throws Exception {
        String s=rpc.seller();var p=product(s);String b=body(p,"RACE");
        var input=(tools.jackson.databind.node.ObjectNode)json.readTree(CatalogApiTests.BODY);
        ((tools.jackson.databind.node.ObjectNode)input.path("skus").get(0)).put("code","NEW_"+key().replace("-","").substring(0,8).toUpperCase(Locale.ROOT));input.put("expectedVersion",0);
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var create=executor.submit(()->{start.await();return request("POST",path(p),b,s,key()).statusCode();});
            var edit=executor.submit(()->{start.await();return request("PUT","/"+p.path("id").asString(),input.toString(),s,key()).statusCode();});
            start.countDown();int a=create.get(10,TimeUnit.SECONDS),c=edit.get(10,TimeUnit.SECONDS);
            assertThat(a==201&&c==409||a==404&&c==200).withFailMessage("create=%s edit=%s",a,c).isTrue();
        }
    }
    @Test void concurrentSameKeyCreatesOneLotAndOneAudit()throws Exception {
        String s=rpc.seller();var p=product(s);String b=body(p,"ONE"),k=key();
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var a=executor.submit(()->{start.await();return request("POST",path(p),b,s,k);});
            var c=executor.submit(()->{start.await();return request("POST",path(p),b,s,k);});
            start.countDown();var first=a.get(10,TimeUnit.SECONDS);var second=c.get(10,TimeUnit.SECONDS);
            assertThat(first.statusCode()).isIn(201,409);assertThat(second.statusCode()).isIn(201,409);
            assertThat(List.of(first.statusCode(),second.statusCode())).contains(201);
        }
        var replay=request("POST",path(p),b,s,k);assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(tree(request("GET",path(p),null,s,null)).path("items").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.inventory_audit WHERE lot_id=?",Long.class,UUID.fromString(tree(replay).path("id").asString()))).isEqualTo(1);
    }
    @Test void paginationAndDatabaseQuantityReferenceConstraintsAndSafeVersions()throws Exception {
        String s=rpc.seller();var p=product(s);var a=lot(p,s,"PAGE_A");lot(p,s,"PAGE_B");lot(p,s,"PAGE_C");
        var first=tree(request("GET",path(p)+"?page=0&pageSize=2",null,s,null));
        var second=tree(request("GET",path(p)+"?page=1&pageSize=2",null,s,null));
        assertThat(first.path("items").size()).isEqualTo(2);assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(first.path("totalItems").asLong()).isEqualTo(3);
        var ids=new HashSet<String>();first.path("items").forEach(item->ids.add(item.path("id").asString()));second.path("items").forEach(item->ids.add(item.path("id").asString()));assertThat(ids).hasSize(3);
        assertThat(request("GET",path(p)+"?pageSize=101",null,s,null).statusCode()).isEqualTo(422);
        UUID id=UUID.fromString(a.path("id").asString());
        for(String assignment:new String[]{"on_hand=-1","held=13","allocated=-1","held=10,allocated=3","sku_id='"+UUID.randomUUID()+"'"})
            assertThatThrownBy(()->jdbc.update("UPDATE catalog.inventory_lots SET "+assignment+" WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("UPDATE catalog.inventory_lots SET version=9007199254740991 WHERE id=?",id);
        assertThat(request("PUT",statusPath(p,a),change("BLOCKED",9007199254740991L),s,key()).statusCode()).isEqualTo(409);
        assertThat(request("PUT",statusPath(p,a),change("BLOCKED",-1),s,key()).statusCode()).isEqualTo(422);
        assertThat(request("PUT",statusPath(p,a),change("BLOCKED",9007199254740992L),s,key()).statusCode()).isEqualTo(422);
    }
    @Test void upgradesV3DataAndAddsInventoryWithoutChangingProductSkuOrIdempotencySnapshot(){
        jdbc.execute("CREATE DATABASE inventory_upgrade");
        String url=POSTGRES.getJdbcUrl().replace("/"+POSTGRES.getDatabaseName(),"/inventory_upgrade");
        var config=Flyway.configure().dataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword()).defaultSchema("catalog").schemas("catalog").createSchemas(true);
        config.target("3").load().migrate();var db=new JdbcTemplate(new DriverManagerDataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword()));
        UUID p=UUID.randomUUID(),shop=UUID.randomUUID(),sku=UUID.randomUUID();
        db.update("INSERT INTO catalog.products(id,shop_id,name,category,condition,origin,storage_instructions,status,version,created_at,updated_at) VALUES(?,?,'Upgrade lot','SEAFOOD','FRESH','VN','Cold','HIDDEN',3,now(),now())",p,shop);
        db.update("INSERT INTO catalog.skus VALUES(?,?,?,'UPGRADE_LOT','500 g',50000,500,600,20,10,5,'PACKAGE',0)",sku,p,shop);
        var saved=new com.agora.catalog.adapter.outbound.JdbcProductStore(db,json).owned(shop,p).orElseThrow();
        db.update("INSERT INTO catalog.idempotency_records(actor_id,shop_id,operation,key,request_hash,state,http_status,response_snapshot,resource_id) VALUES(?,?,'createProduct',?,?,'COMPLETED',201,?::jsonb,?)",UUID.randomUUID(),shop,UUID.randomUUID(),"0".repeat(64),json.writeValueAsString(saved),p);
        config.target("latest").load().migrate();
        assertThat(new com.agora.catalog.adapter.outbound.JdbcProductStore(db,json).owned(shop,p).orElseThrow()).isEqualTo(saved);
        assertThat(db.queryForObject("SELECT response_snapshot->>'status' FROM catalog.idempotency_records",String.class)).isEqualTo("HIDDEN");
        assertThat(db.queryForObject("SELECT count(*) FROM catalog.inventory_lots",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT condeferrable FROM pg_constraint WHERE conname='skus_product_id_position_key'",Boolean.class)).isTrue();
    }
}
