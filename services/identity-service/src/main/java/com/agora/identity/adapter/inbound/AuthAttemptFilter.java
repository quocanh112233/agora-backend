package com.agora.identity.adapter.inbound;

import com.agora.identity.application.AttemptLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthAttemptFilter extends OncePerRequestFilter {
    private final AttemptLimiter limiter;
    private final ApiErrors errors;
    private final long retryAfter;

    public AuthAttemptFilter(@Value("${agora.auth.rate-limit.attempts}") int attempts,
            @Value("${agora.auth.rate-limit.window}") Duration window,
            @Value("${agora.auth.rate-limit.max-keys}") int maxKeys, ApiErrors errors) {
        this.limiter = new AttemptLimiter(attempts, window, maxKeys);
        this.retryAfter = window.toSeconds(); this.errors = errors;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || !(request.getRequestURI().equals("/api/v1/auth/sessions")
                || request.getRequestURI().equals("/api/v1/auth/registrations"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // No X-Forwarded-For: only the transport peer is trusted until a real ingress is configured.
        if (!limiter.allow(request.getRemoteAddr(), Instant.now())) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            errors.send(request, response, 429, "RATE_LIMITED", "Thử quá nhiều lần. Vui lòng thử lại sau.");
            return;
        }
        chain.doFilter(request, response);
    }
}
