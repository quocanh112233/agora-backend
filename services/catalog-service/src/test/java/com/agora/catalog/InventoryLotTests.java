package com.agora.catalog;

import com.agora.catalog.domain.InventoryLot;
import com.agora.catalog.domain.CatalogFailure;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InventoryLotTests {
    static final LocalDate TODAY = LocalDate.of(2026,10,24);
    InventoryLot create(LocalDate produced, LocalDate expires, long quantity) {
        return InventoryLot.create(UUID.randomUUID(), UUID.randomUUID(),
                new InventoryLot.Input(UUID.randomUUID(), "LOT_01", quantity, produced, expires), TODAY);
    }
    @Test void createsIntegerPackagesWithZeroHoldsAndIndependentVersion() {
        var lot = create(null, TODAY.plusDays(2), 12);
        assertThat(lot.view(TODAY).available()).isEqualTo(12);
        assertThat(lot.view(TODAY).status()).isEqualTo("ACTIVE");
        assertThat(lot.view(TODAY).producedOn()).isNull();
        assertThat(lot.held()).isZero(); assertThat(lot.allocated()).isZero(); assertThat(lot.version()).isZero();
    }
    @Test void validatesDatesQuantityAndCodesAtCreation() {
        for (LocalDate expiry : new LocalDate[]{TODAY, TODAY.minusDays(1), null})
            assertThatThrownBy(() -> create(null, expiry, 1)).isInstanceOf(CatalogFailure.class);
        assertThatThrownBy(() -> create(TODAY.plusDays(1), TODAY.plusDays(5), 1)).isInstanceOf(CatalogFailure.class);
        for (long quantity : new long[]{0, -1, 1000001})
            assertThatThrownBy(() -> create(null, TODAY.plusDays(1), quantity)).isInstanceOf(CatalogFailure.class);
        assertThat(create(TODAY, TODAY.plusDays(1), 1000000).onHand()).isEqualTo(1000000);
        assertThatThrownBy(() -> InventoryLot.create(UUID.randomUUID(), UUID.randomUUID(),
                new InventoryLot.Input(UUID.randomUUID(), " ", 1, null, TODAY.plusDays(1)), TODAY)).isInstanceOf(CatalogFailure.class);
    }
    @Test void expiryIsDerivedWithoutAWorkerAndBlockedTakesPrecedence() {
        var lot = create(TODAY.minusDays(1), TODAY.plusDays(1), 8);
        assertThat(lot.view(TODAY.plusDays(1)).status()).isEqualTo("EXPIRED");
        assertThat(lot.view(TODAY.plusDays(1)).available()).isEqualTo(8);
        var blocked = lot.changeStatus(0, "BLOCKED", TODAY);
        assertThat(blocked.view(TODAY.plusDays(2)).status()).isEqualTo("BLOCKED");
        assertThatThrownBy(() -> blocked.changeStatus(1, "ACTIVE", TODAY.plusDays(1)))
                .isInstanceOfSatisfying(CatalogFailure.class,e -> assertThat(e.status()).isEqualTo(422));
    }
    @Test void blockAndUnblockPreservePhysicalCountersAndGuardStateVersion() {
        var lot = create(null, TODAY.plusDays(5), 11);
        var blocked = lot.changeStatus(0, "BLOCKED", TODAY);
        var active = blocked.changeStatus(1, "ACTIVE", TODAY);
        assertThat(active.onHand()).isEqualTo(11); assertThat(active.version()).isEqualTo(2);
        assertThat(active.createdAt()).isEqualTo(lot.createdAt());
        assertThatThrownBy(() -> lot.changeStatus(1,"BLOCKED",TODAY)).isInstanceOf(CatalogFailure.class);
        assertThatThrownBy(() -> lot.changeStatus(0,"ACTIVE",TODAY)).isInstanceOf(CatalogFailure.class);
        assertThatThrownBy(() -> lot.changeStatus(0,"EXPIRED",TODAY)).isInstanceOf(CatalogFailure.class);
    }
    @Test void exhaustedVersionCannotWrapAndDepletedDoesNotChangePhysicalArithmetic() {
        var lot = create(null,TODAY.plusDays(5),3);
        var depleted = new InventoryLot(lot.id(),lot.productId(),lot.shopId(),lot.skuId(),lot.code(),lot.producedOn(),lot.expiresOn(),
                3,1,2,"ACTIVE",9007199254740991L,lot.createdAt(),lot.updatedAt());
        assertThat(depleted.view(TODAY).status()).isEqualTo("DEPLETED"); assertThat(depleted.view(TODAY).available()).isZero();
        assertThatThrownBy(() -> depleted.changeStatus(9007199254740991L,"BLOCKED",TODAY)).isInstanceOf(CatalogFailure.class);
    }
    @Test void vietnamDateChangesSevenHoursBeforeUtcMidnight() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-24T17:00:00Z"),ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(LocalDate.now(clock)).isEqualTo(TODAY.plusDays(1));
        assertThat(create(null,TODAY.plusDays(1),1).view(LocalDate.now(clock)).status()).isEqualTo("EXPIRED");
    }
}
