package com.agora.commerce;

import com.agora.contracts.identity.v1.AccountType;
import com.agora.commerce.adapter.outbound.GrpcIdentityAccess;
import com.agora.commerce.domain.CartFailure;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AccountTypeAccessTests {
    @Test void onlyExplicitBuyerCanAccessBuyerOperations() throws Exception {
        try (var fixture = new CartRpcFixture()) {
            String session = "typed-session";
            UUID id = UUID.randomUUID();
            fixture.sessions.put(session, id);
            var client = new GrpcIdentityAccess(fixture.identityChannel, CartRpcFixture.TOKEN);
            assertThat(client.resolve(session, null, false)).isEqualTo(id);
            fixture.accountType.set(AccountType.SELLER);
            assertThatThrownBy(() -> client.resolve(session, null, false))
                    .isInstanceOfSatisfying(CartFailure.class, e -> assertThat(e.status()).isEqualTo(403));
            fixture.accountType.set(AccountType.ACCOUNT_TYPE_UNSPECIFIED);
            assertThatThrownBy(() -> client.resolve(session, null, false))
                    .isInstanceOfSatisfying(CartFailure.class, e -> assertThat(e.status()).isEqualTo(503));
        }
    }
}
