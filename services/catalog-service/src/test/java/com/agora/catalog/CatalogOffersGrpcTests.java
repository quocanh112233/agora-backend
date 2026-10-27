package com.agora.catalog;

import com.agora.catalog.adapter.outbound.GrpcPublicShops;
import com.agora.catalog.adapter.inbound.ReservationRpc;
import com.agora.catalog.adapter.inbound.CheckoutRpc;
import com.agora.catalog.application.*;
import com.agora.catalog.configuration.CatalogGrpcServer;
import com.agora.contracts.catalog.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogOffersGrpcTests {
    final StorefrontStore store=mock(StorefrontStore.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-24T17:00:00Z"),ZoneOffset.UTC);
    RpcFixture rpc;
    CatalogGrpcServer server;
    ManagedChannel channel;
    @BeforeEach void start() throws Exception {
        rpc=new RpcFixture();
        server=new CatalogGrpcServer(new CatalogOffersService(store,new GrpcPublicShops(rpc.merchantChannel,RpcFixture.TOKEN),clock),new ReservationRpc(mock(ReservationService.class)),new CheckoutRpc(mock(CheckoutService.class)),0,RpcFixture.TOKEN);
        server.start();
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.port()).usePlaintext().disableRetry().build();
    }
    @AfterEach void stop() throws Exception {
        if(channel!=null)channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        if(server!=null)server.stop();
        if(rpc!=null)rpc.close();
    }
    CatalogOffersGrpc.CatalogOffersBlockingStub client(String token) {
        var stub=CatalogOffersGrpc.newBlockingStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS);
        if(token==null)return stub;
        var headers=new Metadata();headers.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    ResolveOffersResponse find(List<String> ids) {
        return client(RpcFixture.TOKEN).resolveOffers(ResolveOffersRequest.newBuilder().addAllSkuIds(ids).build());
    }
    void denied(List<String> ids,Status.Code code) {
        assertThatThrownBy(()->find(ids)).isInstanceOfSatisfying(StatusRuntimeException.class,e->{
            assertThat(e.getStatus().getCode()).isEqualTo(code);
            assertThat(e.getStatus().getDescription()).isNull();
        });
    }
    StorefrontStore.Offer offer() {
        String session=rpc.seller();UUID shop=rpc.shops.get(rpc.sessions.get(session));
        UUID product=UUID.randomUUID(),sku=UUID.randomUUID();
        return new StorefrontStore.Offer(product,sku,shop,"Public product","Package",70000,1000,true,
                new StorefrontStore.Image("https://res.cloudinary.com/test/image/upload/v1/agora/products/"+product+"/"+UUID.randomUUID()+".png",1,1));
    }
    List<StorefrontStore.Offer> rows(int count) {
        var rows=new ArrayList<StorefrontStore.Offer>();for(int i=0;i<count;i++)rows.add(offer());
        when(store.offers(anyList(),any())).thenReturn(rows);return rows;
    }
    List<String> ids(List<StorefrontStore.Offer> rows) {return rows.stream().map(row->row.skuId().toString()).toList();}

    @Test void acceptsOneThroughTwentyDistinctStrictUuidsAndUsesVietnamDate() {
        for(int count=1;count<=20;count++){
            var rows=rows(count);var response=find(ids(rows));
            assertThat(response.getOffersList()).extracting(CartOffer::getSkuId).containsExactlyInAnyOrderElementsOf(ids(rows));
        }
        verify(store,times(20)).offers(anyList(),eq(LocalDate.of(2026,10,25)));
        assertThat(rpc.identityCalls.get()).isZero();assertThat(rpc.merchantCalls.get()).isZero();
    }
    @Test void rejectsEmptyOverTwentyMalformedAndDuplicateIdsBeforeAnyRead() {
        denied(List.of(),Status.Code.INVALID_ARGUMENT);
        denied(java.util.stream.IntStream.range(0,21).mapToObj(i->UUID.randomUUID().toString()).toList(),Status.Code.INVALID_ARGUMENT);
        String id=UUID.randomUUID().toString();
        for(String invalid:List.of("","bad","1-1-1-1-1",id+" ",id.replace("-","")))denied(List.of(invalid),Status.Code.INVALID_ARGUMENT);
        denied(List.of(id,id),Status.Code.INVALID_ARGUMENT);denied(List.of(id,id.toUpperCase(Locale.ROOT)),Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(store);assertThat(rpc.publicCalls.get()).isZero();
    }
    @Test void acceptsUppercaseCanonicalIdAndNormalizesIt() {
        var rows=rows(1);assertThat(find(List.of(rows.getFirst().skuId().toString().toUpperCase(Locale.ROOT))).getOffersCount()).isEqualTo(1);
        verify(store).offers(eq(List.of(rows.getFirst().skuId())),any());
    }
    @Test void returnsOnlyExplicitOfferProjectionAndOmitsInactiveShops() {
        var rows=rows(2);var visible=rows.getFirst();rpc.shops.values().remove(rows.getLast().shopId());
        var response=find(ids(rows));assertThat(response.getOffersCount()).isEqualTo(1);
        assertThat(response.getOffers(0)).isEqualTo(CartOffer.newBuilder().setProductId(visible.productId().toString())
                .setSkuId(visible.skuId().toString()).setShopId(visible.shopId().toString()).setShopName("Cửa hàng công khai")
                .setProductName(visible.productName()).setSkuLabel(visible.skuLabel()).setPriceVnd(70000).setNetWeightGrams(1000).setInStock(true)
                .setThumbnail(OfferImage.newBuilder().setUrl(visible.thumbnail().url()).setWidth(1).setHeight(1)).build());
        assertThat(CartOffer.getDescriptor().getFields()).extracting(field->field.getName()).containsExactly(
                "product_id","sku_id","shop_id","shop_name","product_name","sku_label","price_vnd","net_weight_grams","in_stock","thumbnail");
        assertThat(rpc.identityCalls.get()).isZero();
    }
    @Test void emptyProjectionDoesNotCallMerchant() {
        when(store.offers(anyList(),any())).thenReturn(List.of());
        assertThat(find(List.of(UUID.randomUUID().toString())).getOffersCount()).isZero();assertThat(rpc.publicCalls.get()).isZero();
    }
    @Test void boundsMerchantToFourBatchesOfFiveAndFailsWholeResponseOnLaterFailure() {
        var rows=rows(20);assertThat(find(ids(rows)).getOffersCount()).isEqualTo(20);
        assertThat(rpc.publicBatchSizes).containsExactly(5,5,5,5);assertThat(rpc.publicDeadline.get()).isBetween(1L,2000L);
        rpc.publicCalls.set(0);rpc.publicFailAt.set(2);denied(ids(rows),Status.Code.UNAVAILABLE);assertThat(rpc.publicCalls.get()).isEqualTo(2);
    }
    @Test void merchantDeadlineAndMalformedProjectionFailClosedWithoutRetry() {
        var rows=rows(1);rpc.hangMerchant=true;long started=System.nanoTime();
        denied(ids(rows),Status.Code.UNAVAILABLE);assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(5));
        assertThat(rpc.publicCalls.get()).isEqualTo(1);rpc.hangMerchant=false;
        rpc.publicResponse.set(com.agora.contracts.merchant.v1.FindPublicShopsResponse.newBuilder()
                .addShops(com.agora.contracts.merchant.v1.PublicShop.newBuilder().setId(UUID.randomUUID().toString()).setName("Wrong shop")).build());
        denied(ids(rows),Status.Code.UNAVAILABLE);
    }
    @Test void missingAndWrongTokenFailBeforeValidationAndPersistence() {
        for(String token:new String[]{null,"","wrong",RpcFixture.TOKEN+"x"})
            assertThatThrownBy(()->client(token).resolveOffers(ResolveOffersRequest.getDefaultInstance()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
        verifyNoInteractions(store);
    }
    @Test void fourKiBInboundLimitIsEnforced() {
        assertThatThrownBy(()->find(List.of("x".repeat(5000)))).isInstanceOfSatisfying(StatusRuntimeException.class,e->{
            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
            assertThat(e.getStatus().getDescription()).contains("maximum size 4096")
                    .doesNotContain("private","SQL","password",RpcFixture.TOKEN,"x".repeat(32));
        });
        verifyNoInteractions(store);
    }
    @Test void persistenceFailureIsSafeAndDoesNotCallMerchant() {
        when(store.offers(anyList(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL password"));
        denied(List.of(UUID.randomUUID().toString()),Status.Code.UNAVAILABLE);assertThat(rpc.publicCalls.get()).isZero();
    }
    @Test void twentyWorstCaseUnicodeOffersFitBelow64KiB() throws Exception {
        var rows=new ArrayList<StorefrontStore.Offer>();
        for(int i=0;i<20;i++){
            var p=offer();rows.add(new StorefrontStore.Offer(p.productId(),p.skuId(),p.shopId(),"🐟".repeat(160),"🐟".repeat(100),1000000000,50000,true,
                    new StorefrontStore.Image(p.thumbnail().url()+"x".repeat(512-p.thumbnail().url().length()),20000000,1)));
        }
        when(store.offers(anyList(),any())).thenReturn(rows);
        PublicShops shops=ids->{var result=new HashMap<UUID,PublicShops.Shop>();ids.forEach(id->result.put(id,new PublicShops.Shop(id,"🐟".repeat(120))));return result;};
        channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.stop();
        server=new CatalogGrpcServer(new CatalogOffersService(store,shops,clock),new ReservationRpc(mock(ReservationService.class)),new CheckoutRpc(mock(CheckoutService.class)),0,RpcFixture.TOKEN);server.start();
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.port()).usePlaintext().disableRetry().maxInboundMessageSize(65536).build();
        var response=find(ids(rows));
        assertThat(response.getOffersCount()).isEqualTo(20);assertThat(response.getSerializedSize()).isLessThan(65536);
        assertThat(response.getOffersList()).allSatisfy(p->{
            assertThat(p.getShopName()).isEqualTo("🐟".repeat(120));assertThat(p.getProductName()).isEqualTo("🐟".repeat(160));
            assertThat(p.getSkuLabel()).isEqualTo("🐟".repeat(100));assertThat(p.getThumbnail().getUrl()).hasSize(512);
        });
    }
    @Test void invalidListenerConfigurationFailsFastAndStopIsIdempotent() {
        var service=new CatalogOffersService(store,ids->Map.of(),clock);
        for(String token:new String[]{null,"","x".repeat(31),"x".repeat(257),"!".repeat(32)})
            assertThatThrownBy(()->new CatalogGrpcServer(service,new ReservationRpc(mock(ReservationService.class)),new CheckoutRpc(mock(CheckoutService.class)),0,token)).isInstanceOf(IllegalArgumentException.class);
        for(int port:new int[]{-1,65536})assertThatThrownBy(()->new CatalogGrpcServer(service,new ReservationRpc(mock(ReservationService.class)),new CheckoutRpc(mock(CheckoutService.class)),port,RpcFixture.TOKEN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.isRunning()).isTrue();server.start();server.stop();server.stop();assertThat(server.isRunning()).isFalse();
    }
}
