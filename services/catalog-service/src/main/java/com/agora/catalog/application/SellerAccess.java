package com.agora.catalog.application;

import java.util.UUID;

public interface SellerAccess {
    record Seller(UUID accountId, UUID shopId) {}
    Seller resolve(String sessionId, String csrfToken, boolean requireCsrf);
}
