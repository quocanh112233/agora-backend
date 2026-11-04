package com.agora.identity.configuration;

import com.agora.identity.adapter.inbound.ApiErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
    @Bean HttpSessionSecurityContextRepository securityContexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean HttpSessionCsrfTokenRepository csrfTokens() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setSessionAttributeName("AGORA_CSRF");
        return repository;
    }

    @Bean DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("AGORA_SESSION");
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        cookie.setUseSecureCookie(secure);
        cookie.setUseBase64Encoding(false);
        return cookie;
    }

    @Bean SecurityFilterChain security(HttpSecurity http, ApiErrors errors,
            HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository tokens) throws Exception {
        return http
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(contexts))
                .csrf(csrf -> csrf.csrfTokenRepository(tokens))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) -> errors.send(request, response, 401,
                                "UNAUTHENTICATED", "Thông tin đăng nhập hoặc phiên không hợp lệ."))
                        .accessDeniedHandler((request, response, failure) -> errors.send(request, response, 403,
                                failure instanceof org.springframework.security.web.csrf.CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                                "Yêu cầu không được phép.")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/registrations", "/api/v1/auth/seller-registrations", "/api/v1/auth/sessions", "/actuator/health/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/email-verifications", "/api/v1/auth/email-verifications/confirm",
                                "/api/v1/auth/password-resets", "/api/v1/auth/password-resets/confirm", "/api/v1/auth/password-changes/confirm", "/api/v1/auth/email-changes/confirm").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/me/password-changes", "/api/v1/me/email-changes").authenticated()
                        .requestMatchers("/api/v1/me/profile", "/api/v1/me/avatar").authenticated()
                        .requestMatchers("/api/v1/me", "/api/v1/auth/sessions/current").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/admin/accounts", "/api/v1/admin/accounts/**").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/v1/admin/accounts/*/status").authenticated()
                        .anyRequest().denyAll())
                .build();
    }
}
