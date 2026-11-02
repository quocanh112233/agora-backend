package com.agora.identity.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public record Account(UUID id, String email, String displayName, AccountType accountType, Set<String> roles,
                      String status, Instant createdAt) {
    public Account {
        roles = Set.copyOf(roles);
    }

    public static Account newUser(UUID id, String email, String displayName, AccountType accountType, Instant createdAt) {
        return new Account(id, normalizeEmail(email), displayName.strip(), java.util.Objects.requireNonNull(accountType), Set.of("USER"), "ACTIVE", createdAt);
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
