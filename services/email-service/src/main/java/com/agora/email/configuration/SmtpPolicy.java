package com.agora.email.configuration;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SmtpPolicy {
    public SmtpPolicy(@Value("${spring.mail.host}") String host,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable}") boolean startTls,
            @Value("${spring.mail.properties.mail.smtp.ssl.enable}") boolean ssl) {
        if (!host.isBlank() && !Set.of("localhost", "127.0.0.1", "::1").contains(host) && !startTls && !ssl) {
            throw new IllegalArgumentException("Remote SMTP requires STARTTLS or SSL.");
        }
    }
}
