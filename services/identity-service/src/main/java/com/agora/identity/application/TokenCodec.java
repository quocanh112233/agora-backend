package com.agora.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenCodec {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public TokenCodec(@Value("${agora.email.token-key}") String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != 32)
                throw new IllegalArgumentException();
            key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("EMAIL_TOKEN_ENCRYPTION_KEY must encode32bytes as URL-safe Base64.");
        }
    }

    public String create() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public byte[] digest(String token) {
        if (token == null
                || !token.matches("[A-Za-z0-9_-]{43}")
                || !Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(Base64.getUrlDecoder().decode(token))
                        .equals(token))
            throw new IllegalArgumentException("Invalid link token.");
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] seal(UUID id, String purpose, String token) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD((id + ":" + purpose).getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.US_ASCII));
            return java.nio.ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce)
                    .put(encrypted)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot protect email action.");
        }
    }

    public String open(UUID id, String purpose, byte[] sealed) {
        try {
            if (sealed == null || sealed.length < 28)
                throw new GeneralSecurityException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 0, 12)));
            cipher.updateAAD((id + ":" + purpose).getBytes(StandardCharsets.US_ASCII));
            String token = new String(
                    cipher.doFinal(Arrays.copyOfRange(sealed, 12, sealed.length)),
                    StandardCharsets.US_ASCII);
            digest(token);
            return token;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot open email action.");
        }
    }
}
