package com.agora.catalog;

import com.agora.catalog.domain.Product;
import com.agora.catalog.domain.CatalogFailure;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProductTests {
    Product.Input input(String... codes) {
        return new Product.Input("Fresh fish", null, "SEAFOOD", "FRESH", "Viet Nam", null, "Keep cold",
                java.util.Arrays.stream(codes).map(code -> new Product.SkuInput(code, "500 g", 50000, 500, 600, 20, 10, 5)).toList());
    }
    @Test void replacementKeepsIdsForUnchangedCodesAndDropsRemovedSkus() {
        Product product = Product.draft(UUID.randomUUID(), input("FISH-500", "FISH-1000"));
        assertThat(product.status()).isEqualTo("DRAFT");
        assertThat(product.version()).isZero();
        assertThat(product.skus()).allSatisfy(sku -> assertThat(sku.saleUnit()).isEqualTo("PACKAGE"));
        Product changed = product.replace(0, input("FISH-1000", "FISH-2000"));
        assertThat(changed.skus().getFirst().id()).isEqualTo(product.skus().get(1).id());
        assertThat(changed.skus().get(1).id()).isNotIn(product.skus().stream().map(Product.Sku::id).toList());
        assertThat(changed.skus()).extracting(Product.Sku::code).containsExactly("FISH-1000", "FISH-2000");
        assertThat(changed.version()).isEqualTo(1);
        assertThat(changed.createdAt()).isEqualTo(product.createdAt());
        assertThatThrownBy(() -> changed.skus().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void replacementRejectsStaleVersionAndNonDraftState() {
        Product product = Product.draft(UUID.randomUUID(), input("SKU"));
        assertThatThrownBy(() -> product.replace(1, input("SKU")))
                .isInstanceOfSatisfying(CatalogFailure.class, e -> assertThat(e.code()).isEqualTo("STALE_VERSION"));
        Product locked = new Product(product.id(), product.shopId(), product.name(), product.description(), product.category(),
                product.condition(), product.origin(), product.producerName(), product.storageInstructions(), List.copyOf(product.skus()),
                "ACTIVE", 0, product.createdAt(), product.updatedAt());
        assertThatThrownBy(() -> locked.replace(0, input("SKU")))
                .isInstanceOfSatisfying(CatalogFailure.class, e -> assertThat(e.status()).isEqualTo(409));
    }
}
