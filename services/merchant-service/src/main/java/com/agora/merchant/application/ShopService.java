package com.agora.merchant.application;

import com.agora.merchant.application.MerchantStore.Key;
import com.agora.merchant.application.MerchantStore.Result;
import com.agora.merchant.application.MerchantStore.Page;
import com.agora.merchant.domain.MerchantFailure;
import com.agora.merchant.domain.Shop;
import com.agora.merchant.domain.Shop.Address;
import com.agora.merchant.domain.Shop.Patch;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShopService {
    public record Create(String name, String description, String contactPhone, Address pickupAddress) {}
    public record PublicShop(UUID id, String name, UUID ownerAccountId) {}
    private final MerchantStore store;
    public ShopService(MerchantStore store) { this.store = store; }

    public Shop mine(UUID owner) { return owned(owner, null); }
    public List<PublicShop> findPublicShops(List<UUID> ids) {
        return ids.stream().flatMap(id -> store.find(id).stream())
                .filter(shop -> "ACTIVE".equals(shop.status()))
                .map(shop -> {
                    String name = shop.name();
                    if (name == null || name.codePointCount(0, name.length()) > 120)
                        throw new IllegalStateException("Invalid public shop name.");
                    return new PublicShop(shop.id(), name, shop.ownerAccountId());
                }).filter(shop -> !shop.name().isBlank()).toList();
    }
    private Shop owned(UUID owner, UUID shopId) {
        return store.owned(owner, shopId).orElseThrow(() ->
                new MerchantFailure(404, "SHOP_NOT_FOUND", "Shop was not found."));
    }
    @Transactional
    public Result create(Key key, Create input, String traceId) {
        var replay = store.claim(key);
        if (replay.isPresent()) return replay.get();
        Shop shop = Shop.draft(key.actor(), input.name(), input.description(), input.contactPhone(), input.pickupAddress());
        store.insert(shop);
        store.audit(shop, key.actor(), "CREATE_SHOP", null, null, traceId);
        Result result = new Result(201, shop);
        store.complete(key, result);
        return result;
    }
    @Transactional
    public Result patch(Key key, UUID shopId, Patch input, String traceId) {
        var replay = store.claim(key);
        if (replay.isPresent()) return replay.get();
        Shop current = owned(key.actor(), shopId);
        Shop changed = current.patch(input);
        store.update(changed, input.expectedVersion(), current.status());
        store.audit(changed, key.actor(), "UPDATE_SHOP", current.status(), null, traceId);
        Result result = new Result(200, changed);
        store.complete(key, result);
        return result;
    }
    public Page list(String status, int page, int pageSize) { return store.list(status, page, pageSize); }

    @Transactional
    public Result submit(Key key, UUID shopId, long expectedVersion, String traceId) {
        var replay = store.claim(key);
        if (replay.isPresent()) return replay.get();
        Shop current = owned(key.actor(), shopId);
        Shop changed = current.submit(expectedVersion);
        store.update(changed, expectedVersion, current.status());
        store.audit(changed, key.actor(), "SUBMIT_SHOP", current.status(), null, traceId);
        Result result = new Result(200, changed);
        store.complete(key, result);
        return result;
    }
    @Transactional
    public Result review(Key key, UUID shopId, Shop.Review input, String traceId) {
        var replay = store.claim(key);
        if (replay.isPresent()) return replay.get();
        Shop current = store.find(shopId).orElseThrow(() ->
                new MerchantFailure(404, "SHOP_NOT_FOUND", "Shop was not found."));
        Shop changed = current.review(input);
        store.update(changed, input.expectedVersion(), current.status());
        store.review(current, key.actor(), input);
        store.audit(changed, key.actor(), "REVIEW_SHOP", current.status(), input.reason(), traceId);
        Result result = new Result(200, changed);
        store.complete(key, result);
        return result;
    }
}
