package com.agora.identity;

import com.agora.identity.adapter.inbound.SessionCsrfVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import static org.assertj.core.api.Assertions.assertThat;

class SessionCsrfVerifierTests {
    @Test
    void acceptsActualSpringMaskedTokenButRejectsRawStaleMissingAndMalformedValues() {
        var raw = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "7b49cb26-ad9c-427e-ae62-fc17142b6c00");
        var request = new MockHttpServletRequest();
        new XorCsrfTokenRequestAttributeHandler().handle(request, new MockHttpServletResponse(), () -> raw);
        String masked = ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
        assertThat(SessionCsrfVerifier.matches(raw, masked)).isTrue();
        assertThat(SessionCsrfVerifier.matches(raw, raw.getToken())).isFalse();
        assertThat(SessionCsrfVerifier.matches(new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "different-token"), masked)).isFalse();
        assertThat(SessionCsrfVerifier.matches(raw, "!invalid")).isFalse();
        assertThat(SessionCsrfVerifier.matches(raw, "")).isFalse();
        assertThat(SessionCsrfVerifier.matches(null, masked)).isFalse();
    }
}
