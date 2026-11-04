package com.agora.identity.configuration;
import com.agora.media.*;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;
@Configuration
public class AvatarConfiguration {
 @Bean(destroyMethod="close") HttpClient avatarHttpClient(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();}
 @Bean ImageStorage avatarStorage(HttpClient avatarHttpClient,JsonMapper json,@Value("${CLOUDINARY_CLOUD_NAME:}")String cloud,@Value("${CLOUDINARY_API_KEY:}")String key,@Value("${CLOUDINARY_API_SECRET:}")String secret){return new CloudinaryImageStorage(avatarHttpClient,json,cloud,key,secret,"avatars",()->new com.agora.identity.domain.AdminAccountFailure(503,"MEDIA_UNAVAILABLE","Dịch vụ ảnh tạm thời không sẵn sàng."));}
 @org.springframework.scheduling.annotation.Scheduled(initialDelay=60000,fixedDelay=60000) public void cleanup(){profiles.cleanup();}
 private final com.agora.identity.application.ProfileService profiles;
 public AvatarConfiguration(@org.springframework.context.annotation.Lazy com.agora.identity.application.ProfileService profiles){this.profiles=profiles;}
}
