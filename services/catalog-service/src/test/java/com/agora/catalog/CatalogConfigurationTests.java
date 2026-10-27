package com.agora.catalog;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class CatalogConfigurationTests {
    @Test void catalogDatabaseAndHttpPortCannotUseOtherServicesSharedDefaults() throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("shared", Map.of(
                "DB_HOST", "127.0.0.1", "DB_PORT", "5432", "DB_NAME", "agora", "SERVER_PORT", "8081",
                "MERCHANT_DB_NAME", "agora_merchant", "MERCHANT_SERVER_PORT", "8082")));
        new YamlPropertySourceLoader().load("catalog", new ClassPathResource("application.yaml"))
                .forEach(source -> env.getPropertySources().addLast(source));
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://127.0.0.1:5432/agora_catalog");
        assertThat(env.getProperty("server.port")).isEqualTo("8083");
        assertThat(env.getProperty("agora.grpc.enabled")).isEqualTo("false");
        assertThat(env.getProperty("agora.grpc.port")).isEqualTo("9093");
        env.getPropertySources().addFirst(new MapPropertySource("catalog-overrides", Map.of(
                "CATALOG_DB_NAME", "disposable_catalog", "CATALOG_SERVER_PORT", "18083",
                "CATALOG_GRPC_ENABLED", "true", "CATALOG_GRPC_PORT", "19093", "INTERSERVICE_TOKEN", "test-internal-token-at-least-32-characters")));
        assertThat(env.getProperty("spring.datasource.url")).endsWith("/disposable_catalog");
        assertThat(env.getProperty("server.port")).isEqualTo("18083");
        assertThat(env.getProperty("agora.grpc.enabled")).isEqualTo("true");
        assertThat(env.getProperty("agora.grpc.port")).isEqualTo("19093");
        assertThat(env.getProperty("agora.grpc.token")).isEqualTo("test-internal-token-at-least-32-characters");
    }
    @Test void optionalListenerIsAbsentWhenDisabledAndInvalidEnabledConfigurationFailsStartup() {
        var context=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.agora.catalog.configuration.CatalogGrpcServer.class)
                .withBean(com.agora.catalog.application.CatalogOffersService.class,()->org.mockito.Mockito.mock(com.agora.catalog.application.CatalogOffersService.class))
                .withBean(com.agora.catalog.adapter.inbound.ReservationRpc.class,()->org.mockito.Mockito.mock(com.agora.catalog.adapter.inbound.ReservationRpc.class));
        context.run(c->assertThat(c).doesNotHaveBean(com.agora.catalog.configuration.CatalogGrpcServer.class));
        context.withPropertyValues("agora.grpc.enabled=true","agora.grpc.port=0","agora.grpc.token=bad")
                .run(c->assertThat(c).hasFailed().getFailure().hasRootCauseInstanceOf(IllegalArgumentException.class));
    }
}
