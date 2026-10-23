package com.agora.merchant.configuration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel identityChannel() {
        return ManagedChannelBuilder.forAddress("127.0.0.1", 9091).usePlaintext().disableRetry().build();
    }
}
