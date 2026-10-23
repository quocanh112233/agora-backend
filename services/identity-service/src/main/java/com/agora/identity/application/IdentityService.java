package com.agora.identity.application;

import com.agora.identity.domain.Account;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public IdentityService(AccountRepository accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public Account register(String email, String password, String displayName) {
        return accounts.create(Account.newUser(UUID.randomUUID(), email, displayName, Instant.now()),
                passwords.encode(password));
    }

    public Account login(String email, String password) {
        var stored = accounts.findByEmail(Account.normalizeEmail(email));
        // Always run the KDF, including absent/suspended users; never identify which credential failed.
        boolean matches = passwords.matches(password, stored.map(AccountRepository.Credentials::hash).orElse(dummyHash));
        if (stored.isEmpty() || !matches || !stored.get().account().status().equals("ACTIVE")) {
            throw new Unauthenticated();
        }
        return stored.get().account();
    }

    public Account current(UUID id) {
        return accounts.findById(id).filter(account -> account.status().equals("ACTIVE"))
                .orElseThrow(Unauthenticated::new);
    }

    public static class Unauthenticated extends RuntimeException { }
}
