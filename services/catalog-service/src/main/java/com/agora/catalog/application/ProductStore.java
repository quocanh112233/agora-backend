package com.agora.catalog.application;

import com.agora.catalog.domain.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductStore {
    record Key(UUID actor, UUID shopId, String operation, UUID key, String hash) {}
    record Result(int status, Product product) {}
    record Page(List<Product> items, int page, int pageSize, long totalItems) {}
    Optional<Product> owned(UUID shopId, UUID id);
    Optional<Product> lockOwned(UUID shopId, UUID id);
    boolean hasVerifiedImage(UUID shopId, UUID id);
    void changeVisibility(Product product, long expectedVersion);
    Page list(UUID shopId, int page, int pageSize);
    Optional<Result> claim(Key key);
    void insert(Product product);
    void update(Product product, long expectedVersion);
    void audit(Product product, UUID actor, String action, String traceId);
    void complete(Key key, Result result);
}
