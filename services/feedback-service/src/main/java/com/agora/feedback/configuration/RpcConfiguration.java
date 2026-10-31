package com.agora.feedback.configuration;
import io.grpc.*;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class RpcConfiguration {
    private ManagedChannel channel(int port){return ManagedChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().disableRetry().maxInboundMessageSize(4096).build();}
    @Bean(destroyMethod="shutdownNow") ManagedChannel identityChannel(){return channel(9091);}
    @Bean(destroyMethod="shutdownNow") ManagedChannel commerceChannel(){return channel(9094);}
    @Bean(destroyMethod="shutdownNow") ManagedChannel merchantChannel(){return channel(9092);}
}
