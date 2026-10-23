package com.agora.merchant;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class MerchantConfigurationTests {
    @Test void sharedIdentityDatabaseAndPortSettingsCannotOverrideMerchantDefaults() throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("shared-env", Map.of(
                "DB_HOST", "127.0.0.1", "DB_PORT", "5432", "DB_NAME", "agora", "SERVER_PORT", "8081")));
        var yaml = new YamlPropertySourceLoader().load("merchant", new ClassPathResource("application.yaml"));
        yaml.forEach(source -> env.getPropertySources().addLast(source));
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://127.0.0.1:5432/agora_merchant");
        assertThat(env.getProperty("server.port")).isEqualTo("8082");
        env.getPropertySources().addFirst(new MapPropertySource("merchant-env", Map.of(
                "MERCHANT_DB_NAME", "disposable_merchant", "MERCHANT_SERVER_PORT", "18082")));
        assertThat(env.getProperty("spring.datasource.url")).endsWith("/disposable_merchant");
        assertThat(env.getProperty("server.port")).isEqualTo("18082");
    }
}
