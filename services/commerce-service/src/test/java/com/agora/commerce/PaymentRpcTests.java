package com.agora.commerce;

import com.agora.commerce.adapter.inbound.PaymentRpc;
import com.agora.commerce.application.PaymentApplicationService;
import com.agora.commerce.configuration.CommerceGrpcServer;
import com.agora.commerce.domain.*;
import com.agora.commerce.domain.CaptureApplication.*;
import com.agora.contracts.commerce.v1.*;
import com.google.protobuf.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRpcTests {
    PaymentApplicationService service;CommerceGrpcServer server;ManagedChannel channel;
    static final Metadata.Key<String> TOKEN=Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER);
    @BeforeEach void start(){service=mock(PaymentApplicationService.class);server=new CommerceGrpcServer(new PaymentRpc(service),0,CartRpcFixture.TOKEN);server.start();channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.port()).usePlaintext().disableRetry().maxInboundMessageSize(4096).build();}
    @AfterEach void stop()throws Exception{channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);server.stop();}
    CommercePaymentsGrpc.CommercePaymentsBlockingStub stub(String...tokens){var metadata=new Metadata();for(String token:tokens)metadata.put(TOKEN,token);return CommercePaymentsGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));}
    void status(Runnable action,Status.Code expected){assertThatThrownBy(action::run).isInstanceOfSatisfying(StatusRuntimeException.class,e->{assertThat(e.getStatus().getCode()).isEqualTo(expected);assertThat(e.getStatus().getDescription()).isNull();});}
    @Test void tokenBeforeMalformedInputAndServiceForAllMethods(){
        for(var client:List.of(stub(),stub("wrong"),stub(CartRpcFixture.TOKEN,CartRpcFixture.TOKEN))){
            status(()->client.getPayableOrder(PaymentOrderReference.getDefaultInstance()),Status.Code.PERMISSION_DENIED);
            status(()->client.applyCapture(ApplyCaptureRequest.getDefaultInstance()),Status.Code.PERMISSION_DENIED);
            status(()->client.getCaptureApplication(CaptureApplicationReference.getDefaultInstance()),Status.Code.PERMISSION_DENIED);
        }verifyNoInteractions(service);
    }
    @Test void strictUuidProviderAmountAndCurrencyBeforeService(){
        var client=stub(CartRpcFixture.TOKEN);String id="01234567-89ab-cdef-0123-456789abcdef";
        var valid=ApplyCaptureRequest.newBuilder().setCaptureId(id).setPaymentAttemptId(id).setBuyerId(id).setOrderId(id).setProviderTransactionNo("123").setCurrency("VND").setAmountVnd(5000).build();
        for(var bad:List.of(valid.toBuilder().setBuyerId("1-1-1-1-1").build(),valid.toBuilder().setCaptureId(id.toUpperCase()).build(),valid.toBuilder().setProviderTransactionNo("0").build(),valid.toBuilder().setProviderTransactionNo("0000").build(),valid.toBuilder().setProviderTransactionNo("1234567890123456").build(),valid.toBuilder().setCurrency("USD").build(),valid.toBuilder().setAmountVnd(0).build(),valid.toBuilder().setAmountVnd(10000000000L).build()))status(()->client.applyCapture(bad),Status.Code.INVALID_ARGUMENT);
        status(()->client.getPayableOrder(PaymentOrderReference.getDefaultInstance()),Status.Code.INVALID_ARGUMENT);
        status(()->client.getCaptureApplication(CaptureApplicationReference.getDefaultInstance()),Status.Code.INVALID_ARGUMENT);verifyNoInteractions(service);
    }
    @Test void inboundFourKiBLimitAlsoBoundsUnknownFields(){
        var unknown=UnknownFieldSet.newBuilder().addField(100,UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFrom(new byte[4096])).build()).build();
        assertThatThrownBy(()->stub(CartRpcFixture.TOKEN).getPayableOrder(PaymentOrderReference.newBuilder().setUnknownFields(unknown).build())).isInstanceOfSatisfying(StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED));verifyNoInteractions(service);
    }
    @Test void ownerNotFoundAndStorageErrorsHaveSafeStatuses(){
        UUID buyer=UUID.randomUUID(),order=UUID.randomUUID(),capture=UUID.randomUUID();var input=CaptureApplicationReference.newBuilder().setBuyerId(buyer.toString()).setOrderId(order.toString()).setCaptureId(capture.toString()).build();
        when(service.get(buyer,order,capture)).thenThrow(Checkout.missing());status(()->stub(CartRpcFixture.TOKEN).getCaptureApplication(input),Status.Code.NOT_FOUND);
        doThrow(new IllegalStateException("secret SQL or token")).when(service).get(buyer,order,capture);status(()->stub(CartRpcFixture.TOKEN).getCaptureApplication(input),Status.Code.INTERNAL);
    }
    @Test void opaqueCaptureResponseHasOnlyApplicationIdentityAndTypedOutcome(){
        UUID buyer=UUID.randomUUID(),order=UUID.randomUUID(),capture=UUID.randomUUID();
        when(service.get(buyer,order,capture)).thenReturn(new Receipt(capture,buyer,order,"REFUND_REQUIRED","DUPLICATE_CAPTURE",false));
        var result=stub(CartRpcFixture.TOKEN).getCaptureApplication(CaptureApplicationReference.newBuilder().setBuyerId(buyer.toString()).setOrderId(order.toString()).setCaptureId(capture.toString()).build());
        assertThat(result.getState()).isEqualTo(CaptureApplicationState.CAPTURE_REFUND_REQUIRED);assertThat(result.getReason()).isEqualTo(CaptureApplicationReason.DUPLICATE_CAPTURE);
        assertThat(result.getAllFields().keySet()).allSatisfy(field->assertThat(field.getName()).isIn("capture_id","buyer_id","order_id","state","reason","needs_reconciliation"));
    }
    @Test void listenerValidatesConfigurationAndSupportsRestart(){
        for(int port:List.of(-1,65536))assertThatThrownBy(()->new CommerceGrpcServer(new PaymentRpc(service),port,CartRpcFixture.TOKEN)).isInstanceOf(IllegalArgumentException.class);
        for(String token:List.of("","short","x".repeat(257),"has spaces".repeat(5)))assertThatThrownBy(()->new CommerceGrpcServer(new PaymentRpc(service),0,token)).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.isRunning()).isTrue();int port=server.port();server.start();assertThat(server.port()).isEqualTo(port);server.stop();assertThat(server.isRunning()).isFalse();server.start();assertThat(server.isRunning()).isTrue();
    }
}
