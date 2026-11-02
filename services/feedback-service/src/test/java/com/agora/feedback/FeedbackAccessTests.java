package com.agora.feedback;

import com.agora.feedback.adapter.outbound.GrpcFeedbackAccess;
import com.agora.feedback.domain.FeedbackFailure;
import com.agora.contracts.identity.v1.*;
import com.agora.contracts.commerce.v1.*;
import com.agora.contracts.merchant.v1.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
class FeedbackAccessTests {
    static final String TOKEN="feedback-synthetic-test-token-000000001";
    final UUID buyer=UUID.randomUUID(),order=UUID.randomUUID(),sku=UUID.randomUUID(),product=UUID.randomUUID(),shop=UUID.randomUUID();
    Server server;ManagedChannel channel;GrpcFeedbackAccess access;
    AtomicReference<Status> identityFailure=new AtomicReference<>(),commerceFailure=new AtomicReference<>(),merchantFailure=new AtomicReference<>();
    ResolveSessionResponse identity;ReviewEligibility eligible;ResolveActiveShopResponse merchant;ResolveSessionRequest lastIdentity;ReviewLineReference lastLine;
    @BeforeEach void start()throws Exception{
        identity=ResolveSessionResponse.newBuilder().setAccountId(buyer.toString()).addRoles("USER").setAccountType(AccountType.BUYER).build();eligible=ReviewEligibility.newBuilder().setBuyerId(buyer.toString()).setOrderId(order.toString()).setSkuId(sku.toString()).setProductId(product.toString()).setShopId(shop.toString()).build();merchant=ResolveActiveShopResponse.newBuilder().setShopId(shop.toString()).build();
        String name=InProcessServerBuilder.generateName();server=InProcessServerBuilder.forName(name).directExecutor().intercept(new ServerInterceptor(){public <Q,R>ServerCall.Listener<Q> interceptCall(ServerCall<Q,R> c,Metadata h,ServerCallHandler<Q,R> next){assertThat(h.get(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER))).isEqualTo(TOKEN);return next.startCall(c,h);}})
            .addService(new SessionIdentityGrpc.SessionIdentityImplBase(){@Override public void resolveSession(ResolveSessionRequest r,StreamObserver<ResolveSessionResponse> out){lastIdentity=r;reply(out,identity,identityFailure.get());}})
            .addService(new CommercePaymentsGrpc.CommercePaymentsImplBase(){@Override public void getReviewEligibility(ReviewLineReference r,StreamObserver<ReviewEligibility> out){lastLine=r;reply(out,eligible,commerceFailure.get());}})
            .addService(new MerchantAccessGrpc.MerchantAccessImplBase(){@Override public void resolveActiveShop(ResolveActiveShopRequest r,StreamObserver<ResolveActiveShopResponse> out){assertThat(r.getAccountId()).isEqualTo(buyer.toString());reply(out,merchant,merchantFailure.get());}}).build().start();
        channel=InProcessChannelBuilder.forName(name).directExecutor().maxInboundMessageSize(4096).build();access=new GrpcFeedbackAccess(channel,channel,channel,TOKEN);
    }
    <T>void reply(StreamObserver<T> out,T value,Status failure){if(failure!=null){out.onError(failure.asRuntimeException());return;}out.onNext(value);out.onCompleted();}
    @AfterEach void stop(){channel.shutdownNow();server.shutdownNow();}
    void fails(Runnable call,int status){assertThatThrownBy(call::run).isInstanceOfSatisfying(FeedbackFailure.class,e->assertThat(e.status()).isEqualTo(status));}
    @Test void forwardsSessionCsrfAndChecksCurrentAdminRole(){assertThat(access.resolve("session","csrf",true).admin()).isFalse();assertThat(lastIdentity.getRequireCsrf()).isTrue();assertThat(lastIdentity.getCsrfToken()).isEqualTo("csrf");identity=identity.toBuilder().addRoles("ADMIN").build();assertThat(access.resolve("session",null,false).admin()).isTrue();assertThat(lastIdentity.getRequireCsrf()).isFalse();}
    @Test void missingSessionAndIdentityFailuresFailClosed(){fails(()->access.resolve(null,null,false),401);for(var entry:Map.of(Status.UNAUTHENTICATED,401,Status.PERMISSION_DENIED,403,Status.UNAVAILABLE,503,Status.DEADLINE_EXCEEDED,503).entrySet()){identityFailure.set(entry.getKey());fails(()->access.resolve("session","csrf",true),entry.getValue());}}
    @Test void unspecifiedOrUnknownTypeNeverGrantsScopeEvenWithAdminRole(){
        for(int type:java.util.List.of(0,999)){
            identity=identity.toBuilder().setAccountTypeValue(type).addRoles("ADMIN").build();
            fails(()->access.resolve("session",null,false),503);
        }
    }
    @Test void malformedIdentityNeverGrantsAdmin(){identity=identity.toBuilder().setAccountId("bad").addRoles("ADMIN").build();fails(()->access.resolve("session","csrf",true),503);}
    @Test void resolvesLineWithExactBuyerOrderAndSku(){var line=access.eligible(buyer,order,sku);assertThat(line.productId()).isEqualTo(product);assertThat(line.shopId()).isEqualTo(shop);assertThat(lastLine.getBuyerId()).isEqualTo(buyer.toString());assertThat(lastLine.getOrderId()).isEqualTo(order.toString());assertThat(lastLine.getSkuId()).isEqualTo(sku.toString());}
    @Test void commerceMismatchAndMalformedSnapshotFailClosed(){var valid=eligible;for(var bad:List.of(valid.toBuilder().setBuyerId(UUID.randomUUID().toString()).build(),valid.toBuilder().setOrderId(UUID.randomUUID().toString()).build(),valid.toBuilder().setSkuId(UUID.randomUUID().toString()).build(),valid.toBuilder().setProductId("bad").build(),valid.toBuilder().setShopId("bad").build())){eligible=bad;fails(()->access.eligible(buyer,order,sku),503);}}
    @Test void commerceNotFoundNotPaidAndTransportRemainDistinct(){for(var entry:Map.of(Status.NOT_FOUND,404,Status.FAILED_PRECONDITION,409,Status.UNAVAILABLE,503,Status.PERMISSION_DENIED,503).entrySet()){commerceFailure.set(entry.getKey());fails(()->access.eligible(buyer,order,sku),entry.getValue());}}
    @Test void merchantRequiresActiveCanonicalShop(){assertThat(access.shop(buyer)).isEqualTo(shop);merchant=merchant.toBuilder().setShopId("bad").build();fails(()->access.shop(buyer),503);for(var entry:Map.of(Status.NOT_FOUND,404,Status.FAILED_PRECONDITION,404,Status.UNAVAILABLE,503).entrySet()){merchantFailure.set(entry.getKey());fails(()->access.shop(buyer),entry.getValue());}}
    @Test void actualMerchantPermissionDenialIsForbidden(){merchantFailure.set(Status.PERMISSION_DENIED);fails(()->access.shop(buyer),403);}
    @Test void validatesInternalToken(){for(String invalid:List.of("","short","has spaces".repeat(9),"a".repeat(257)))assertThatThrownBy(()->new GrpcFeedbackAccess(channel,channel,channel,invalid)).isInstanceOf(IllegalArgumentException.class);}
}
