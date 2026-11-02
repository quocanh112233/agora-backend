package com.agora.merchant.application;

import java.util.Set;
import java.util.UUID;

public interface IdentityPort {
    record Principal(UUID accountId, Set<String> roles, String accountType, String displayName) {
        public Principal { roles = Set.copyOf(roles); }
    }
    Set<UUID> activeSellers(java.util.List<UUID> ids);
    Principal resolve(String sessionId, String csrfToken, boolean requireCsrf);
}
