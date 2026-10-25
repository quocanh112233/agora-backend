package com.agora.merchant;

import com.agora.contracts.merchant.v1.FindPublicShopsRequest;
import com.agora.contracts.merchant.v1.FindPublicShopsResponse;
import com.agora.contracts.merchant.v1.MerchantAccessGrpc;
import com.agora.contracts.merchant.v1.PublicShop;
import com.agora.merchant.adapter.outbound.JdbcMerchantStore;
import com.agora.merchant.application.MerchantStore;
import com.agora.merchant.application.ShopService;
import com.agora.merchant.configuration.MerchantGrpcServer;
import com.agora.merchant.domain.Shop;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "agora.grpc.enabled=true", "agora.grpc.port=0", "agora.grpc.token=" + MerchantGrpcTests.TOKEN,
        "agora.identity.token=" + MerchantGrpcTests.TOKEN, "spring.config.import=" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MerchantPublicShopGrpcTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcMerchantStore store;
    @Autowired MerchantGrpcServer server;
    ManagedChannel channel;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @BeforeEach void start() {
        server.start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().disableRetry().build();
    }
    @AfterEach void stop() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.stop();
    }
    Shop shop(String status, String name) {
        Shop shop = Shop.draft(UUID.randomUUID(), name, "Private description", "0901234567",
                new Shop.Address("VN", "opaque-P", "Province", "opaque-W", "Ward", "Private street"));
        store.insert(shop);
        jdbc.update("UPDATE merchant.shops SET status=?,last_review_reason='Private review reason' WHERE id=?", status, shop.id());
        return shop;
    }
    MerchantAccessGrpc.MerchantAccessBlockingStub client(String token) {
        var stub = MerchantAccessGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS);
        if (token == null) return stub;
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER), token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
    FindPublicShopsRequest request(List<String> ids) {
        return FindPublicShopsRequest.newBuilder().addAllShopIds(ids).build();
    }
    FindPublicShopsResponse find(String... ids) {
        return client(MerchantGrpcTests.TOKEN).findPublicShops(request(List.of(ids)));
    }
    void denied(List<String> ids, Status.Code code) {
        assertThatThrownBy(() -> client(MerchantGrpcTests.TOKEN).findPublicShops(request(ids)))
                .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                    assertThat(failure.getStatus().getCode()).isEqualTo(code);
                    assertThat(failure.getStatus().getDescription()).isNull();
                    assertThat(failure.getMessage()).doesNotContain("Private", "SELECT", "SQLException", "merchant.shops");
                });
    }

    @Test void acceptsOneThroughFiveDistinctIdsAndReturnsOnlyRequestedActiveShops() {
        shop("ACTIVE", "Unrequested shop");
        var ids = new ArrayList<String>();
        for (int count = 1; count <= 5; count++) {
            ids.add(shop("ACTIVE", "Public shop " + count).id().toString());
            assertThat(client(MerchantGrpcTests.TOKEN).findPublicShops(request(ids)).getShopsList())
                    .extracting(PublicShop::getId).containsExactlyInAnyOrderElementsOf(ids);
        }
    }
    @Test void projectionContainsOnlyIdAndUnmodifiedPublicNameWithoutPrivateData() {
        Shop shop = shop("ACTIVE", "  Chợ cá tươi 🐟  ");
        var response = find(shop.id().toString().toUpperCase(Locale.ROOT));
        assertThat(response.getShopsList()).containsExactly(PublicShop.newBuilder()
                .setId(shop.id().toString()).setName("  Chợ cá tươi 🐟  ").build());
        assertThat(response.getAllFields()).hasSize(1);
        assertThat(response.getShops(0).getAllFields().keySet()).extracting(field -> field.getName())
                .containsExactlyInAnyOrder("id", "name");
        assertThat(new String(response.toByteArray(), StandardCharsets.UTF_8)).doesNotContain(
                shop.ownerAccountId().toString(), "Private description", "0901234567", "Private street", "Private review reason",
                "owner_account_id", "contact_phone", "pickup_address", "version", "created_at", "updated_at");
    }
    @Test void absentAndEveryInactiveStateAreOmitted() {
        var ids = new ArrayList<String>();
        ids.add(UUID.randomUUID().toString());
        for (String status : List.of("DRAFT", "PENDING_REVIEW", "REJECTED", "SUSPENDED"))
            ids.add(shop(status, "Inactive shop").id().toString());
        assertThat(client(MerchantGrpcTests.TOKEN).findPublicShops(request(ids)).getShopsList()).isEmpty();
    }
    @Test void mixedBatchKeepsActiveShopAndOmitsMissingOrSuspendedShops() {
        Shop active = shop("ACTIVE", "Visible shop");
        Shop suspended = shop("SUSPENDED", "Suspended shop");
        assertThat(find(suspended.id().toString(), UUID.randomUUID().toString(), active.id().toString()).getShopsList())
                .containsExactly(PublicShop.newBuilder().setId(active.id().toString()).setName("Visible shop").build());
    }
    @Test void everyCallReadsCurrentStatusAndName() {
        Shop shop = shop("ACTIVE", "Original name");
        String id = shop.id().toString();
        assertThat(find(id).getShops(0).getName()).isEqualTo("Original name");
        jdbc.update("UPDATE merchant.shops SET name='Current name' WHERE id=?", shop.id());
        assertThat(find(id).getShops(0).getName()).isEqualTo("Current name");
        jdbc.update("UPDATE merchant.shops SET status='SUSPENDED' WHERE id=?", shop.id());
        assertThat(find(id).getShopsList()).isEmpty();
        jdbc.update("UPDATE merchant.shops SET status='ACTIVE' WHERE id=?", shop.id());
        assertThat(find(id).getShops(0).getName()).isEqualTo("Current name");
        jdbc.update("DELETE FROM merchant.shops WHERE id=?", shop.id());
        assertThat(find(id).getShopsList()).isEmpty();
    }
    @Test void fiveNamesOf120FourByteUnicodeCodePointsFitWithinFourKiB() {
        var ids = new ArrayList<String>();
        for (int index = 0; index < 5; index++) ids.add(shop("ACTIVE", "🐟".repeat(120)).id().toString());
        var response = client(MerchantGrpcTests.TOKEN).findPublicShops(request(ids));
        assertThat(response.getShopsList()).hasSize(5).allSatisfy(shop -> assertThat(shop.getName()).isEqualTo("🐟".repeat(120)));
        assertThat(response.getSerializedSize()).isLessThanOrEqualTo(4096);
    }
    @Test void blankActiveNamesAreOmittedWithoutPoisoningTheBatch() {
        Shop valid = shop("ACTIVE", "Valid shop");
        for (String name : List.of("   ", "\u2003\u2003\u2003")) {
            Shop blank = shop("ACTIVE", name);
            assertThat(find(blank.id().toString()).getShopsList()).isEmpty();
            assertThat(find(valid.id().toString(), blank.id().toString()).getShopsList())
                    .containsExactly(PublicShop.newBuilder().setId(valid.id().toString()).setName("Valid shop").build());
        }
    }
    @Test void providerNamesAbove120CodePointsAreRejectedWithoutTruncating() {
        Shop current = shop("ACTIVE", "Valid shop");
        MerchantStore provider = mock(MerchantStore.class);
        for (String name : List.of("x".repeat(121), "🐟".repeat(121))) {
            Shop invalid = new Shop(current.id(), current.ownerAccountId(), name, current.description(),
                    current.contactPhone(), current.pickupAddress(), "ACTIVE", current.version(), current.lastReviewReason(),
                    current.createdAt(), current.updatedAt());
            when(provider.find(current.id())).thenReturn(Optional.of(invalid));
            assertThatThrownBy(() -> new ShopService(provider).findPublicShops(List.of(current.id())))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void inactiveShopWithMaximumLengthNameIsOmitted() {
        Shop inactive = shop("SUSPENDED", "x".repeat(120));
        assertThat(find(inactive.id().toString()).getShopsList()).isEmpty();
    }
    @Test void zeroAndSixIdsAreInvalidArguments() {
        denied(List.of(), Status.Code.INVALID_ARGUMENT);
        denied(java.util.stream.IntStream.range(0, 6).mapToObj(index -> UUID.randomUUID().toString()).toList(),
                Status.Code.INVALID_ARGUMENT);
    }
    @Test void duplicateIdsIncludingDifferentCaseAreInvalidArguments() {
        String id = shop("ACTIVE", "Duplicate shop").id().toString();
        denied(List.of(id, id), Status.Code.INVALID_ARGUMENT);
        denied(List.of(id, id.toUpperCase(Locale.ROOT)), Status.Code.INVALID_ARGUMENT);
    }
    @Test void malformedAndNonCanonicalIdsAreInvalidArguments() {
        for (String id : List.of("", "not-a-uuid", "1-1-1-1-1", UUID.randomUUID() + " ",
                "------------------------------------", UUID.randomUUID().toString().replace("-", "")))
            denied(List.of(id), Status.Code.INVALID_ARGUMENT);
    }
    @Test void missingAndWrongInternalCredentialsFailClosedBeforeInputValidation() {
        for (String token : new String[]{null, "", "wrong", MerchantGrpcTests.TOKEN + "x"}) {
            assertThatThrownBy(() -> client(token).findPublicShops(request(List.of())))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                        assertThat(failure.getStatus().getDescription()).isNull();
                    });
        }
    }
    @Test void requestsAboveFourKiBAreRejected() {
        assertThatThrownBy(() -> find("x".repeat(5000)))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
    }
    @Test void persistenceOutageIsSafeAndValidationHappensBeforeAnyRead() {
        Shop shop = shop("ACTIVE", "Available shop");
        String id = shop.id().toString();
        jdbc.execute("ALTER TABLE merchant.shops RENAME TO shops_unavailable");
        try {
            denied(List.of(id), Status.Code.UNAVAILABLE);
            denied(List.of(id, "malformed"), Status.Code.INVALID_ARGUMENT);
            denied(List.of(id, id), Status.Code.INVALID_ARGUMENT);
            denied(List.of(), Status.Code.INVALID_ARGUMENT);
        } finally { jdbc.execute("ALTER TABLE merchant.shops_unavailable RENAME TO shops"); }
        assertThat(find(id).getShops(0).getName()).isEqualTo("Available shop");
    }
}
