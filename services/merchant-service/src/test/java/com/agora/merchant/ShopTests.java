package com.agora.merchant;

import com.agora.merchant.domain.Shop;
import com.agora.merchant.domain.Shop.Address;
import com.agora.merchant.domain.Shop.Patch;
import com.agora.merchant.domain.MerchantFailure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShopTests {
    private Shop draft() {
        return Shop.draft(UUID.randomUUID(), "Fresh shop", "Fish", "0901234567",
                new Address("VN", "opaque-P", "Province", "opaque-W", "Ward", "Street"));
    }
    @Test void createsDraftAndPatchesOnlySuppliedFieldsWithVersionGuard() {
        Shop shop = draft();
        assertThat(shop.status()).isEqualTo("DRAFT");
        assertThat(shop.version()).isZero();
        Shop changed = shop.patch(new Patch(0, "New shop", null, true, null, null));
        assertThat(changed.name()).isEqualTo("New shop");
        assertThat(changed.description()).isNull();
        assertThat(changed.contactPhone()).isEqualTo(shop.contactPhone());
        assertThat(changed.pickupAddress()).isEqualTo(shop.pickupAddress());
        assertThat(changed.version()).isEqualTo(1);
        assertThatThrownBy(() -> changed.patch(new Patch(0, "Stale", null, false, null, null)))
                .isInstanceOfSatisfying(MerchantFailure.class, e -> assertThat(e.status()).isEqualTo(409));
    }
    @Test void refusesEditingPendingActiveAndSuspendedShops() {
        Shop shop = draft();
        for (String status : new String[]{"PENDING_REVIEW", "ACTIVE", "SUSPENDED"}) {
            Shop locked = new Shop(shop.id(), shop.ownerAccountId(), shop.name(), shop.description(),
                    shop.contactPhone(), shop.pickupAddress(), status, 0, null, Instant.now(), Instant.now());
            assertThatThrownBy(() -> locked.patch(new Patch(0, "Changed", null, false, null, null)))
                    .isInstanceOfSatisfying(MerchantFailure.class, e -> assertThat(e.status()).isEqualTo(409));
        }
    }
    @Test void editingRejectedShopReturnsToDraftAndPreservesReviewFeedback() {
        Shop shop = draft();
        Shop rejected = new Shop(shop.id(), shop.ownerAccountId(), shop.name(), shop.description(),
                shop.contactPhone(), shop.pickupAddress(), "REJECTED", 2, "Correct the address", shop.createdAt(), shop.updatedAt());
        Shop changed = rejected.patch(new Patch(2, "Corrected", null, false, null, null));
        assertThat(changed.status()).isEqualTo("DRAFT");
        assertThat(changed.version()).isEqualTo(3);
        assertThat(changed.lastReviewReason()).isEqualTo("Correct the address");
    }
}
