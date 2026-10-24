package com.agora.catalog.configuration;

import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

@Configuration(proxyBeanMethods = false)
public class MultipartConfiguration {
    @Bean MultipartConfigElement multipartConfigElement() throws IOException {
        Path cwd = Path.of("").toRealPath();
        Path target = directory(cwd.resolve("target"));
        Path uploads = directory(target.resolve("upload-tmp"));
        return new MultipartConfigElement(uploads.toString(), 5L * 1024 * 1024, 6L * 1024 * 1024, 0);
    }
    private Path directory(Path path) throws IOException {
        try { Files.createDirectory(path); }
        catch (FileAlreadyExistsException e) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Upload temporary directory must be a real directory.");
        }
        return path;
    }
    @Bean StandardServletMultipartResolver multipartResolver() {
        var resolver = new StandardServletMultipartResolver();
        resolver.setResolveLazily(true);
        resolver.setStrictServletCompliance(true);
        return resolver;
    }
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> mediaRejectedBodyDrain() {
        // Drain a just-over-limit request so the client receives the structured 413 response.
        return factory -> factory.addConnectorCustomizers(connector -> connector.setProperty("maxSwallowSize", "7340032"));
    }
}
