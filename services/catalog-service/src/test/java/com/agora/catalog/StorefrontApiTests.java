package com.agora.catalog;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.agora.contracts.merchant.v1.*;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.config.import=")
@Import({CatalogApiTests.RemoteConfiguration.class,MediaApiTests.StorageConfiguration.class})
class StorefrontApiTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);p.add("agora.internal-token",()->RpcFixture.TOKEN);}
    @Autowired JdbcTemplate jdbc;@Autowired JsonMapper json;@Autowired RpcFixture rpc;@MockitoBean Clock clock;@LocalServerPort int port;
    @MockitoSpyBean javax.sql.DataSource dataSource;
    final HttpClient http=HttpClient.newHttpClient();
    record Seed(UUID id,UUID shop,UUID sku,UUID image,UUID lot){}
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE catalog.checkout_decisions,catalog.reservation_audit,catalog.reservation_allocations,catalog.reservation_lines,catalog.reservations,catalog.inventory_audit,catalog.inventory_requests,catalog.inventory_lots,catalog.product_images,catalog.image_uploads,catalog.media_requests,catalog.audit_entries,catalog.idempotency_records,catalog.skus,catalog.products");
        rpc.merchantFailure.set(null);rpc.publicResponse.set(null);rpc.hangMerchant=false;rpc.publicCalls.set(0);rpc.publicBatchSizes.clear();rpc.publicFailAt.set(0);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));when(clock.instant()).thenReturn(Instant.parse("2026-10-24T03:00:00Z"));
    }
    Seed seed(String name){
        String s=rpc.seller();UUID shop=rpc.shops.get(rpc.sessions.get(s)),p=UUID.randomUUID(),sku=UUID.randomUUID(),image=UUID.randomUUID(),lot=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.products(id,shop_id,name,description,category,condition,origin,producer_name,storage_instructions,status,version,created_at,updated_at) VALUES(?,?,?,'Mô tả công khai','PRODUCE','FRESH','Việt Nam',null,'Bảo quản mát','PUBLISHED',0,now(),now())",p,shop,name);
        jdbc.update("INSERT INTO catalog.skus VALUES(?,?,?,?,'Gói 500 g',35000,500,600,20,15,10,'PACKAGE',0)",sku,p,shop,sku.toString().toUpperCase(Locale.ROOT));
        String reserved="agora/products/"+p+"/"+image;
        jdbc.update("INSERT INTO catalog.image_uploads(id,actor_id,shop_id,product_id,key,request_hash,public_id,state,mime,bytes,width,height,provider_version,format,url) VALUES(?,?,?,?,?,?,?,'UPLOADED','image/png',68,1,1,1,'png',?)",image,rpc.sessions.get(s),shop,p,UUID.randomUUID(),"0".repeat(64),reserved,"https://res.cloudinary.com/storefront-test/image/upload/v1/"+reserved+".png");
        jdbc.update("INSERT INTO catalog.product_images VALUES(?,?,?,0)",image,p,shop);
        jdbc.update("INSERT INTO catalog.inventory_lots(id,product_id,shop_id,sku_id,code,expires_on,on_hand,held,allocated,state,version,created_at,updated_at) VALUES(?,?,?,?,'LOT','2026-10-25',12,0,0,'ACTIVE',0,now(),now())",lot,p,shop,sku);
        return new Seed(p,shop,sku,image,lot);
    }
    HttpResponse<String> get(String path)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/products"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode tree(HttpResponse<String> r){return json.readTree(r.body());}
    String encoded(String x){return URLEncoder.encode(x,StandardCharsets.UTF_8);}
    @Test void anonymousListDetailExposeOnlyPublicProjectionAndNoStore()throws Exception{
        var p=seed("Rau công khai");var r=get("");assertThat(r.statusCode()).isEqualTo(200);assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var card=tree(r).path("items").get(0);assertThat(card.path("id").asString()).isEqualTo(p.id().toString());assertThat(card.propertyNames()).containsExactlyInAnyOrder("id","name","category","condition","origin","shop","minPriceVnd","inStock","thumbnail");
        assertThat(card.path("shop").propertyNames()).containsExactlyInAnyOrder("id","name");assertThat(card.path("inStock").asBoolean()).isTrue();
        var detail=tree(get("/"+p.id()));assertThat(detail.propertyNames()).containsExactlyInAnyOrder("id","name","category","condition","origin","shop","inStock","description","producerName","storageInstructions","skus","images");
        assertThat(detail.path("skus").get(0).propertyNames()).containsExactlyInAnyOrder("id","label","priceVnd","netWeightGrams","saleUnit","inStock");
        assertThat(detail.toString()).doesNotContain("onHand","held","allocated","ownerAccountId","contactPhone","pickupAddress","version","uploadId",p.lot().toString());
        assertThat(rpc.identityCalls.get()).isZero();assertThat(rpc.publicCalls.get()).isEqualTo(2);
    }
    @Test void hiddenDraftMissingPhotoAndNonActiveShopAreNeverPublic()throws Exception{
        for(String state:List.of("DRAFT","HIDDEN")){
            var p=seed("Không hiện "+state);jdbc.update("UPDATE catalog.products SET status=? WHERE id=?",state,p.id());assertThat(get("/"+p.id()).statusCode()).isEqualTo(404);
        }
        var missing=seed("Chưa có ảnh");jdbc.update("DELETE FROM catalog.product_images WHERE product_id=?",missing.id());assertThat(get("/"+missing.id()).statusCode()).isEqualTo(404);
        var inactive=seed("Shop bị đình chỉ");rpc.shops.entrySet().removeIf(e->e.getValue().equals(inactive.shop()));assertThat(get("/"+inactive.id()).statusCode()).isEqualTo(404);
        assertThat(tree(get("")).path("items").size()).isZero();
    }
    @Test void freshReadsReflectHideShopRevocationAndVietnamMidnightWithoutWorkers()throws Exception{
        var p=seed("Theo thời gian");assertThat(tree(get("/"+p.id())).path("inStock").asBoolean()).isTrue();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-24T17:00:00Z"));
        var expired=tree(get("/"+p.id()));assertThat(expired.path("inStock").asBoolean()).isFalse();assertThat(expired.path("skus").get(0).path("inStock").asBoolean()).isFalse();
        jdbc.update("UPDATE catalog.products SET status='HIDDEN' WHERE id=?",p.id());assertThat(get("/"+p.id()).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE catalog.products SET status='PUBLISHED' WHERE id=?",p.id());rpc.shops.entrySet().removeIf(e->e.getValue().equals(p.shop()));assertThat(get("/"+p.id()).statusCode()).isEqualTo(404);
    }
    @Test void blockedExhaustedAndAllocatedLotsDoNotAdvertiseStock()throws Exception{
        var p=seed("Có lô tồn");
        for(String update:List.of("state='BLOCKED'","state='ACTIVE',held=12","held=0,allocated=12","allocated=0,on_hand=0")){
            jdbc.update("UPDATE catalog.inventory_lots SET "+update+" WHERE id=?",p.lot());assertThat(tree(get("/"+p.id())).path("inStock").asBoolean()).isFalse();
        }
    }
    @Test void filtersAreLiteralAndPriceMeansMinimumAdvertisedPackage()throws Exception{
        var p=seed("Rau%_\\ sạch");seed("Rau thường");
        assertThat(tree(get("?q="+encoded("%_\\"))).path("items").size()).isEqualTo(1);
        assertThat(tree(get("?q="+encoded("' OR 1=1 --"))).path("items").size()).isZero();
        assertThat(tree(get("?category=SEAFOOD")).path("items").size()).isZero();
        assertThat(tree(get("?origin="+encoded("VIỆT NAM")+"&shopId="+p.shop()+"&minPriceVnd=35000&maxPriceVnd=35000")).path("items").size()).isEqualTo(1);
        assertThat(tree(get("?minPriceVnd=35001")).path("items").size()).isZero();
    }
    @Test void cursorAdvancesAcrossInactiveCandidateWindowWithoutTotalOrDuplicates()throws Exception{
        var oldest=seed("Cuối cùng hoạt động");for(int i=0;i<20;i++){var p=seed("Ẩn shop "+i);rpc.shops.entrySet().removeIf(e->e.getValue().equals(p.shop()));}
        var first=tree(get(""));assertThat(first.path("items").size()).isZero();assertThat(first.path("nextCursor").isString()).isTrue();assertThat(first.has("totalItems")).isFalse();
        var second=tree(get("?cursor="+first.path("nextCursor").asString()));assertThat(second.path("items").size()).isEqualTo(1);assertThat(second.path("items").get(0).path("id").asString()).isEqualTo(oldest.id().toString());assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(get("?q=other&cursor="+first.path("nextCursor").asString()).statusCode()).isEqualTo(422);
    }
    @Test void validatesDuplicateUnknownQueriesAndMalformedCursorsBeforeSql()throws Exception{
        for(String query:List.of("?q=a&q=b","?unknown=x","?category=OTHER","?minPriceVnd=-1","?minPriceVnd=100&maxPriceVnd=1","?cursor=invalid","?shopId=x"))assertThat(get(query).statusCode()).isEqualTo(422);
        assertThat(get("/invalid").statusCode()).isEqualTo(422);
    }
    @Test void merchantOutageAndUntrustedResponsesFailClosedAndDoNotLeakDiagnostics()throws Exception{
        var p=seed("Phụ thuộc shop");rpc.merchantFailure.set(io.grpc.Status.UNAVAILABLE.withDescription("private SQL owner account"));
        var failed=get("");assertThat(failed.statusCode()).isEqualTo(503);assertThat(failed.body()).doesNotContain("SQL","owner account");rpc.merchantFailure.set(null);
        for(var response:List.of(
                FindPublicShopsResponse.newBuilder().addShops(PublicShop.newBuilder().setId(UUID.randomUUID().toString()).setName("Wrong shop")).build(),
                FindPublicShopsResponse.newBuilder().addShops(PublicShop.newBuilder().setId(p.shop().toString()).setName(" ")).build(),
                FindPublicShopsResponse.newBuilder().addShops(PublicShop.newBuilder().setId(p.shop().toString()).setName("a")).addShops(PublicShop.newBuilder().setId(p.shop().toString()).setName("a")).build())){
            rpc.publicResponse.set(response);assertThat(get("/"+p.id()).statusCode()).isEqualTo(503);
        }
    }
    @Test void boundsShopCallsAndFailsWholePageWhenLaterBatchFails()throws Exception{
        for(int i=0;i<21;i++)seed("Công khai "+i);
        var r=get("");assertThat(r.statusCode()).isEqualTo(200);assertThat(tree(r).path("items").size()).isEqualTo(20);
        assertThat(rpc.publicBatchSizes).containsExactly(5,5,5,5);assertThat(rpc.publicDeadline.get()).isBetween(1L,2000L);
        rpc.publicCalls.set(0);rpc.publicFailAt.set(2);
        var failed=get("");assertThat(failed.statusCode()).isEqualTo(503);assertThat(tree(failed).has("items")).isFalse();assertThat(rpc.publicCalls.get()).isEqualTo(2);
    }
    @Test void equalTimestampsUseUuidTieBreakWithoutLosingOrRepeatingCandidates()throws Exception{
        var ids=new ArrayList<String>();for(int i=0;i<21;i++)ids.add(seed("Đồng thời "+i).id().toString());
        jdbc.update("UPDATE catalog.products SET created_at='2026-10-24T03:00:00.123456Z'");ids.sort(Comparator.reverseOrder());
        var first=tree(get(""));var actual=new ArrayList<String>();first.path("items").forEach(p->actual.add(p.path("id").asString()));
        var second=tree(get("?cursor="+first.path("nextCursor").asString()));second.path("items").forEach(p->actual.add(p.path("id").asString()));
        assertThat(actual).containsExactlyElementsOf(ids);assertThat(second.path("nextCursor").isNull()).isTrue();
    }
    @Test void minimumPriceIncludesOutOfStockSkusAndProductStockIncludesOtherSku()throws Exception{
        var p=seed("Hai gói hàng");jdbc.update("UPDATE catalog.inventory_lots SET state='BLOCKED' WHERE id=?",p.lot());
        UUID sku=UUID.randomUUID();jdbc.update("INSERT INTO catalog.skus VALUES(?,?,?,?,'Gói 1 kg',70000,1000,1100,20,15,10,'PACKAGE',1)",sku,p.id(),p.shop(),sku.toString().toUpperCase(Locale.ROOT));
        jdbc.update("UPDATE catalog.inventory_lots SET sku_id=? WHERE id=?",sku,p.lot());jdbc.update("UPDATE catalog.inventory_lots SET state='ACTIVE' WHERE id=?",p.lot());
        var r=tree(get("?minPriceVnd=35000&maxPriceVnd=35000"));assertThat(r.path("items").get(0).path("minPriceVnd").asLong()).isEqualTo(35000);assertThat(r.path("items").get(0).path("inStock").asBoolean()).isTrue();
        assertThat(tree(get("?minPriceVnd=70000")).path("items").size()).isZero();
        var detail=tree(get("/"+p.id()));assertThat(detail.path("skus").get(0).path("inStock").asBoolean()).isFalse();assertThat(detail.path("skus").get(1).path("inStock").asBoolean()).isTrue();
    }
    @Test void hangingMerchantUsesDeadlineAndReturnsSafe503()throws Exception{
        seed("Đợi cửa hàng");rpc.hangMerchant=true;long started=System.nanoTime();
        try{assertThat(get("").statusCode()).isEqualTo(503);assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(5));assertThat(rpc.publicCalls.get()).isEqualTo(1);}
        finally{rpc.hangMerchant=false;}
    }
    @Test void jdbcConnectionOutageReturns503ForBothPublicReadsWithoutPrivateDiagnostics()throws Exception{
        var p=seed("Kiểm tra kết nối");
        org.mockito.Mockito.doThrow(new java.sql.SQLException("private JDBC host/password","08006")).when(dataSource).getConnection();
        try{
            for(String path:List.of("","/"+p.id())){
                var failed=get(path);assertThat(failed.statusCode()).isEqualTo(503);
                assertThat(tree(failed).path("code").asString()).isEqualTo("CATALOG_UNAVAILABLE");
                assertThat(failed.body()).doesNotContain("JDBC","host","password");
                assertThat(failed.headers().firstValue("Cache-Control")).hasValue("no-store");
            }
            assertThat(rpc.publicCalls.get()).isZero();
        }finally{org.mockito.Mockito.reset(dataSource);}
    }
}
