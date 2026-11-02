package com.agora.identity.application;

import com.agora.identity.domain.Account;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {
    Account create(Account account, String passwordHash);
    record State(UUID id, com.agora.identity.domain.AccountType accountType, boolean active) {}
    java.util.List<State> states(java.util.List<UUID> ids);
    record Snapshot(Account account, long version) {}
    Optional<Snapshot> findById(UUID id);
    Optional<Credentials> findByEmail(String email);

    // Not a record: never accidentally render credential material in toString/logs.
    final class Credentials {
        private final Account account;
        private final String hash;
        private final long version;
        public Credentials(Account account, String hash, long version) { this.account = account; this.hash = hash; this.version = version; }
        public long version() { return version; }
        public Account account() { return account; }
        public String hash() { return hash; }
    }
}
