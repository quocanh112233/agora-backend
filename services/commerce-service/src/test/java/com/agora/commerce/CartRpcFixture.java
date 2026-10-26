package com.agora.commerce;

import com.agora.contracts.identity.v1.*;
import com.agora.contracts.catalog.v1.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class CartRpcFixture implements AutoCloseable {
    static final String TOKEN="commerce-test-internal-token-at-least-32";
    final Map<String,UUID> sessions=new ConcurrentHashMap<>();
    final Map<UUID,CartOffer> offers=new ConcurrentHashMap<>();
    final AtomicReference<Status> identityFailure=new AtomicReference<>(),catalogFailure=new AtomicReference<>();
    final AtomicReference<ResolveOffersResponse> override=new AtomicReference<>();
    final AtomicInteger catalogCalls=new AtomicInteger();
    volatile Runnable catalogHook=()->{};
    final ManagedChannel identityChannel,catalogChannel;final Server identityServer,catalogServer;
    CartRpcFixture()throws Exception{
        String identity=InProcessServerBuilder.generateName(),catalog=InProcessServerBuilder.generateName();
        identityServer=InProcessServerBuilder.forName(identity).directExecutor().intercept(auth()).addService(new SessionIdentityGrpc.SessionIdentityImplBase(){
            @Override public void resolveSession(ResolveSessionRequest r,StreamObserver<ResolveSessionResponse> out){
                if(identityFailure.get()!=null){out.onError(identityFailure.get().asRuntimeException());return;}
                UUID account=sessions.get(r.getSessionId());if(account==null){out.onError(Status.UNAUTHENTICATED.asRuntimeException());return;}
                if(r.getRequireCsrf()&&!r.getCsrfToken().equals("csrf-fixture")){out.onError(Status.PERMISSION_DENIED.asRuntimeException());return;}
                out.onNext(ResolveSessionResponse.newBuilder().setAccountId(account.toString()).addRoles("USER").build());out.onCompleted();
            }
        }).build().start();
        catalogServer=InProcessServerBuilder.forName(catalog).directExecutor().intercept(auth()).addService(new CatalogOffersGrpc.CatalogOffersImplBase(){
            @Override public void resolveOffers(ResolveOffersRequest r,StreamObserver<ResolveOffersResponse> out){
                catalogCalls.incrementAndGet();if(catalogFailure.get()!=null){out.onError(catalogFailure.get().asRuntimeException());return;}
                catalogHook.run();
                if(override.get()!=null){out.onNext(override.get());out.onCompleted();return;}
                var reply=ResolveOffersResponse.newBuilder();for(String id:r.getSkuIdsList()){var offer=offers.get(UUID.fromString(id));if(offer!=null)reply.addOffers(offer);}
                out.onNext(reply.build());out.onCompleted();
            }
        }).build().start();
        identityChannel=InProcessChannelBuilder.forName(identity).directExecutor().disableRetry().build();
        catalogChannel=InProcessChannelBuilder.forName(catalog).directExecutor().disableRetry().maxInboundMessageSize(65536).build();
    }
    String account(){String session="session-"+UUID.randomUUID();sessions.put(session,UUID.randomUUID());return session;}
    CartOffer offer(){
        UUID product=UUID.randomUUID(),sku=UUID.randomUUID(),image=UUID.randomUUID();
        var offer=CartOffer.newBuilder().setProductId(product.toString()).setSkuId(sku.toString()).setShopId(UUID.randomUUID().toString()).setShopName("Gian hàng test").setProductName("Rau theo mùa").setSkuLabel("Gói 500 g").setPriceVnd(35000).setNetWeightGrams(500).setInStock(true).setThumbnail(OfferImage.newBuilder().setUrl("https://res.cloudinary.com/commerce-fixture/image/upload/v1/agora/products/"+product+"/"+image+".png").setWidth(1).setHeight(1)).build();
        offers.put(sku,offer);return offer;
    }
    ServerInterceptor auth(){return new ServerInterceptor(){@Override public <Q,R> ServerCall.Listener<Q> interceptCall(ServerCall<Q,R> call,Metadata headers,ServerCallHandler<Q,R> next){
        if(!TOKEN.equals(headers.get(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER)))){call.close(Status.PERMISSION_DENIED,new Metadata());return new ServerCall.Listener<>(){};}return next.startCall(call,headers);
    }};}
    @Override public void close()throws Exception{identityChannel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);catalogChannel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);identityServer.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);catalogServer.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
}
