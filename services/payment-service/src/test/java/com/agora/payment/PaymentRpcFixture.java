package com.agora.payment;

import com.agora.contracts.identity.v1.*;
import com.agora.contracts.commerce.v1.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class PaymentRpcFixture implements AutoCloseable {
    static final String TOKEN="payment-fixture-internal-token-at-least-32";
    final Map<String,UUID> sessions=new ConcurrentHashMap<>();
    final Map<String,PayableOrderResponse> orders=new ConcurrentHashMap<>();
    final AtomicReference<Status> identityFailure=new AtomicReference<>(),commerceFailure=new AtomicReference<>(),afterCaptureFailure=new AtomicReference<>();
    final Map<String,CaptureApplicationResponse> applied=new ConcurrentHashMap<>();
    final List<ApplyCaptureRequest> captures=new CopyOnWriteArrayList<>();
    final AtomicInteger orderCalls=new AtomicInteger();
    volatile Runnable orderHook=()->{},captureHook=()->{};
    final ManagedChannel identityChannel,commerceChannel;final Server identityServer,commerceServer;
    PaymentRpcFixture()throws Exception {
        String identity=InProcessServerBuilder.generateName(),commerce=InProcessServerBuilder.generateName();
        identityServer=InProcessServerBuilder.forName(identity).directExecutor().intercept(auth()).addService(new SessionIdentityGrpc.SessionIdentityImplBase(){
            @Override public void resolveSession(ResolveSessionRequest r,StreamObserver<ResolveSessionResponse> out){
                if(identityFailure.get()!=null){out.onError(identityFailure.get().asRuntimeException());return;}
                UUID actor=sessions.get(r.getSessionId());if(actor==null){out.onError(Status.UNAUTHENTICATED.asRuntimeException());return;}
                if(r.getRequireCsrf()&&!r.getCsrfToken().equals("csrf-fixture")){out.onError(Status.PERMISSION_DENIED.asRuntimeException());return;}
                out.onNext(ResolveSessionResponse.newBuilder().setAccountId(actor.toString()).addRoles("USER").build());out.onCompleted();
            }
        }).build().start();
        commerceServer=InProcessServerBuilder.forName(commerce).directExecutor().intercept(auth()).addService(new CommercePaymentsGrpc.CommercePaymentsImplBase(){
            @Override public void getPayableOrder(PaymentOrderReference r,StreamObserver<PayableOrderResponse> out){
                orderCalls.incrementAndGet();orderHook.run();if(commerceFailure.get()!=null){out.onError(commerceFailure.get().asRuntimeException());return;}
                var order=orders.get(r.getOrderId());if(order==null||!order.getBuyerId().equals(r.getBuyerId())){out.onError(Status.NOT_FOUND.asRuntimeException());return;}
                out.onNext(order);out.onCompleted();
            }
            @Override public void applyCapture(ApplyCaptureRequest r,StreamObserver<CaptureApplicationResponse> out){
                captures.add(r);captureHook.run();if(commerceFailure.get()!=null){out.onError(commerceFailure.get().asRuntimeException());return;}
                var result=applied.computeIfAbsent(r.getCaptureId(),id->CaptureApplicationResponse.newBuilder().setCaptureId(id).setBuyerId(r.getBuyerId()).setOrderId(r.getOrderId()).setState(CaptureApplicationState.CAPTURE_APPLIED).build());
                if(afterCaptureFailure.get()!=null){out.onError(afterCaptureFailure.get().asRuntimeException());return;}out.onNext(result);out.onCompleted();
            }
            @Override public void getCaptureApplication(CaptureApplicationReference r,StreamObserver<CaptureApplicationResponse> out){
                var result=applied.get(r.getCaptureId());if(result==null||!result.getBuyerId().equals(r.getBuyerId())||!result.getOrderId().equals(r.getOrderId())){out.onError(Status.NOT_FOUND.asRuntimeException());return;}out.onNext(result);out.onCompleted();
            }
        }).build().start();
        identityChannel=InProcessChannelBuilder.forName(identity).directExecutor().disableRetry().build();commerceChannel=InProcessChannelBuilder.forName(commerce).directExecutor().disableRetry().maxInboundMessageSize(4096).build();
    }
    String account(){String session="session-"+UUID.randomUUID();sessions.put(session,UUID.randomUUID());return session;}
    String order(String session){UUID id=UUID.randomUUID();var n=java.time.Instant.now().plusSeconds(900);orders.put(id.toString(),PayableOrderResponse.newBuilder().setBuyerId(sessions.get(session).toString()).setOrderId(id.toString()).setCheckoutId(UUID.randomUUID().toString()).setShopId(UUID.randomUUID().toString()).setCurrency("VND").setAmountVnd(70000).setExpiresAtEpochMicros(n.getEpochSecond()*1000000+n.getNano()/1000).build());return id.toString();}
    ServerInterceptor auth(){return new ServerInterceptor(){@Override public <Q,R> ServerCall.Listener<Q> interceptCall(ServerCall<Q,R> call,Metadata headers,ServerCallHandler<Q,R> next){
        if(!TOKEN.equals(headers.get(Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER)))){call.close(Status.PERMISSION_DENIED,new Metadata());return new ServerCall.Listener<>(){};}return next.startCall(call,headers);
    }};}
    @Override public void close()throws Exception {identityChannel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);commerceChannel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);identityServer.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);commerceServer.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);}
}
