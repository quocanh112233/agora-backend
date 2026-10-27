package com.agora.catalog;

import com.agora.catalog.application.*;
import com.agora.catalog.adapter.inbound.ReservationRpc;
import com.agora.catalog.adapter.inbound.CheckoutRpc;
import com.agora.catalog.configuration.CatalogGrpcServer;
import com.agora.catalog.domain.CatalogFailure;
import com.agora.catalog.domain.Reservation;
import com.agora.contracts.catalog.v1.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationGrpcTests {
    final ReservationService service=mock(ReservationService.class);
    final UUID buyer=UUID.randomUUID(),checkout=UUID.randomUUID(),shop=UUID.randomUUID(),product=UUID.randomUUID(),sku=UUID.randomUUID();
    CatalogGrpcServer server;ManagedChannel channel;
    @BeforeEach void start(){
        server=new CatalogGrpcServer(mock(CatalogOffersService.class),new ReservationRpc(service),new CheckoutRpc(mock(CheckoutService.class)),0,RpcFixture.TOKEN);
        server.start();channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.port()).usePlaintext().disableRetry().maxInboundMessageSize(131072).build();
    }
    @AfterEach void stop()throws Exception{if(channel!=null)channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);if(server!=null)server.stop();}
    CatalogReservationsGrpc.CatalogReservationsBlockingStub client(String token){
        var stub=CatalogReservationsGrpc.newBlockingStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS);
        if(token==null)return stub;
        var h=new Metadata();h.put(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER),token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(h));
    }
    CatalogReservationsGrpc.CatalogReservationsBlockingStub client(){return client(RpcFixture.TOKEN);}
    HoldReservationRequest request(){return HoldReservationRequest.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(checkout.toString()).setShopId(shop.toString())
            .addLines(ReservationLine.newBuilder().setProductId(product.toString()).setSkuId(sku.toString()).setQuantity(2)).build();}
    ReservationReference reference(){return ReservationReference.newBuilder().setBuyerId(buyer.toString()).setCheckoutId(checkout.toString()).build();}
    Reservation view(String state,String reason){return new Reservation(checkout,buyer,shop,state,Instant.parse("2026-10-26T12:00:00Z"),Instant.parse("2026-10-26T12:15:00Z"),reason,
            List.of(new Reservation.Item(product,sku,2,List.of(new Reservation.Allocation(UUID.randomUUID(),2)))));}
    void error(Runnable call,Status.Code code){assertThatThrownBy(call::run).isInstanceOfSatisfying(StatusRuntimeException.class,e->{assertThat(e.getStatus().getCode()).isEqualTo(code);assertThat(e.getStatus().getDescription()).isNull();});}
    @Test void holdMapsCurrentStateAndInternalLotReferences(){
        var v=view("HELD",null);when(service.hold(any())).thenReturn(v);var r=client().hold(request());
        assertThat(r.getCheckoutId()).isEqualTo(checkout.toString());assertThat(r.getBuyerId()).isEqualTo(buyer.toString());assertThat(r.getShopId()).isEqualTo(shop.toString());
        assertThat(r.getState()).isEqualTo(ReservationState.HELD);assertThat(r.getReleaseReason()).isEqualTo(ReservationReleaseReason.RESERVATION_RELEASE_REASON_UNSPECIFIED);
        assertThat(r.getExpiresAtEpochMicros()-r.getCreatedAtEpochMicros()).isEqualTo(900000000);
        assertThat(r.getItems(0).getLots(0).getLotId()).isEqualTo(v.items().getFirst().allocations().getFirst().lotId().toString());
        verify(service).hold(new Reservation.Request(buyer,checkout,shop,List.of(new Reservation.Line(product,sku,2))));
    }
    @Test void getAndReleaseMapTerminalStateWithoutInventingHold(){
        when(service.get(buyer,checkout)).thenReturn(view("RELEASED","EXPIRED"));when(service.release(buyer,checkout)).thenReturn(view("RELEASED","CANCELLED"));
        assertThat(client().get(reference()).getReleaseReason()).isEqualTo(ReservationReleaseReason.EXPIRED);
        assertThat(client().release(reference()).getReleaseReason()).isEqualTo(ReservationReleaseReason.CANCELLED);
        verify(service).get(buyer,checkout);verify(service).release(buyer,checkout);verifyNoMoreInteractions(service);
    }
    @Test void checksTokenBeforeServiceOrInputValidation(){
        for(String token:new String[]{null,"","wrong"})error(()->client(token).hold(HoldReservationRequest.getDefaultInstance()),Status.Code.PERMISSION_DENIED);
        verifyNoInteractions(service);
    }
    @Test void rejectsMalformedOwnerCheckoutShopAndLineIdsBeforeService(){
        var b=request().toBuilder();
        for(String bad:List.of("","1-1-1-1-1","invalid",buyer+" ")){
            error(()->client().hold(b.clone().setBuyerId(bad).build()),Status.Code.INVALID_ARGUMENT);
            error(()->client().hold(b.clone().setCheckoutId(bad).build()),Status.Code.INVALID_ARGUMENT);
            error(()->client().hold(b.clone().setShopId(bad).build()),Status.Code.INVALID_ARGUMENT);
            error(()->client().hold(b.clone().setLines(0,b.getLines(0).toBuilder().setProductId(bad)).build()),Status.Code.INVALID_ARGUMENT);
            error(()->client().hold(b.clone().setLines(0,b.getLines(0).toBuilder().setSkuId(bad)).build()),Status.Code.INVALID_ARGUMENT);
            error(()->client().get(reference().toBuilder().setBuyerId(bad).build()),Status.Code.INVALID_ARGUMENT);
            error(()->client().release(reference().toBuilder().setCheckoutId(bad).build()),Status.Code.INVALID_ARGUMENT);
        }
        verifyNoInteractions(service);
    }
    @Test void validatesLineCountDuplicatesAndQuantities(){
        error(()->client().hold(request().toBuilder().clearLines().build()),Status.Code.INVALID_ARGUMENT);
        error(()->client().hold(request().toBuilder().addLines(request().getLines(0)).build()),Status.Code.INVALID_ARGUMENT);
        for(int n:new int[]{0,-1,100,Integer.MAX_VALUE})error(()->client().hold(request().toBuilder().setLines(0,request().getLines(0).toBuilder().setQuantity(n)).build()),Status.Code.INVALID_ARGUMENT);
        var b=request().toBuilder().clearLines();for(int i=0;i<21;i++)b.addLines(ReservationLine.newBuilder().setProductId(UUID.randomUUID().toString()).setSkuId(UUID.randomUUID().toString()).setQuantity(1));
        error(()->client().hold(b.build()),Status.Code.INVALID_ARGUMENT);verifyNoInteractions(service);
    }
    @Test void acceptsUppercaseUuidWithoutChangingIdentity(){
        when(service.hold(any())).thenReturn(view("HELD",null));client().hold(request().toBuilder().setBuyerId(buyer.toString().toUpperCase(Locale.ROOT)).build());
        verify(service).hold(new Reservation.Request(buyer,checkout,shop,List.of(new Reservation.Line(product,sku,2))));
    }
    @Test void mapsBusinessErrorsWithoutLeakingMessages(){
        int[] statuses={400,422,404,409,429,503,500};Status.Code[] codes={Status.Code.INVALID_ARGUMENT,Status.Code.INVALID_ARGUMENT,Status.Code.NOT_FOUND,Status.Code.FAILED_PRECONDITION,Status.Code.RESOURCE_EXHAUSTED,Status.Code.UNAVAILABLE,Status.Code.INTERNAL};
        for(int i=0;i<statuses.length;i++){doThrow(new CatalogFailure(statuses[i],"FAILURE","private SQL secret")).when(service).hold(any());error(()->client().hold(request()),codes[i]);}
        doThrow(new CatalogFailure(422,"IDEMPOTENCY_PAYLOAD_MISMATCH","private")).when(service).hold(any());error(()->client().hold(request()),Status.Code.ALREADY_EXISTS);
    }
    @Test void mapsConnectionAndLockFailuresButKeepsProgrammingErrorsInternal(){
        for(RuntimeException e:List.of(new org.springframework.dao.DataAccessResourceFailureException("private"),new org.springframework.dao.CannotAcquireLockException("private"),new org.springframework.transaction.CannotCreateTransactionException("private"))){doThrow(e).when(service).get(buyer,checkout);error(()->client().get(reference()),Status.Code.UNAVAILABLE);}
        doThrow(new IllegalStateException("private SQL secret")).when(service).get(buyer,checkout);error(()->client().get(reference()),Status.Code.INTERNAL);
    }
    @Test void maxFragmentedResponseFits128KiBAndMaxRequestFits4KiB(){
        var items=new ArrayList<Reservation.Item>();var b=request().toBuilder().clearLines();
        for(int i=0;i<20;i++){
            UUID p=UUID.randomUUID(),s=UUID.randomUUID();var lots=new ArrayList<Reservation.Allocation>();for(int j=0;j<99;j++)lots.add(new Reservation.Allocation(UUID.randomUUID(),1));
            items.add(new Reservation.Item(p,s,99,lots));b.addLines(ReservationLine.newBuilder().setProductId(p.toString()).setSkuId(s.toString()).setQuantity(99));
        }
        when(service.hold(any())).thenReturn(new Reservation(checkout,buyer,shop,"HELD",Instant.now(),Instant.now().plusSeconds(900),null,items));
        var response=client().hold(b.build());assertThat(response.getItemsCount()).isEqualTo(20);assertThat(response.getSerializedSize()).isBetween(65537,131072);assertThat(b.build().getSerializedSize()).isLessThan(4096);
    }
    @Test void invalidInternalStateIsAnInternalErrorRatherThanInvalidClientInput(){
        when(service.hold(any())).thenReturn(view("INVALID_INTERNAL_STATE",null));
        error(()->client().hold(request()),Status.Code.INTERNAL);
    }
    @Test void listenerRejectsOversizedRequests(){
        assertThatThrownBy(()->client().hold(request().toBuilder().setBuyerId("x".repeat(5000)).build())).isInstanceOfSatisfying(StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED));verifyNoInteractions(service);
    }
}
