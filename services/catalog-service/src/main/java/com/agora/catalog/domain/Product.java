package com.agora.catalog.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

public record Product(UUID id, UUID shopId, String name, String description, String category, String condition,
                      String origin, String producerName, String storageInstructions, List<Sku> skus,
                      String status, long version, Instant createdAt, Instant updatedAt) {
    private static final Pattern NONBLANK = Pattern.compile("[^\\s\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]");
    public Product { skus = List.copyOf(skus); }
    public record SkuInput(String code, String label, long priceVnd, int netWeightGrams, int shippingWeightGrams,
                           int lengthCm, int widthCm, int heightCm) {}
    public record Sku(UUID id, String code, String label, long priceVnd, int netWeightGrams, int shippingWeightGrams,
                      int lengthCm, int widthCm, int heightCm, String saleUnit) {}
    public record Input(String name, String description, String category, String condition, String origin,
                        String producerName, String storageInstructions, List<SkuInput> skus) {
        public Input { skus = List.copyOf(skus); }
    }
    public static Product draft(UUID shopId, Input input) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return build(UUID.randomUUID(), shopId, input, Map.of(), 0, now, now, "DRAFT");
    }
    public Product replace(long expectedVersion, Input input) {
        if (version != expectedVersion)
            throw new CatalogFailure(409, "STALE_VERSION", "Product version has changed.");
        if (!editable())
            throw new CatalogFailure(409, "INVALID_PRODUCT_STATE", "Only DRAFT or HIDDEN products can be edited.");
        return build(id, shopId, input, skus.stream().collect(Collectors.toMap(Sku::code, Sku::id)),
                nextVersion(), createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS), status);
    }
    public Product changeVisibility(long expectedVersion, String target) {
        if (version != expectedVersion)
            throw new CatalogFailure(409, "STALE_VERSION", "Product version has changed.");
        if (!"PUBLISHED".equals(target) && !"HIDDEN".equals(target))
            throw new CatalogFailure(422, "VALIDATION_ERROR", "Visibility must be PUBLISHED or HIDDEN.");
        if (!("PUBLISHED".equals(target) ? editable() : "PUBLISHED".equals(status)))
            throw new CatalogFailure(409, "INVALID_PRODUCT_STATE", "Product cannot make this transition.");
        if ("PUBLISHED".equals(target) && (!hasText(name) || !hasText(origin) || !hasText(storageInstructions)
                || skus.isEmpty() || skus.size() > 10 || skus.stream().anyMatch(sku -> !hasText(sku.label()))))
            throw new CatalogFailure(422, "PUBLICATION_NOT_READY", "Saved product data is incomplete.");
        return new Product(id, shopId, name, description, category, condition, origin, producerName, storageInstructions,
                skus, target, nextVersion(), createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
    private boolean editable() { return "DRAFT".equals(status) || "HIDDEN".equals(status); }
    private static boolean hasText(String text) { return text != null && NONBLANK.matcher(text).find(); }
    private long nextVersion() {
        if (version >= 9007199254740991L) throw new CatalogFailure(409, "STALE_VERSION", "Product version is exhausted.");
        return version + 1;
    }
    private static Product build(UUID id, UUID shopId, Input input, Map<String, UUID> previousIds,
                                 long version, Instant created, Instant updated, String status) {
        var skus = input.skus().stream().map(sku -> new Sku(previousIds.containsKey(sku.code())
                ? previousIds.get(sku.code()) : UUID.randomUUID(), sku.code(), sku.label(), sku.priceVnd(),
                sku.netWeightGrams(), sku.shippingWeightGrams(), sku.lengthCm(), sku.widthCm(), sku.heightCm(), "PACKAGE")).toList();
        return new Product(id, shopId, input.name(), input.description(), input.category(), input.condition(),
                input.origin(), input.producerName(), input.storageInstructions(), skus, status, version, created, updated);
    }
}
