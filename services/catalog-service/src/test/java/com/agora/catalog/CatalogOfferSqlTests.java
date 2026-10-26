package com.agora.catalog;

import com.agora.catalog.application.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="spring.config.import=")
@Import({CatalogApiTests.RemoteConfiguration.class,MediaApiTests.StorageConfiguration.class})
class CatalogOfferSqlTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(System.getProperty("agora.test.postgres-image","postgres:16.15-alpine"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);p.add("agora.internal-token",()->RpcFixture.TOKEN);}
    @MockitoSpyBean JdbcTemplate jdbc;@Autowired StorefrontStore store;@Autowired RpcFixture rpc;
    @MockitoSpyBean DataSource dataSource;
    final LocalDate today=LocalDate.of(2026,10,24);
    record Seed(UUID product,UUID shop,UUID sku,UUID image,UUID lot){}
    Seed seed() {
        String session=rpc.seller();UUID shop=rpc.shops.get(rpc.sessions.get(session)),p=UUID.randomUUID(),sku=UUID.randomUUID(),image=UUID.randomUUID(),lot=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.products VALUES(?,?,?,'Private description','PRODUCE','FRESH','VN','Private producer','Private storage','PUBLISHED',0,now(),now())",p,shop,"Public product");
        jdbc.update("INSERT INTO catalog.skus VALUES(?,?,?,?,'Package',70000,1000,1100,20,15,10,'PACKAGE',0)",sku,p,shop,sku.toString().toUpperCase(Locale.ROOT));
        String reserved="agora/products/"+p+"/"+image;
        jdbc.update("INSERT INTO catalog.image_uploads(id,actor_id,shop_id,product_id,key,request_hash,public_id,state,mime,bytes,width,height,provider_version,format,url) VALUES(?,?,?,?,?,?,?,'UPLOADED','image/png',68,1,1,1,'png',?)",image,rpc.sessions.get(session),shop,p,UUID.randomUUID(),"0".repeat(64),reserved,"https://res.cloudinary.com/test/image/upload/v1/"+reserved+".png");
        jdbc.update("INSERT INTO catalog.product_images VALUES(?,?,?,0)",image,p,shop);
        jdbc.update("INSERT INTO catalog.inventory_lots(id,product_id,shop_id,sku_id,code,expires_on,on_hand,held,allocated,state,version,created_at,updated_at) VALUES(?,?,?,?,'LOT','2026-10-25',12,0,0,'ACTIVE',0,now(),now())",lot,p,shop,sku);
        return new Seed(p,shop,sku,image,lot);
    }
    StorefrontStore.Offer offer(Seed p) {return store.offers(List.of(p.sku()),today).getFirst();}
    @Test void readsRequestedSkuPriceAndStockInExactlyOneDatabaseSnapshot() throws Exception {
        var p=seed();UUID cheaper=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.skus VALUES(?,?,?,?,'Cheap package',1,100,100,1,1,1,'PACKAGE',1)",cheaper,p.product(),p.shop(),cheaper.toString().toUpperCase(Locale.ROOT));
        clearInvocations(dataSource,jdbc);var rows=store.offers(List.of(p.sku(),cheaper,UUID.randomUUID()),today);
        verify(dataSource,times(1)).getConnection();assertThat(rows).hasSize(2);
        verify(jdbc,times(1)).query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class));
        assertThat(rows).filteredOn(r->r.skuId().equals(p.sku())).singleElement().satisfies(r->{assertThat(r.priceVnd()).isEqualTo(70000);assertThat(r.inStock()).isTrue();assertThat(r.productId()).isEqualTo(p.product());});
        assertThat(rows).filteredOn(r->r.skuId().equals(cheaper)).singleElement().satisfies(r->{assertThat(r.priceVnd()).isEqualTo(1);assertThat(r.inStock()).isFalse();});
    }
    @Test void onlyCurrentPublishedProductsWithAttachedUploadedPhotoAreEligible() {
        var p=seed();assertThat(offer(p).thumbnail().url()).contains(p.image().toString());
        UUID second=UUID.randomUUID();String publicId="agora/products/"+p.product()+"/"+second;
        jdbc.update("INSERT INTO catalog.image_uploads(id,actor_id,shop_id,product_id,key,request_hash,public_id,state,mime,bytes,width,height,provider_version,format,url) SELECT ?,actor_id,shop_id,product_id,?,request_hash,?,'UPLOADED',mime,bytes,width,height,provider_version,format,? FROM catalog.image_uploads WHERE id=?",
                second,UUID.randomUUID(),publicId,"https://res.cloudinary.com/test/image/upload/v1/"+publicId+".png",p.image());
        jdbc.update("INSERT INTO catalog.product_images VALUES(?,?,?,1)",second,p.product(),p.shop());
        assertThat(offer(p).thumbnail().url()).contains(p.image().toString());
        jdbc.update("UPDATE catalog.image_uploads SET state='DELETE_PENDING' WHERE id=?",p.image());
        assertThat(offer(p).thumbnail().url()).contains(second.toString());
        jdbc.update("UPDATE catalog.image_uploads SET state='UPLOADED' WHERE id=?",p.image());
        for(String state:List.of("DRAFT","HIDDEN")){
            jdbc.update("UPDATE catalog.products SET status=? WHERE id=?",state,p.product());assertThat(store.offers(List.of(p.sku()),today)).isEmpty();
        }
        jdbc.update("UPDATE catalog.products SET status='PUBLISHED' WHERE id=?",p.product());
        jdbc.update("UPDATE catalog.image_uploads SET state='DELETE_PENDING' WHERE product_id=?",p.product());assertThat(store.offers(List.of(p.sku()),today)).isEmpty();
        jdbc.update("UPDATE catalog.image_uploads SET state='UPLOADED' WHERE id=?",p.image());
        jdbc.update("DELETE FROM catalog.product_images WHERE product_id=?",p.product());assertThat(store.offers(List.of(p.sku()),today)).isEmpty();
    }
    @Test void stockIsBooleanExistsWithActiveUnexpiredAvailableLotsAndReflectsEveryRead() {
        var p=seed();assertThat(offer(p).inStock()).isTrue();
        for(String change:List.of("state='BLOCKED'","state='ACTIVE',held=12","held=0,allocated=12","allocated=0,on_hand=0","on_hand=12,expires_on='2026-10-24'")){
            jdbc.update("UPDATE catalog.inventory_lots SET "+change+" WHERE id=?",p.lot());assertThat(offer(p).inStock()).isFalse();
        }
        jdbc.update("UPDATE catalog.inventory_lots SET expires_on='2026-10-25' WHERE id=?",p.lot());assertThat(offer(p).inStock()).isTrue();
        assertThat(store.offers(List.of(p.sku()),today.plusDays(1)).getFirst().inStock()).isFalse();
        jdbc.update("UPDATE catalog.skus SET price_vnd=80000,label='Current label' WHERE id=?",p.sku());assertThat(offer(p).priceVnd()).isEqualTo(80000);assertThat(offer(p).skuLabel()).isEqualTo("Current label");
    }
    @Test void merchantLookupRunsAfterDatabaseConnectionHasBeenReleased() {
        var p=seed();clearInvocations(dataSource);
        PublicShops shops=ids->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
            return Map.of(p.shop(),new PublicShops.Shop(p.shop(),"Current public shop"));
        };
        var service=new CatalogOffersService(store,shops,Clock.fixed(Instant.parse("2026-10-24T03:00:00Z"),ZoneOffset.UTC));
        assertThat(service.resolve(List.of(p.sku()))).singleElement().satisfies(row->assertThat(row.shopName()).isEqualTo("Current public shop"));
    }
}
