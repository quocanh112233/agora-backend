package com.agora.catalog.configuration;

import com.agora.catalog.application.CatalogOffersService;
import com.agora.catalog.adapter.inbound.ReservationRpc;
import com.agora.catalog.adapter.inbound.CheckoutRpc;
import com.agora.contracts.catalog.v1.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="agora.grpc.enabled",havingValue="true")
public class CatalogGrpcServer implements SmartLifecycle {
    private static final Metadata.Key<String> INTERNAL_TOKEN=Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER);
    private final CatalogOffersService offers;
    private final ReservationRpc reservations;
    private final CheckoutRpc checkout;
    private final int configuredPort;
    private final byte[] token;
    private volatile Server server;
    public CatalogGrpcServer(CatalogOffersService offers,ReservationRpc reservations,CheckoutRpc checkout,@Value("${agora.grpc.port}") int port,@Value("${agora.grpc.token}") String token){
        if(port<0||port>65535)throw new IllegalArgumentException("Invalid Catalog gRPC port.");
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid interservice token.");
        this.offers=offers;this.reservations=reservations;this.checkout=checkout;this.configuredPort=port;this.token=token.getBytes(StandardCharsets.UTF_8);
    }
    @Override public synchronized void start(){
        if(isRunning())return;
        try{
            server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",configuredPort))
                    .maxInboundMessageSize(4096).maxConcurrentCallsPerConnection(32)
                    .intercept(new ServerInterceptor(){
                        @Override public <Q,R> ServerCall.Listener<Q> interceptCall(ServerCall<Q,R> call,Metadata headers,ServerCallHandler<Q,R> next){
                            String supplied=headers.get(INTERNAL_TOKEN);
                            if(supplied==null||!MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8))){
                                call.close(Status.PERMISSION_DENIED,new Metadata());return new ServerCall.Listener<>(){};
                            }
                            return next.startCall(call,headers);
                        }
                    }).addService(new Endpoint()).addService(reservations).addService(checkout).build().start();
        }catch(IOException e){throw new IllegalStateException("Cannot start Catalog gRPC listener",e);}
    }
    public int port(){return server.getPort();}
    @Override public boolean isRunning(){return server!=null&&!server.isShutdown();}
    @Override public synchronized void stop(){
        Server running=server;if(running==null)return;
        running.shutdown();
        try{if(!running.awaitTermination(5,TimeUnit.SECONDS))running.shutdownNow();}
        catch(InterruptedException e){running.shutdownNow();Thread.currentThread().interrupt();}
    }
    private final class Endpoint extends CatalogOffersGrpc.CatalogOffersImplBase {
        @Override public void resolveOffers(ResolveOffersRequest request,StreamObserver<ResolveOffersResponse> observer){
            var ids=new ArrayList<UUID>();
            try{
                if(request.getSkuIdsCount()<1||request.getSkuIdsCount()>20)throw new IllegalArgumentException();
                for(String value:request.getSkuIdsList()){
                    UUID id=UUID.fromString(value);
                    if(!id.toString().equalsIgnoreCase(value)||ids.contains(id))throw new IllegalArgumentException();
                    ids.add(id);
                }
            }catch(IllegalArgumentException e){observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());return;}
            try{
                var response=ResolveOffersResponse.newBuilder();
                for(var offer:offers.resolve(ids))response.addOffers(CartOffer.newBuilder()
                        .setProductId(offer.productId().toString()).setSkuId(offer.skuId().toString()).setShopId(offer.shopId().toString())
                        .setShopName(offer.shopName()).setProductName(offer.productName()).setSkuLabel(offer.skuLabel())
                        .setPriceVnd(offer.priceVnd()).setNetWeightGrams(offer.netWeightGrams()).setInStock(offer.inStock())
                        .setThumbnail(OfferImage.newBuilder().setUrl(offer.thumbnail().url()).setWidth(offer.thumbnail().width()).setHeight(offer.thumbnail().height())));
                observer.onNext(response.build());observer.onCompleted();
            }catch(RuntimeException e){
                org.slf4j.LoggerFactory.getLogger(CatalogGrpcServer.class).error("Catalog offer lookup failed type={}",e.getClass().getSimpleName());
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
            }
        }
    }
}
