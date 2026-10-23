package com.agora.merchant.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record Shop(UUID id, UUID ownerAccountId, String name, String description, String contactPhone,
                   Address pickupAddress, String status, long version, String lastReviewReason,
                   Instant createdAt, Instant updatedAt) {
    public record Address(String countryCode, String provinceCode, String provinceName, String wardCode,
                          String wardName, String streetAddress) {}
    public record Patch(long expectedVersion, String name, String description, boolean descriptionPresent,
                        String contactPhone, Address pickupAddress) {}
    public record Review(long expectedVersion, String decision, String reason) {}
    public static Shop draft(UUID owner, String name, String description, String phone, Address address) {
        return new Shop(UUID.randomUUID(), owner, name, description, phone, address, "DRAFT", 0, null,
                Instant.now().truncatedTo(ChronoUnit.MICROS), Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
    public Shop patch(Patch patch) {
        checkVersion(patch.expectedVersion());
        if (!"DRAFT".equals(status) && !"REJECTED".equals(status))
            throw new MerchantFailure(409, "INVALID_SHOP_STATE", "Only DRAFT or REJECTED shops can be edited.");
        return new Shop(id, ownerAccountId, patch.name() == null ? name : patch.name(),
                patch.descriptionPresent() ? patch.description() : description,
                patch.contactPhone() == null ? contactPhone : patch.contactPhone(),
                patch.pickupAddress() == null ? pickupAddress : patch.pickupAddress(),
                "DRAFT", Math.addExact(version, 1), lastReviewReason, createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
    public Shop submit(long expectedVersion) {
        checkVersion(expectedVersion);
        requireStatus("DRAFT");
        return transition("PENDING_REVIEW", lastReviewReason);
    }
    public Shop review(Review review) {
        checkVersion(review.expectedVersion());
        requireStatus("PENDING_REVIEW");
        return transition("APPROVE".equals(review.decision()) ? "ACTIVE" : "REJECTED", review.reason());
    }
    private void checkVersion(long expectedVersion) {
        if (version != expectedVersion)
            throw new MerchantFailure(409, "STALE_VERSION", "Shop version has changed.");
    }
    private void requireStatus(String required) {
        if (!required.equals(status))
            throw new MerchantFailure(409, "INVALID_SHOP_STATE", "Shop must be " + required + ".");
    }
    private Shop transition(String nextStatus, String reason) {
        return new Shop(id, ownerAccountId, name, description, contactPhone, pickupAddress,
                nextStatus, Math.addExact(version, 1), reason, createdAt, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
