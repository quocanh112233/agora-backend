package com.agora.identity.adapter.inbound;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.security.web.csrf.CsrfToken;

/** Cross-transport verification of Spring Security 7's masked (BREACH-protected) CSRF token. */
public final class SessionCsrfVerifier {
    private SessionCsrfVerifier() { }

    public static boolean matches(CsrfToken expected, String masked) {
        if (expected == null || masked == null || masked.length() > 512) return false;
        byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(masked); }
        catch (IllegalArgumentException exception) { return false; }
        byte[] token = expected.getToken().getBytes(StandardCharsets.UTF_8);
        if (bytes.length != token.length * 2) return false;
        byte[] decoded = new byte[token.length];
        for (int index = 0; index < token.length; index++) decoded[index] = (byte) (bytes[index] ^ bytes[index + token.length]);
        return MessageDigest.isEqual(token, decoded);
    }
}
