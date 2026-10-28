package com.agora.payment.configuration;
import io.grpc.*;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class RpcConfiguration {
    @Bean com.agora.payment.adapter.outbound.VnpayApiClient vnpayApiClient(tools.jackson.databind.json.JsonMapper json){return new com.agora.payment.adapter.outbound.VnpayApiClient(json);}
    @Bean(destroyMethod="shutdownNow") ManagedChannel identityChannel(){return ManagedChannelBuilder.forAddress("127.0.0.1",9091).usePlaintext().disableRetry().maxInboundMessageSize(4096).build();}
    @Bean(destroyMethod="shutdownNow") ManagedChannel commerceChannel(){return ManagedChannelBuilder.forAddress("127.0.0.1",9094).usePlaintext().disableRetry().maxInboundMessageSize(4096).build();}
}
