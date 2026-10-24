package com.agora.catalog.configuration;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class InventoryConfiguration {
    @Bean Clock inventoryClock(){return Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));}
}
