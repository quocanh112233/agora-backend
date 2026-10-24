package com.agora.catalog.application;

import com.agora.catalog.application.ProductStore.*;
import com.agora.catalog.domain.CatalogFailure;
import com.agora.catalog.domain.Product;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ProductService {
    private final ProductStore store;
    public ProductService(ProductStore store) { this.store = store; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Product get(UUID shopId, UUID id) { return owned(shopId, id); }
    private Product owned(UUID shopId, UUID id) {
        return store.owned(shopId, id).orElseThrow(() -> new CatalogFailure(404, "PRODUCT_NOT_FOUND", "Product was not found."));
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(UUID shopId, int page, int pageSize) { return store.list(shopId, page, pageSize); }
    @Transactional
    public Result create(Key key, Product.Input input, String traceId) {
        var replay = store.claim(key);
        if (replay.isPresent()) return replay.get();
        Product product = Product.draft(key.shopId(), input);
        store.insert(product);
        store.audit(product, key.actor(), "CREATE_PRODUCT", traceId);
        Result result = new Result(201, product);
        store.complete(key, result);
        return result;
    }
    @Transactional
    public Result replace(Key key, UUID id, long expectedVersion, Product.Input input, String traceId) {
        var replay = store.claim(key);
        if (replay.isPresent()) return replay.get();
        Product changed = owned(key.shopId(), id).replace(expectedVersion, input);
        store.update(changed, expectedVersion);
        store.audit(changed, key.actor(), "UPDATE_PRODUCT", traceId);
        Result result = new Result(200, changed);
        store.complete(key, result);
        return result;
    }
    @Transactional
    public Result changeVisibility(Key key, UUID id, long expectedVersion, String target, String traceId) {
        var replay = store.claim(key);
        if (replay.isPresent()) return replay.get();
        Product current = store.lockOwned(key.shopId(), id).orElseThrow(() ->
                new CatalogFailure(404, "PRODUCT_NOT_FOUND", "Product was not found."));
        Product changed = current.changeVisibility(expectedVersion, target);
        // The product lock is shared with image mutations, including detaching its last image.
        if ("PUBLISHED".equals(target) && !store.hasVerifiedImage(key.shopId(), id))
            throw new CatalogFailure(422, "PUBLICATION_NOT_READY", "At least one attached verified image is required.");
        store.changeVisibility(changed, expectedVersion);
        store.audit(changed, key.actor(), "PUBLISHED".equals(target) ? "PUBLISH_PRODUCT" : "HIDE_PRODUCT", traceId);
        Result result = new Result(200, changed);
        store.complete(key, result);
        return result;
    }
}
