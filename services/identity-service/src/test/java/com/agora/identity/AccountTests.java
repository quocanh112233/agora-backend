package com.agora.identity;

import com.agora.identity.domain.Account;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTests {
    @Test
    void newAccountHasOnlyUserRoleAndActiveStatus() {
        var account = Account.newUser(UUID.randomUUID(), "  USER@Example.Test ", " Quốc Anh ", Instant.now());
        assertThat(account.email()).isEqualTo("user@example.test");
        assertThat(account.displayName()).isEqualTo("Quốc Anh");
        assertThat(account.roles()).isEqualTo(Set.of("USER"));
        assertThat(account.status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> account.roles().add("ADMIN")).isInstanceOf(UnsupportedOperationException.class);
    }
}
