package com.agora.catalog.configuration;

import com.agora.catalog.adapter.outbound.CloudinaryImageStorage;
import com.agora.catalog.application.ImageStorage;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ImageStorageConfiguration {
    @Bean(destroyMethod = "close") HttpClient cloudinaryHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Bean ImageStorage imageStorage(HttpClient cloudinaryHttpClient, JsonMapper json,
            @Value("${CLOUDINARY_CLOUD_NAME:}") String cloud,
            @Value("${CLOUDINARY_API_KEY:}") String key,
            @Value("${CLOUDINARY_API_SECRET:}") String secret) {
        return new CloudinaryImageStorage(cloudinaryHttpClient, json, cloud, key, secret);
    }
}
