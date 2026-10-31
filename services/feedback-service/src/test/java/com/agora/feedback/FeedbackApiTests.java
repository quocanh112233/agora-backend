package com.agora.feedback;

import com.agora.feedback.application.FeedbackAccess;
import com.agora.feedback.domain.*;
import com.agora.feedback.domain.Feedback.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import="})
class FeedbackApiTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);p.add("agora.internal-token",()->"feedback-synthetic-test-token-000000001");}
    @MockitoBean FeedbackAccess access;
    @Autowired com.agora.feedback.application.FeedbackStore store;
    @Autowired JdbcTemplate jdbc;@Autowired JsonMapper json;@LocalServerPort int port;
    UUID buyer,other,order,sku,product,shop;
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE feedback.moderation_audit,feedback.reviews");resetMocks();buyer=UUID.randomUUID();other=UUID.randomUUID();order=UUID.randomUUID();sku=UUID.randomUUID();product=UUID.randomUUID();shop=UUID.randomUUID();
        when(access.resolve(any(),any(),anyBoolean())).thenAnswer(call->{String session=call.getArgument(0);if(session==null)throw new FeedbackFailure(401,"UNAUTHENTICATED","Session required.");if((boolean)call.getArgument(2)&&!"csrf".equals(call.getArgument(1)))throw new FeedbackFailure(403,"FORBIDDEN","CSRF required.");return new Actor("other".equals(session)?other:buyer,"admin".equals(session));});
        when(access.eligible(any(),any(),any())).thenAnswer(call->{if(!buyer.equals(call.getArgument(0))||!order.equals(call.getArgument(1))||!sku.equals(call.getArgument(2)))throw Feedback.missing();return new Line(buyer,order,sku,product,shop);});
        when(access.shop(any())).thenAnswer(call->buyer.equals(call.getArgument(0))?shop:UUID.randomUUID());
    }
    void resetMocks(){org.mockito.Mockito.reset(access);}
    String path(){return "/me/orders/"+order+"/items/"+sku+"/review";}
    HttpResponse<String> call(String session,String method,String path,String body,boolean csrf)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));if(session!=null)b.header("Cookie","AGORA_SESSION="+session);if(csrf)b.header("X-CSRF-TOKEN","csrf");if(body!=null)b.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode create()throws Exception{var r=call("buyer","POST",path(),"{\"rating\":5,\"content\":\" Fresh fish \"}",true);assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return json.readTree(r.body());}
    @Test void createDerivesIdentityAndRecoversOwnedReview()throws Exception{var r=create();assertThat(r.path("content").asString()).isEqualTo("Fresh fish");assertThat(r.path("productId").asString()).isEqualTo(product.toString());var get=call("buyer","GET",path(),null,false);assertThat(get.statusCode()).isEqualTo(200);assertThat(json.readTree(get.body())).isEqualTo(r);assertThat(get.headers().firstValue("Cache-Control")).hasValue("no-store");}
    @Test void samePayloadReplayIsSafeAndDifferentPayloadConflicts()throws Exception{var first=create();var replay=call("buyer","POST",path(),"{\"rating\":5,\"content\":\"Fresh fish\"}",true);assertThat(replay.statusCode()).isEqualTo(200);assertThat(json.readTree(replay.body())).isEqualTo(first);assertThat(call("buyer","POST",path(),"{\"rating\":4,\"content\":\"Changed\"}",true).statusCode()).isEqualTo(409);assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback.reviews",Integer.class)).isEqualTo(1);}
    @Test void concurrentCreateHasExactlyOneReview()throws Exception{
        try(var pool=Executors.newFixedThreadPool(6)){var start=new CountDownLatch(1);var results=new ArrayList<Future<HttpResponse<String>>>();for(int i=0;i<6;i++)results.add(pool.submit(()->{start.await();return call("buyer","POST",path(),"{\"rating\":5,\"content\":\"Fish\"}",true);}));start.countDown();var statuses=new ArrayList<Integer>();var ids=new HashSet<String>();for(var future:results){var r=future.get(15,TimeUnit.SECONDS);statuses.add(r.statusCode());ids.add(json.readTree(r.body()).path("id").asString());}assertThat(statuses).containsOnly(200,201);assertThat(Collections.frequency(statuses,201)).isEqualTo(1);assertThat(ids).hasSize(1);}
    }
    @Test void authenticationCsrfAndForeignLineFailClosed()throws Exception{
        assertThat(call(null,"POST",path(),"{}",true).statusCode()).isEqualTo(401);assertThat(call("buyer","POST",path(),"{}",false).statusCode()).isEqualTo(403);
        assertThat(call("other","POST",path(),"{\"rating\":5,\"content\":\"Fish\"}",true).statusCode()).isEqualTo(404);
        create();assertThat(call("other","GET",path(),null,false).statusCode()).isEqualTo(404);
    }
    @Test void pendingAndUnavailableCommerceNeverWrite()throws Exception{
        for(int status:List.of(409,503)){doThrow(new FeedbackFailure(status,"REVIEW_UNAVAILABLE","Unavailable")).when(access).eligible(any(),any(),any());assertThat(call("buyer","POST",path(),"{\"rating\":5,\"content\":\"Fish\"}",true).statusCode()).isEqualTo(status);assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback.reviews",Integer.class)).isZero();}
    }
    @Test void editsRequireOwnershipAndCurrentVersion()throws Exception{String id=create().path("id").asString();String body="{\"rating\":3,\"content\":\"Updated\",\"expectedVersion\":0}";assertThat(call("other","PATCH","/me/reviews/"+id,body,true).statusCode()).isEqualTo(404);assertThat(call("buyer","PATCH","/me/reviews/"+id,body,true).statusCode()).isEqualTo(200);assertThat(call("buyer","PATCH","/me/reviews/"+id,body,true).statusCode()).isEqualTo(409);var summaryResponse=call(null,"GET","/products/"+product+"/review-summary",null,false);assertThat(summaryResponse.statusCode()).as(summaryResponse.body()).isEqualTo(200);assertThat(json.readTree(summaryResponse.body()).path("averageRating").asDouble()).as(summaryResponse.body()).isEqualTo(3);}
    @Test void sellerReplyRequiresMatchingActiveShop()throws Exception{String id=create().path("id").asString(),body="{\"content\":\"Thank you\",\"expectedVersion\":0}";assertThat(call("other","PUT","/seller/reviews/"+id+"/reply",body,true).statusCode()).isEqualTo(404);var reply=call("buyer","PUT","/seller/reviews/"+id+"/reply",body,true);assertThat(reply.statusCode()).isEqualTo(200);assertThat(reply.body()).contains("Thank you").doesNotContain("buyerId","orderId","skuId");assertThat(call("buyer","PUT","/seller/reviews/"+id+"/reply",body,true).statusCode()).isEqualTo(409);}
    @Test void moderationAuditedHiddenExcludedAndEditsDoNotUnhide()throws Exception{
        String id=create().path("id").asString(),body="{\"hidden\":true,\"reason\":\"Spam\",\"expectedVersion\":0}";
        assertThat(call("buyer","PUT","/admin/reviews/"+id+"/visibility",body,true).statusCode()).isEqualTo(403);
        assertThat(call("admin","PUT","/admin/reviews/"+id+"/visibility",body,true).statusCode()).isEqualTo(200);
        assertThat(call("admin","PUT","/admin/reviews/"+id+"/visibility",body,true).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback.moderation_audit",Integer.class)).isEqualTo(1);
        assertThat(call("buyer","PATCH","/me/reviews/"+id,"{\"rating\":4,\"content\":\"Edited\",\"expectedVersion\":1}",true).statusCode()).isEqualTo(200);
        assertThat(json.readTree(call(null,"GET","/products/"+product+"/reviews",null,false).body()).path("items").size()).isZero();
        var summary=json.readTree(call(null,"GET","/products/"+product+"/review-summary",null,false).body());assertThat(summary.path("count").asLong()).isZero();assertThat(summary.path("averageRating").isNull()).as(summary.toString()).isTrue();
        assertThat(call("admin","PUT","/admin/reviews/"+id+"/visibility","{\"hidden\":false,\"reason\":\"Reviewed\",\"expectedVersion\":2}",true).statusCode()).isEqualTo(200);
        assertThat(json.readTree(call(null,"GET","/products/"+product+"/review-summary",null,false).body()).path("count").asLong()).isEqualTo(1);
    }
    @Test void publicProjectionNeverLeaksPrivateIdentityOrModeration()throws Exception{create();var r=call(null,"GET","/products/"+product+"/reviews",null,false);assertThat(r.statusCode()).isEqualTo(200);assertThat(r.body()).doesNotContain("buyerId","orderId","skuId","hidden","reason",buyer.toString(),order.toString());assertThat(json.readTree(r.body()).path("items").size()).isEqualTo(1);}
    @Test void strictJsonAndDomainLimitsRejectMassAssignment()throws Exception{
        for(String body:List.of("null","[]","{\"rating\":0,\"content\":\"Fish\"}","{\"rating\":6,\"content\":\"Fish\"}","{\"rating\":1.5,\"content\":\"Fish\"}","{\"rating\":5,\"content\":\" \"}","{\"rating\":5,\"content\":\"Fish\",\"productId\":\"fake\"}"))assertThat(call("buyer","POST",path(),body,true).statusCode()).as(body).isEqualTo(422);
        for(String body:List.of("{} {}","{\"rating\":5,\"rating\":4,\"content\":\"Fish\"}"," ".repeat(16385)))assertThat(call("buyer","POST",path(),body,true).statusCode()).isEqualTo(400);
        assertThat(call("buyer","POST",path(),"{\"rating\":5,\"content\":\""+"a".repeat(2001)+"\"}",true).statusCode()).isEqualTo(422);
        assertThat(call(null,"GET","/products/"+product+"/reviews?cursor=x&cursor=y",null,false).statusCode()).isEqualTo(422);
        assertThat(call(null,"GET","/products/"+product+"/reviews?unknown=1",null,false).statusCode()).isEqualTo(422);
    }
    @Test void publicPaginationHasNoDuplicatesAndRejectsBadCursor()throws Exception{
        for(int i=0;i<25;i++)jdbc.update("INSERT INTO feedback.reviews(id,buyer_id,order_id,sku_id,product_id,shop_id,rating,content) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),buyer,UUID.randomUUID(),sku,product,shop,4,"Fish");
        var first=json.readTree(call(null,"GET","/products/"+product+"/reviews",null,false).body());assertThat(first.path("items").size()).isEqualTo(20);String cursor=first.path("nextCursor").asString();var second=json.readTree(call(null,"GET","/products/"+product+"/reviews?cursor="+cursor,null,false).body());assertThat(second.path("items").size()).isEqualTo(5);assertThat(second.path("nextCursor").isNull()).isTrue();var ids=new HashSet<String>();first.path("items").forEach(x->ids.add(x.path("id").asString()));second.path("items").forEach(x->ids.add(x.path("id").asString()));assertThat(ids).hasSize(25);
        for(String bad:List.of("bad","x".repeat(513)))assertThat(call(null,"GET","/products/"+product+"/reviews?cursor="+bad,null,false).statusCode()).isEqualTo(422);
    }
    @Test void concurrentEditAndModerationCannotOverwriteEachOther()throws Exception{
        String id=create().path("id").asString();try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);
            var edit=pool.submit(()->{gate.await();return call("buyer","PATCH","/me/reviews/"+id,"{\"rating\":3,\"content\":\"Edited\",\"expectedVersion\":0}",true);});
            var hide=pool.submit(()->{gate.await();return call("admin","PUT","/admin/reviews/"+id+"/visibility","{\"hidden\":true,\"reason\":\"Spam\",\"expectedVersion\":0}",true);});gate.countDown();
            assertThat(List.of(edit.get(10,TimeUnit.SECONDS).statusCode(),hide.get(10,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,409);
            assertThat(jdbc.queryForObject("SELECT version FROM feedback.reviews WHERE id=?",Long.class,UUID.fromString(id))).isEqualTo(1);
        }
    }
    @Test void auditFailureRollsBackVisibilityAndVersion()throws Exception{
        String id=create().path("id").asString();jdbc.execute("CREATE FUNCTION feedback.fail_audit_probe() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic rollback' USING ERRCODE='23514'; END $$");jdbc.execute("CREATE TRIGGER fail_audit_probe BEFORE INSERT ON feedback.moderation_audit FOR EACH ROW EXECUTE FUNCTION feedback.fail_audit_probe()");
        try{assertThat(call("admin","PUT","/admin/reviews/"+id+"/visibility","{\"hidden\":true,\"reason\":\"Spam\",\"expectedVersion\":0}",true).statusCode()).isEqualTo(503);var review=store.owned(buyer,order,sku);assertThat(review.hidden()).isFalse();assertThat(review.version()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback.moderation_audit",Integer.class)).isZero();}
        finally{jdbc.execute("DROP TRIGGER fail_audit_probe ON feedback.moderation_audit");jdbc.execute("DROP FUNCTION feedback.fail_audit_probe()");}
    }
    @Test void invalidVersionsAndModerationReasonsNeverWrite()throws Exception{
        String id=create().path("id").asString();for(String version:List.of("-1","0.5","9007199254740991","9223372036854775808"))assertThat(call("buyer","PATCH","/me/reviews/"+id,"{\"rating\":5,\"content\":\"Fish\",\"expectedVersion\":"+version+"}",true).statusCode()).isEqualTo(422);
        for(String reason:List.of(" ","a".repeat(501)))assertThat(call("admin","PUT","/admin/reviews/"+id+"/visibility","{\"hidden\":true,\"reason\":\""+reason+"\",\"expectedVersion\":0}",true).statusCode()).isEqualTo(422);
        assertThat(store.owned(buyer,order,sku).version()).isZero();
    }
    @Test void unicodeLimitsCountCharactersForReviewReplyAndModeration()throws Exception{
        var response=call("buyer","POST",path(),"{\"rating\":5,\"content\":\""+"😀".repeat(2000)+"\"}",true);assertThat(response.statusCode()).as(response.body()).isEqualTo(201);String id=json.readTree(response.body()).path("id").asString();
        assertThat(call("buyer","PUT","/seller/reviews/"+id+"/reply","{\"content\":\""+"😀".repeat(2000)+"\",\"expectedVersion\":0}",true).statusCode()).isEqualTo(200);
        assertThat(call("admin","PUT","/admin/reviews/"+id+"/visibility","{\"hidden\":true,\"reason\":\""+"😀".repeat(500)+"\",\"expectedVersion\":1}",true).statusCode()).isEqualTo(200);
    }
    @Test void cursorTimestampsOutsideSupportedRangeAreValidationErrors()throws Exception{
        for(long micros:new long[]{Long.MIN_VALUE,Long.MAX_VALUE,-1,253402300800000000L}){String cursor=Base64.getUrlEncoder().withoutPadding().encodeToString((product+":"+micros+":"+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));var response=call(null,"GET","/products/"+product+"/reviews?cursor="+cursor,null,false);assertThat(response.statusCode()).as(response.body()).isEqualTo(422);}
    }
    @Test void managementReadsAreScopedAndDoNotExposeBuyerOrderOrSku()throws Exception{
        String id=create().path("id").asString();assertThat(call("admin","PUT","/admin/reviews/"+id+"/visibility","{\"hidden\":true,\"reason\":\"private reason\",\"expectedVersion\":0}",true).statusCode()).isEqualTo(200);
        for(String scope:List.of("seller","admin")){String session=scope.equals("seller")?"buyer":"admin";var read=call(session,"GET","/"+scope+"/reviews/"+id,null,false);assertThat(read.statusCode()).as(read.body()).isEqualTo(200);assertThat(json.readTree(read.body()).path("hidden").asBoolean()).isTrue();assertThat(read.body()).doesNotContain("buyerId","orderId","skuId","private reason");var list=call(session,"GET","/"+scope+"/reviews",null,false);assertThat(list.statusCode()).as(list.body()).isEqualTo(200);assertThat(json.readTree(list.body()).path("items").size()).isEqualTo(1);}
        assertThat(call("other","GET","/seller/reviews/"+id,null,false).statusCode()).isEqualTo(404);assertThat(call("buyer","GET","/admin/reviews/"+id,null,false).statusCode()).isEqualTo(403);assertThat(call(null,"GET","/seller/reviews",null,false).statusCode()).isEqualTo(401);
    }
    @Test void managementCursorBoundToActorScopeAndNotPublicCursor()throws Exception{
        for(int i=0;i<25;i++)jdbc.update("INSERT INTO feedback.reviews(id,buyer_id,order_id,sku_id,product_id,shop_id,rating,content) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),buyer,UUID.randomUUID(),sku,product,shop,4,"Fish");
        var first=json.readTree(call("admin","GET","/admin/reviews",null,false).body());assertThat(first.path("items").size()).isEqualTo(20);String cursor=first.path("nextCursor").asString();var next=json.readTree(call("admin","GET","/admin/reviews?cursor="+cursor,null,false).body());assertThat(next.path("items").size()).isEqualTo(5);
        assertThat(call("buyer","GET","/seller/reviews?cursor="+cursor,null,false).statusCode()).isEqualTo(422);assertThat(call(null,"GET","/products/"+product+"/reviews?cursor="+cursor,null,false).statusCode()).isEqualTo(422);
        var seller=json.readTree(call("buyer","GET","/seller/reviews",null,false).body());assertThat(seller.path("items").size()).isEqualTo(20);String sellerCursor=seller.path("nextCursor").asString();assertThat(call("other","GET","/seller/reviews?cursor="+sellerCursor,null,false).statusCode()).isEqualTo(422);
    }
    @Test void managementRecoveryReadsCurrentVersionAfterLostModerationResponse()throws Exception{
        String id=create().path("id").asString(),payload="{\"hidden\":true,\"reason\":\"Spam\",\"expectedVersion\":0}";assertThat(call("admin","PUT","/admin/reviews/"+id+"/visibility",payload,true).statusCode()).isEqualTo(200);assertThat(call("admin","PUT","/admin/reviews/"+id+"/visibility",payload,true).statusCode()).isEqualTo(409);var read=call("admin","GET","/admin/reviews/"+id,null,false);assertThat(read.statusCode()).isEqualTo(200);assertThat(json.readTree(read.body()).path("version").asLong()).isEqualTo(1);
    }
    @Test void managementFailsClosedOnMerchantOutageAndRejectsUnknownQuery()throws Exception{
        doThrow(Feedback.unavailable("MERCHANT")).when(access).shop(any());assertThat(call("buyer","GET","/seller/reviews",null,false).statusCode()).isEqualTo(503);assertThat(call("admin","GET","/admin/reviews?hidden=true",null,false).statusCode()).isEqualTo(422);
    }
    @Test void managementPaginationKeepsShopPrivacyAndTiedTimestampOrder()throws Exception{
        var expected=new HashSet<String>();
        for(int i=0;i<25;i++){UUID id=UUID.randomUUID();expected.add(id.toString());jdbc.update("INSERT INTO feedback.reviews(id,buyer_id,order_id,sku_id,product_id,shop_id,rating,content,hidden,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,buyer,UUID.randomUUID(),sku,product,shop,4,"Fish",i%2==0,java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00.123456Z")));}
        UUID foreignId=UUID.randomUUID();jdbc.update("INSERT INTO feedback.reviews(id,buyer_id,order_id,sku_id,product_id,shop_id,rating,content) VALUES(?,?,?,?,?,?,?,?)",foreignId,other,UUID.randomUUID(),sku,product,UUID.randomUUID(),3,"Other shop");
        var fields=Set.of("id","productId","shopId","rating","content","reply","hidden","version","createdAt","updatedAt");
        for(String scope:List.of("seller","admin")){
            String session=scope.equals("seller")?"buyer":"admin",cursor=null;var ids=new HashSet<String>();int pages=0;
            do{var response=call(session,"GET","/"+scope+"/reviews"+(cursor==null?"":"?cursor="+cursor),null,false);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");var page=json.readTree(response.body());assertThat(page.path("items").size()).isBetween(1,20);
                for(var item:page.path("items")){assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(fields);assertThat(ids.add(item.path("id").asString())).isTrue();if(scope.equals("seller"))assertThat(item.path("shopId").asString()).isEqualTo(shop.toString());}
                cursor=page.path("nextCursor").isNull()?null:page.path("nextCursor").asString();assertThat(++pages).isLessThanOrEqualTo(2);
            }while(cursor!=null);
            var wanted=new HashSet<>(expected);if(scope.equals("admin"))wanted.add(foreignId.toString());assertThat(ids).isEqualTo(wanted);
        }
    }
    @Test void managementReadsRecheckCurrentRoleAndActiveShop()throws Exception{
        String id=create().path("id").asString();
        assertThat(call("admin","GET","/admin/reviews/"+id,null,false).statusCode()).isEqualTo(200);
        doReturn(new Actor(buyer,false)).when(access).resolve(eq("admin"),any(),eq(false));
        for(String suffix:List.of("","/"+id))assertThat(call("admin","GET","/admin/reviews"+suffix,null,false).statusCode()).isEqualTo(403);
        assertThat(call("buyer","GET","/seller/reviews/"+id,null,false).statusCode()).isEqualTo(200);
        doThrow(new FeedbackFailure(403,"FORBIDDEN","Active shop required.")).when(access).shop(buyer);
        for(String suffix:List.of("","/"+id))assertThat(call("buyer","GET","/seller/reviews"+suffix,null,false).statusCode()).isEqualTo(403);
    }
    @Test void managementRejectsMalformedOutOfRangeAndPublicCursors()throws Exception{
        for(String scope:List.of("seller","admin")){
            String session=scope.equals("seller")?"buyer":"admin",binding=scope.equals("seller")?"seller-"+shop:"admin";
            var bad=new ArrayList<>(List.of("bad","x".repeat(257),""));
            for(String micros:List.of("-1","253402300800000000",Long.toString(Long.MAX_VALUE),"not-a-number"))bad.add(Base64.getUrlEncoder().withoutPadding().encodeToString((binding+":"+micros+":"+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            bad.add(Base64.getUrlEncoder().withoutPadding().encodeToString((product+":0:"+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            for(String cursor:bad){var response=call(session,"GET","/"+scope+"/reviews?cursor="+cursor,null,false);assertThat(response.statusCode()).as(response.body()).isEqualTo(422);}
            assertThat(call(session,"GET","/"+scope+"/reviews?cursor=x&cursor=y",null,false).statusCode()).isEqualTo(422);
        }
    }
    @Test void summaryMapsPostgresNumericAndEmptyAggregate(){assertThat(store.summary(product).averageRating()).isNull();jdbc.update("INSERT INTO feedback.reviews(id,buyer_id,order_id,sku_id,product_id,shop_id,rating,content) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),buyer,order,sku,product,shop,3,"Fish");assertThat(store.summary(product).averageRating()).isEqualTo(3.0);}
    @Test void databaseConstraintsRejectInvalidRatingAndDuplicateLine()throws Exception{create();assertThatThrownBy(()->jdbc.update("UPDATE feedback.reviews SET rating=6")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);assertThatThrownBy(()->jdbc.update("INSERT INTO feedback.reviews SELECT gen_random_uuid(),buyer_id,order_id,sku_id,product_id,shop_id,rating,content,reply,hidden,version,created_at,updated_at FROM feedback.reviews")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);}
}
