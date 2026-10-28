package com.agora.commerce.configuration;

import com.agora.commerce.adapter.inbound.PaymentRpc;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="agora.grpc.enabled",havingValue="true")
public class CommerceGrpcServer implements SmartLifecycle {
    private static final Metadata.Key<String> INTERNAL_TOKEN=Metadata.Key.of("x-agora-internal-token",Metadata.ASCII_STRING_MARSHALLER);
    private final PaymentRpc payments;private final int configuredPort;private final byte[] token;private volatile Server server;
    public CommerceGrpcServer(PaymentRpc payments,@Value("${agora.grpc.port}") int port,@Value("${agora.internal-token}") String token){
        if(port<0||port>65535)throw new IllegalArgumentException("Invalid Commerce gRPC port.");
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid interservice token.");
        this.payments=payments;this.configuredPort=port;this.token=token.getBytes(StandardCharsets.UTF_8);
    }
    @Override public synchronized void start(){
        if(isRunning())return;
        try{server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",configuredPort)).maxInboundMessageSize(4096).maxConcurrentCallsPerConnection(32).intercept(new ServerInterceptor(){
            @Override public <Q,R> ServerCall.Listener<Q> interceptCall(ServerCall<Q,R> call,Metadata headers,ServerCallHandler<Q,R> next){
                var supplied=headers.getAll(INTERNAL_TOKEN);
                String value=null;if(supplied!=null){var iterator=supplied.iterator();if(iterator.hasNext())value=iterator.next();if(iterator.hasNext())value=null;}
                if(value==null||!MessageDigest.isEqual(token,value.getBytes(StandardCharsets.UTF_8))){call.close(Status.PERMISSION_DENIED,new Metadata());return new ServerCall.Listener<>(){};}
                return next.startCall(call,headers);
            }
        }).addService(payments).build().start();}catch(IOException e){throw new IllegalStateException("Cannot start Commerce gRPC listener",e);}
    }
    public int port(){return server.getPort();}
    @Override public boolean isRunning(){return server!=null&&!server.isShutdown();}
    @Override public synchronized void stop(){
        Server running=server;if(running==null)return;running.shutdown();
        try{if(!running.awaitTermination(5,TimeUnit.SECONDS))running.shutdownNow();}catch(InterruptedException e){running.shutdownNow();Thread.currentThread().interrupt();}
    }
}
