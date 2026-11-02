package com.agora.merchant.application;

import com.agora.merchant.domain.Shop;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantStore {
    record Key(UUID actor, String operation, UUID key, String hash) {}
    record Result(int status, Shop shop) {}
    record Page(List<Shop> items, int page, int pageSize, long totalItems) {}
    record History(UUID id, UUID targetId, UUID actorId, String actorDisplayName, String previousStatus,
                   String status, String reason, long version, java.time.Instant createdAt) {}
    record HistoryPage(List<History> items, String nextCursor) { public HistoryPage { items = List.copyOf(items); } }
    void statusAudit(Shop shop, UUID actor, String actorName, String previousStatus, String reason, String traceId);
    List<History> history(UUID target, java.time.Instant time, UUID id, int limit);
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
