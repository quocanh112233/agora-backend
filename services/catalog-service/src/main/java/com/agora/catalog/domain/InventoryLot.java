package com.agora.catalog.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record InventoryLot(UUID id, UUID productId, UUID shopId, UUID skuId, String code, LocalDate producedOn,
                           LocalDate expiresOn, long onHand, long held, long allocated, String state,
                           long version, Instant createdAt, Instant updatedAt) {
    public record Input(UUID skuId, String code, long quantity, LocalDate producedOn, LocalDate expiresOn) {}
    public record View(UUID id, UUID productId, UUID skuId, String code, LocalDate producedOn, LocalDate expiresOn,
                       long onHand, long held, long allocated, long available, String status, long version,
                       Instant createdAt, Instant updatedAt) {}
    public static InventoryLot create(UUID productId, UUID shopId, Input input, LocalDate today) {
        if (input.skuId() == null || input.code() == null || !input.code().matches("[A-Z0-9][A-Z0-9_-]{0,39}")
                || input.quantity() < 1 || input.quantity() > 1000000 || input.expiresOn() == null
                || !input.expiresOn().isAfter(today) || input.expiresOn().getYear() > 9999
                || input.producedOn() != null && (input.producedOn().isAfter(today) || input.producedOn().getYear() < 1))
            throw new CatalogFailure(422,"VALIDATION_ERROR","Provide a valid lot code, quantity and production/expiry dates.");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new InventoryLot(UUID.randomUUID(),productId,shopId,input.skuId(),input.code(),input.producedOn(),input.expiresOn(),
                input.quantity(),0,0,"ACTIVE",0,now,now);
    }
    public View view(LocalDate today) {
        long available = onHand-held-allocated;
        String status = state.equals("BLOCKED") ? "BLOCKED" : !expiresOn.isAfter(today) ? "EXPIRED" : available == 0 ? "DEPLETED" : "ACTIVE";
        return new View(id,productId,skuId,code,producedOn,expiresOn,onHand,held,allocated,available,status,version,createdAt,updatedAt);
    }
    public InventoryLot changeStatus(long expectedVersion, String target, LocalDate today) {
        if (!"ACTIVE".equals(target) && !"BLOCKED".equals(target))
            throw new CatalogFailure(422,"VALIDATION_ERROR","Lot status must be ACTIVE or BLOCKED.");
        if (version != expectedVersion || version >= 9007199254740991L)
            throw new CatalogFailure(409,"STALE_VERSION","Lot version has changed or is exhausted.");
        if (state.equals(target)) throw new CatalogFailure(409,"INVALID_LOT_STATE","Lot already has this stored state.");
        if (target.equals("ACTIVE") && !expiresOn.isAfter(today))
            throw new CatalogFailure(422,"LOT_EXPIRED","An expired lot cannot be reopened.");
        return new InventoryLot(id,productId,shopId,skuId,code,producedOn,expiresOn,onHand,held,allocated,target,
                version+1,createdAt,Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
