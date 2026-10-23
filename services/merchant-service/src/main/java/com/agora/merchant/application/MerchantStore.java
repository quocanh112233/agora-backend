package com.agora.merchant.application;

import com.agora.merchant.domain.Shop;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantStore {
    record Key(UUID actor, String operation, UUID key, String hash) {}
    record Result(int status, Shop shop) {}
    record Page(List<Shop> items, int page, int pageSize, long totalItems) {}
    Optional<Shop> find(UUID shopId);
    Page list(String status, int page, int pageSize);
    Optional<Shop> owned(UUID owner, UUID shopId);
    Optional<Result> claim(Key key);
    void insert(Shop shop);
    void update(Shop shop, long expectedVersion, String previousStatus);
    void review(Shop shop, UUID actor, Shop.Review review);
    void audit(Shop shop, UUID actor, String action, String previousStatus, String reason, String traceId);
    void complete(Key key, Result result);
}
