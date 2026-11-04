package com.agora.identity;

import com.agora.identity.adapter.inbound.ApiErrors;
import com.agora.identity.adapter.inbound.AuthAttemptFilter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class AuthAttemptFilterTests {
    @Test
    void sellerRegistrationSharesTheAuthenticationAttemptBudget() throws Exception {
        var filter = new AuthAttemptFilter(1, Duration.ofMinutes(15), 100, new ApiErrors(JsonMapper.builder().build()));
        for (String path : java.util.List.of("/api/v1/auth/registrations", "/api/v1/auth/seller-registrations",
                "/api/v1/auth/email-verifications", "/api/v1/auth/email-verifications/confirm",
                "/api/v1/auth/password-resets", "/api/v1/auth/password-resets/confirm",
                "/api/v1/auth/password-changes/confirm", "/api/v1/me/password-changes")) {
            var request = new MockHttpServletRequest("POST", path);
            request.setRemoteAddr("127.0.0.1");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
            assertThat(response.getStatus()).isEqualTo(path.equals("/api/v1/auth/registrations") ? 204 : 429);
        }
    }

    @Test
    void returns429WithRetryAfterAndDoesNotTrustForwardedAddress() throws Exception {
        var filter = new AuthAttemptFilter(2, Duration.ofMinutes(15), 100, new ApiErrors(JsonMapper.builder().build()));
        for (int attempt = 0; attempt < 3; attempt++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/sessions");
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Forwarded-For", "192.0.2." + attempt);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
            assertThat(response.getStatus()).isEqualTo(attempt < 2 ? 204 : 429);
            if (attempt == 2) {
                assertThat(response.getHeader("Retry-After")).isEqualTo("900");
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(response.getContentAsString()).contains("RATE_LIMITED", "traceId");
            }
        }
    }
}
