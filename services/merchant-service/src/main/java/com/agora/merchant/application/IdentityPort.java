package com.agora.merchant.application;

import java.util.Set;
import java.util.UUID;

public interface IdentityPort {
    record Principal(UUID accountId, Set<String> roles) {
        public Principal { roles = Set.copyOf(roles); }
    }
    Principal resolve(String sessionId, String csrfToken, boolean requireCsrf);
}
