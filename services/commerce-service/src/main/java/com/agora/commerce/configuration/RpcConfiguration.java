package com.agora.commerce.configuration;

import io.grpc.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class RpcConfiguration {
    @Bean(destroyMethod="shutdownNow") ManagedChannel identityChannel(){return ManagedChannelBuilder.forAddress("127.0.0.1",9091).usePlaintext().disableRetry().maxInboundMessageSize(4096).build();}
    @Bean(destroyMethod="shutdownNow") ManagedChannel catalogChannel(){return ManagedChannelBuilder.forAddress("127.0.0.1",9093).usePlaintext().disableRetry().maxInboundMessageSize(65536).build();}
}
