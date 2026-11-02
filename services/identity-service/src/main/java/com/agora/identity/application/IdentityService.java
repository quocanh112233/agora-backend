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

    public Account register(String email, String password, String displayName, com.agora.identity.domain.AccountType accountType) {
        return accounts.create(Account.newUser(UUID.randomUUID(), email, displayName, accountType, Instant.now()),
                passwords.encode(password));
    }

    public AccountRepository.Snapshot login(String email, String password) {
        var stored = accounts.findByEmail(Account.normalizeEmail(email));
        // Always run the KDF, including absent/suspended users; never identify which credential failed.
        boolean matches = passwords.matches(password, stored.map(AccountRepository.Credentials::hash).orElse(dummyHash));
        if (stored.isEmpty() || !matches || !stored.get().account().status().equals("ACTIVE") || stored.get().account().accountType() == null) {
            throw new Unauthenticated();
        }
        var verified = current(stored.get().account().id());
        if (verified.version() != stored.get().version()) throw new Unauthenticated();
        return verified;
    }

    public AccountRepository.Snapshot current(UUID id) {
        return accounts.findById(id).filter(snapshot -> snapshot.account().status().equals("ACTIVE")
                && snapshot.account().accountType() != null).orElseThrow(Unauthenticated::new);
    }

    public Account authenticated(UUID id, Object typeStamp, Object versionStamp) {
        var snapshot = current(id);
        if (!snapshot.account().accountType().name().equals(typeStamp) || !(versionStamp instanceof Long version)
                || version < 0 || version != snapshot.version()) throw new Unauthenticated();
        return snapshot.account();
    }

    public java.util.List<AccountRepository.State> states(java.util.List<UUID> ids) { return accounts.states(ids); }

    public static class Unauthenticated extends RuntimeException { }
}
