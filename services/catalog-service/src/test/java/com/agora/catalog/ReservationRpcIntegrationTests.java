package com.agora.catalog;

import com.agora.catalog.application.ReservationService;
import com.agora.catalog.configuration.CatalogGrpcServer;
import com.agora.contracts.catalog.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.config.import=","agora.grpc.enabled=true","agora.grpc.port=0","agora.grpc.token="+RpcFixture.TOKEN})
@Import({CatalogApiTests.RemoteConfiguration.class,MediaApiTests.StorageConfiguration.class})
class ReservationRpcIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);p.add("agora.internal-token",()->RpcFixture.TOKEN);}
    @Autowired CatalogGrpcServer server;@Autowired JdbcTemplate jdbc;@Autowired RpcFixture rpc;@Autowired ReservationService service;@MockitoBean Clock clock;
    ManagedChannel channel;UUID shop,product,sku,lot,buyer,checkout;
    @BeforeEach void setup(){
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));when(clock.instant()).thenReturn(Instant.parse("2026-10-26T12:00:00.123456Z"));
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.port()).usePlaintext().disableRetry().maxInboundMessageSize(131072).build();
        String session=rpc.seller();shop=rpc.shops.get(rpc.sessions.get(session));buyer=UUID.randomUUID();checkout=UUID.randomUUID();product=UUID.randomUUID();sku=UUID.randomUUID();lot=UUID.randomUUID();UUID image=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.products VALUES(?,?,?,'Description','PRODUCE','FRESH','VN',null,'Cool','PUBLISHED',0,now(),now())",product,shop,"Product");
        jdbc.update("INSERT INTO catalog.skus VALUES(?,?,?,?,'Package',100,100,100,1,1,1,'PACKAGE',0)",sku,product,shop,sku.toString().toUpperCase(Locale.ROOT));
        String id="agora/products/"+product+"/"+image;
        jdbc.update("INSERT INTO catalog.image_uploads(id,actor_id,shop_id,product_id,key,request_hash,public_id,state,mime,bytes,width,height,provider_version,format,url) VALUES(?,?,?,?,?,?,?,'UPLOADED','image/png',68,1,1,1,'png',?)",image,buyer,shop,product,UUID.randomUUID(),"0".repeat(64),id,"https://res.cloudinary.com/test/image/upload/v1/"+id+".png");
        jdbc.update("INSERT INTO catalog.product_images VALUES(?,?,?,0)",image,product,shop);
        jdbc.update("INSERT INTO catalog.inventory_lots(id,product_id,shop_id,sku_id,code,expires_on,on_hand,state,version,created_at,updated_at) VALUES(?,?,?,?,'LOT','2026-10-28',3,'ACTIVE',0,now(),now())",lot,product,shop,sku);
    }
    @AfterEach void stop()throws Exception{rpc.hangMerchant=false;if(channel!=null)channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
    CatalogReservationsGrpc.CatalogReservationsBlockingStub client(){var h=new Metadata();h.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),RpcFixture.TOKEN);return CatalogReservationsGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(h)).withDeadlineAfter(10,TimeUnit.SECONDS);}
    HoldReservationRequest request(){return HoldReservationRequest.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(checkout.toString()).setShopId(shop.toString()).addLines(ReservationLine.newBuilder().setProductId(product.toString()).setSkuId(sku.toString()).setQuantity(2)).build();}
    ReservationReference reference(){return ReservationReference.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(checkout.toString()).build();}
    long held(){return jdbc.queryForObject("SELECT held FROM catalog.inventory_lots WHERE id=?",Long.class,lot);}
    @Test void realListenerSpringWiringAndPostgresHoldReplayReleaseAreAtomic(){
        var r=client().hold(request());assertThat(r.getState()).isEqualTo(ReservationState.HELD);assertThat(held()).isEqualTo(2);
        assertThat(r.getCreatedAtEpochMicros()).isEqualTo(1793016000123456L);assertThat(r.getExpiresAtEpochMicros()-r.getCreatedAtEpochMicros()).isEqualTo(900000000L);
        assertThat(client().hold(request())).isEqualTo(r);assertThat(client().get(reference())).isEqualTo(r);
        assertThatThrownBy(()->client().get(reference().toBuilder().setBuyerId(UUID.randomUUID().toString()).build())).isInstanceOfSatisfying(StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
        var released=client().release(reference());assertThat(released.getState()).isEqualTo(ReservationState.RELEASED);assertThat(released.getReleaseReason()).isEqualTo(ReservationReleaseReason.CANCELLED);
        assertThat(client().release(reference())).isEqualTo(released);assertThat(client().hold(request())).isEqualTo(released);assertThat(held()).isZero();
        assertThat(jdbc.queryForObject("SELECT on_hand FROM catalog.inventory_lots WHERE id=?",Long.class,lot)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.reservation_audit WHERE checkout_id=?",Long.class,checkout)).isEqualTo(2);
    }
    @Test void merchantOutageAndHiddenBlockedProductDoNotPreventReplayOrRelease(){
        var r=client().hold(request());int calls=rpc.publicCalls.get();rpc.hangMerchant=true;
        jdbc.update("UPDATE catalog.products SET status='HIDDEN' WHERE id=?",product);jdbc.update("UPDATE catalog.inventory_lots SET state='BLOCKED' WHERE id=?",lot);
        assertThat(client().hold(request())).isEqualTo(r);assertThat(client().release(reference()).getState()).isEqualTo(ReservationState.RELEASED);assertThat(held()).isZero();assertThat(rpc.publicCalls.get()).isEqualTo(calls);
    }
    @Test void expiryBoundaryViaGetAndWorkerNeverRevivesOldCheckout(){
        var r=client().hold(request());when(clock.instant()).thenReturn(Instant.parse("2026-10-26T12:15:00.123456Z"));
        var expired=client().get(reference());assertThat(expired.getReleaseReason()).isEqualTo(ReservationReleaseReason.EXPIRED);assertThat(held()).isZero();assertThat(client().hold(request())).isEqualTo(expired);
        checkout=UUID.randomUUID();client().hold(request());when(clock.instant()).thenReturn(Instant.parse("2026-10-26T12:30:00.123456Z"));
        assertThat(service.expire()).isGreaterThanOrEqualTo(1);assertThat(client().get(reference()).getReleaseReason()).isEqualTo(ReservationReleaseReason.EXPIRED);assertThat(held()).isZero();
    }
}
