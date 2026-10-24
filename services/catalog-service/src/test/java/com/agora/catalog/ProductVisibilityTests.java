package com.agora.catalog;

import com.agora.catalog.domain.CatalogFailure;
import com.agora.catalog.domain.Product;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProductVisibilityTests {
    Product.Input input() {
        return new Product.Input("Fresh fish", null, "SEAFOOD", "FRESH", "Viet Nam", null, "Keep cold",
                List.of(new Product.SkuInput("FISH-500", "500 g", 50000, 500, 600, 20, 10, 5)));
    }
    Product draft() { return Product.draft(UUID.randomUUID(), input()); }
    void failure(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CatalogFailure.class, e -> {
            assertThat(e.status()).isEqualTo(status); assertThat(e.code()).isEqualTo(code);
        });
    }
    @Test void publishPreservesContentSkusAndIdentityAndIncrementsVersion() {
        Product draft = draft(), published = draft.changeVisibility(0, "PUBLISHED");
        assertThat(published.status()).isEqualTo("PUBLISHED"); assertThat(published.version()).isEqualTo(1);
        assertThat(published.id()).isEqualTo(draft.id()); assertThat(published.shopId()).isEqualTo(draft.shopId());
        assertThat(published.createdAt()).isEqualTo(draft.createdAt());
        assertThat(published.skus()).isEqualTo(draft.skus()); assertThat(published.name()).isEqualTo(draft.name());
    }
    @Test void hiddenContentRemainsHiddenUntilExplicitRepublish() {
        Product published = draft().changeVisibility(0, "PUBLISHED");
        Product hidden = published.changeVisibility(1, "HIDDEN");
        Product edited = hidden.replace(2, input());
        assertThat(edited.status()).isEqualTo("HIDDEN"); assertThat(edited.version()).isEqualTo(3);
        assertThat(edited.skus()).isEqualTo(published.skus());
        assertThat(edited.changeVisibility(3, "PUBLISHED").status()).isEqualTo("PUBLISHED");
    }
    @Test void invalidOrRepeatedTransitionsAreRejected() {
        Product draft = draft();
        failure(() -> draft.changeVisibility(0, "HIDDEN"), 409, "INVALID_PRODUCT_STATE");
        Product published = draft.changeVisibility(0, "PUBLISHED");
        failure(() -> published.changeVisibility(1, "PUBLISHED"), 409, "INVALID_PRODUCT_STATE");
        failure(() -> published.changeVisibility(1, "BLOCKED"), 422, "VALIDATION_ERROR");
        failure(() -> published.replace(1, input()), 409, "INVALID_PRODUCT_STATE");
    }
    @Test void staleAndUnsafeNextVersionsAreRejectedWithoutOverflow() {
        Product draft = draft();
        failure(() -> draft.changeVisibility(1, "PUBLISHED"), 409, "STALE_VERSION");
        Product maximum = new Product(draft.id(), draft.shopId(), draft.name(), draft.description(), draft.category(), draft.condition(),
                draft.origin(), draft.producerName(), draft.storageInstructions(), draft.skus(), "DRAFT", 9007199254740991L, draft.createdAt(), draft.updatedAt());
        failure(() -> maximum.changeVisibility(maximum.version(), "PUBLISHED"), 409, "STALE_VERSION");
        failure(() -> maximum.replace(maximum.version(), input()), 409, "STALE_VERSION");
    }
    @Test void publicationRejectsBlankRequiredTextIncludingEcmascriptWhitespace() {
        Product valid = draft();
        for (String whitespace : List.of("   ", "\u00a0\u00a0\u00a0", "\ufeff\ufeff\ufeff")) {
            for (int field = 0; field < 4; field++) {
                var skus = field == 3 ? List.of(new Product.Sku(valid.skus().getFirst().id(), "FISH-500", whitespace,
                        50000, 500, 600, 20, 10, 5, "PACKAGE")) : valid.skus();
                Product invalid = new Product(valid.id(), valid.shopId(), field == 0 ? whitespace : valid.name(), valid.description(),
                        valid.category(), valid.condition(), field == 1 ? whitespace : valid.origin(), valid.producerName(),
                        field == 2 ? whitespace : valid.storageInstructions(), skus, "DRAFT", 0, valid.createdAt(), valid.updatedAt());
                failure(() -> invalid.changeVisibility(0, "PUBLISHED"), 422, "PUBLICATION_NOT_READY");
            }
        }
    }
    @Test void publicationRequiresOneToTenSkus() {
        Product valid = draft();
        for (List<Product.Sku> skus : List.of(List.<Product.Sku>of(), Collections.nCopies(11, valid.skus().getFirst()))) {
            Product invalid = new Product(valid.id(), valid.shopId(), valid.name(), valid.description(), valid.category(), valid.condition(),
                    valid.origin(), valid.producerName(), valid.storageInstructions(), skus, "DRAFT", 0, valid.createdAt(), valid.updatedAt());
            failure(() -> invalid.changeVisibility(0, "PUBLISHED"), 422, "PUBLICATION_NOT_READY");
        }
    }
}
