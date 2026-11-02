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
    final AtomicReference<AccountType> accountType = new AtomicReference<>(AccountType.BUYER);
    static final String TOKEN="commerce-test-internal-token-at-least-32";
    final Map<String,UUID> sessions=new ConcurrentHashMap<>();
    final Map<UUID,CartOffer> offers=new ConcurrentHashMap<>();
    final AtomicReference<Status> identityFailure=new AtomicReference<>(),catalogFailure=new AtomicReference<>();
    final AtomicReference<ResolveOffersResponse> override=new AtomicReference<>();
    final AtomicInteger catalogCalls=new AtomicInteger();
    volatile Runnable catalogHook=()->{},quoteHook=()->{},holdHook=()->{};
    final AtomicInteger quoteCalls=new AtomicInteger(),holdCalls=new AtomicInteger();
    final AtomicReference<Status> quoteFailure=new AtomicReference<>(),holdFailure=new AtomicReference<>(),afterHoldFailure=new AtomicReference<>();
    final AtomicReference<QuoteCheckoutResponse> quoteOverride=new AtomicReference<>();
    final AtomicReference<CheckoutHoldResult> holdOverride=new AtomicReference<>();
    final Map<String,HoldCheckoutRequest> accepted=new ConcurrentHashMap<>();
    final Map<String,CheckoutHoldResult> decisions=new ConcurrentHashMap<>();
    final List<HoldCheckoutRequest> holdRequests=new CopyOnWriteArrayList<>();
    volatile long quoteExpiry;
    final AtomicReference<ReservationResponse> settlementOverride=new AtomicReference<>();
    final AtomicReference<Status> settlementFailure=new AtomicReference<>(),afterConsumeFailure=new AtomicReference<>();
    final List<ConsumeReservationRequest> consumeRequests=new CopyOnWriteArrayList<>();
    volatile Runnable consumeHook=()->{},getHook=()->{};

    static long micros(){var n=java.time.Instant.now();return n.getEpochSecond()*1000000+n.getNano()/1000;}
    void resetCheckout(){quoteCalls.set(0);holdCalls.set(0);quoteFailure.set(null);holdFailure.set(null);afterHoldFailure.set(null);quoteOverride.set(null);holdOverride.set(null);accepted.clear();decisions.clear();holdRequests.clear();quoteHook=()->{};holdHook=()->{};quoteExpiry=0;settlementOverride.set(null);settlementFailure.set(null);afterConsumeFailure.set(null);consumeRequests.clear();consumeHook=()->{};getHook=()->{};}

    final ManagedChannel identityChannel,catalogChannel;final Server identityServer,catalogServer;
    CartRpcFixture()throws Exception{
        String identity=InProcessServerBuilder.generateName(),catalog=InProcessServerBuilder.generateName();
        identityServer=InProcessServerBuilder.forName(identity).directExecutor().intercept(auth()).addService(new SessionIdentityGrpc.SessionIdentityImplBase(){
            @Override public void resolveSession(ResolveSessionRequest r,StreamObserver<ResolveSessionResponse> out){
                if(identityFailure.get()!=null){out.onError(identityFailure.get().asRuntimeException());return;}
                UUID account=sessions.get(r.getSessionId());if(account==null){out.onError(Status.UNAUTHENTICATED.asRuntimeException());return;}
                if(r.getRequireCsrf()&&!r.getCsrfToken().equals("csrf-fixture")){out.onError(Status.PERMISSION_DENIED.asRuntimeException());return;}
                out.onNext(ResolveSessionResponse.newBuilder().setAccountId(account.toString()).addRoles("USER").setAccountType(accountType.get()).build());out.onCompleted();
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
        }).addService(new CatalogCheckoutGrpc.CatalogCheckoutImplBase(){
            @Override public void quoteCheckout(QuoteCheckoutRequest r,StreamObserver<QuoteCheckoutResponse> out){
                quoteCalls.incrementAndGet();quoteHook.run();
                if(quoteFailure.get()!=null){out.onError(quoteFailure.get().asRuntimeException());return;}
                if(quoteOverride.get()!=null){out.onNext(quoteOverride.get());out.onCompleted();return;}
                var reply=QuoteCheckoutResponse.newBuilder().setShopId(r.getShopId()).setExpiresAtEpochMicros(quoteExpiry==0?micros()+300000000:quoteExpiry);
                for(var line:r.getLinesList()){
                    var offer=offers.get(UUID.fromString(line.getSkuId()));
                    if(offer==null||!offer.getInStock()||!offer.getShopId().equals(r.getShopId())||!offer.getProductId().equals(line.getProductId())){out.onError(Status.FAILED_PRECONDITION.asRuntimeException());return;}
                    reply.setShopName(offer.getShopName()).addOffers(CheckoutOffer.newBuilder().setProductId(line.getProductId()).setSkuId(line.getSkuId()).setQuantity(line.getQuantity()).setProductName(offer.getProductName()).setSkuLabel(offer.getSkuLabel()).setNetWeightGrams(offer.getNetWeightGrams()).setUnitPriceVnd(offer.getPriceVnd()).setProductVersion(1));
                }
                out.onNext(reply.build());out.onCompleted();
            }
            @Override public void holdCheckout(HoldCheckoutRequest r,StreamObserver<CheckoutHoldResult> out){
                holdCalls.incrementAndGet();holdRequests.add(r);holdHook.run();
                if(holdFailure.get()!=null){out.onError(holdFailure.get().asRuntimeException());return;}
                if(holdOverride.get()!=null){out.onNext(holdOverride.get());out.onCompleted();return;}
                var previous=accepted.putIfAbsent(r.getCheckoutId(),r);
                if(previous!=null&&!previous.equals(r)){out.onError(Status.INVALID_ARGUMENT.asRuntimeException());return;}
                var result=decisions.computeIfAbsent(r.getCheckoutId(),ignored->{
                    if(micros()>=r.getNotAfterEpochMicros())return CheckoutHoldResult.newBuilder().setClosed(CheckoutClosed.newBuilder().setReason(CheckoutClosureReason.CHECKOUT_EXPIRED)).build();
                    long now=micros();var reservation=ReservationResponse.newBuilder().setBuyerId(r.getBuyerId()).setCheckoutId(r.getCheckoutId()).setShopId(r.getShopId()).setState(ReservationState.HELD).setCreatedAtEpochMicros(now).setExpiresAtEpochMicros(now+900000000);
                    for(var line:r.getLinesList())reservation.addItems(ReservedItem.newBuilder().setProductId(line.getProductId()).setSkuId(line.getSkuId()).setQuantity(line.getQuantity()).addLots(ReservedLot.newBuilder().setLotId(UUID.randomUUID().toString()).setQuantity(line.getQuantity())));
                    return CheckoutHoldResult.newBuilder().setReservation(reservation).build();
                });
                if(afterHoldFailure.get()!=null){out.onError(afterHoldFailure.get().asRuntimeException());return;}
                out.onNext(result);out.onCompleted();
            }
        }).addService(new CatalogReservationsGrpc.CatalogReservationsImplBase(){
            @Override public void get(ReservationReference r,StreamObserver<ReservationResponse> out){
                getHook.run();respond(r.getBuyerId(),r.getCheckoutId(),null,out);
            }
            @Override public void consume(ConsumeReservationRequest r,StreamObserver<ReservationResponse> out){
                consumeRequests.add(r);consumeHook.run();respond(r.getBuyerId(),r.getCheckoutId(),r.getCaptureId(),out);
            }
            private void respond(String buyer,String checkout,String capture,StreamObserver<ReservationResponse> out){
                if(settlementFailure.get()!=null){out.onError(settlementFailure.get().asRuntimeException());return;}
                if(settlementOverride.get()!=null){out.onNext(settlementOverride.get());out.onCompleted();return;}
                var decision=decisions.get(checkout);
                if(decision==null||!decision.getReservation().getBuyerId().equals(buyer)){out.onError(Status.NOT_FOUND.asRuntimeException());return;}
                var r=decision.getReservation();
                if(capture!=null&&r.getState()==ReservationState.HELD){
                    r=r.toBuilder().setState(ReservationState.CONSUMED).setConsumedCaptureId(capture).setConsumedAtEpochMicros(r.getCreatedAtEpochMicros()+1).build();
                    decisions.put(checkout,CheckoutHoldResult.newBuilder().setReservation(r).build());
                }
                if(capture!=null&&afterConsumeFailure.get()!=null){out.onError(afterConsumeFailure.get().asRuntimeException());return;}
                out.onNext(r);out.onCompleted();
            }
        }).build().start();
        identityChannel=InProcessChannelBuilder.forName(identity).directExecutor().disableRetry().build();
        catalogChannel=InProcessChannelBuilder.forName(catalog).directExecutor().disableRetry().maxInboundMessageSize(131072).build();
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
