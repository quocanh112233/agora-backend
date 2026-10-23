package com.agora.identity.application;

import com.agora.identity.domain.Account;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {
    Account create(Account account, String passwordHash);
    Optional<Account> findById(UUID id);
    Optional<Credentials> findByEmail(String email);

    // Not a record: never accidentally render credential material in toString/logs.
    final class Credentials {
        private final Account account;
        private final String hash;
        public Credentials(Account account, String hash) { this.account = account; this.hash = hash; }
        public Account account() { return account; }
        public String hash() { return hash; }
    }
}
