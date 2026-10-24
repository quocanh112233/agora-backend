package com.agora.catalog.configuration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RpcConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel identityChannel() {
        return ManagedChannelBuilder.forAddress("127.0.0.1", 9091).usePlaintext().disableRetry().maxInboundMessageSize(4096).build();
    }
    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel merchantChannel() {
        return ManagedChannelBuilder.forAddress("127.0.0.1", 9092).usePlaintext().disableRetry().maxInboundMessageSize(4096).build();
    }
}
