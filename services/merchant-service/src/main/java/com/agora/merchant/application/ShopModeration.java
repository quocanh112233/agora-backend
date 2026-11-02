package com.agora.merchant.application;

import com.agora.merchant.domain.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShopModeration {
    private final MerchantStore store;
    private final IdentityPort identity;
    public ShopModeration(MerchantStore store, IdentityPort identity) { this.store = store; this.identity = identity; }
    @Transactional
    public Shop change(IdentityPort.Principal actor, UUID id, Shop.StatusChange input, String trace) {
        try {
            if (actor.displayName() == null || actor.displayName().isEmpty() || actor.displayName().codePointCount(0, actor.displayName().length()) > 100
                    || actor.displayName().indexOf('\0') >= 0) throw unavailable();
            var current = find(id);
            var changed = current.moderate(input);
            if ("ACTIVE".equals(changed.status()) && !identity.activeSellers(List.of(current.ownerAccountId())).contains(current.ownerAccountId()))
                throw new MerchantFailure(409, "OWNER_INACTIVE", "Shop owner must be an active SELLER.");
            store.update(changed, input.expectedVersion(), current.status());
            store.statusAudit(changed, actor.accountId(), actor.displayName(), current.status(), input.reason(), trace);
            return changed;
        } catch (DataAccessException e) { throw unavailable(); }
    }
    public MerchantStore.HistoryPage history(UUID target, String cursor) {
        Instant time = null; UUID id = null;
        if (cursor != null) {
            try {
                if (!cursor.matches("[A-Za-z0-9_-]{1,512}")) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 3 || !target.toString().equals(parts[2])) throw new IllegalArgumentException();
                time = Instant.parse(parts[0]); id = UUID.fromString(parts[1]);
                if (!id.toString().equals(parts[1]) || time.getNano() % 1000 != 0 || time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                        || !time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) throw new IllegalArgumentException();
            } catch (RuntimeException e) { throw new MerchantFailure(422, "VALIDATION_ERROR", "Invalid history cursor."); }
        }
        try {
            find(target);
            var rows = store.history(target, time, id, 21);
            var items = rows.subList(0, Math.min(rows.size(), 20));
            String next = null;
            if (rows.size() > 20) {
                var last = items.getLast();
                next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.createdAt() + "|" + last.id() + "|" + target).getBytes(StandardCharsets.UTF_8));
            }
            return new MerchantStore.HistoryPage(items, next);
        } catch (DataAccessException e) { throw unavailable(); }
    }
    private Shop find(UUID id) { return store.find(id).orElseThrow(() -> new MerchantFailure(404, "SHOP_NOT_FOUND", "Shop was not found.")); }
    private static MerchantFailure unavailable() { return new MerchantFailure(503, "MERCHANT_UNAVAILABLE", "Shop moderation is unavailable."); }
}
