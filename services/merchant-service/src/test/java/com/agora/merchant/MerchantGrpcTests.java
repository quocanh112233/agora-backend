package com.agora.merchant;

import com.agora.contracts.merchant.v1.MerchantAccessGrpc;
import com.agora.contracts.merchant.v1.ResolveActiveShopRequest;
import com.agora.merchant.adapter.outbound.JdbcMerchantStore;
import com.agora.merchant.application.ShopService;
import com.agora.merchant.configuration.MerchantGrpcServer;
import com.agora.merchant.domain.Shop;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "agora.grpc.enabled=true", "agora.grpc.port=0", "agora.grpc.token=" + MerchantGrpcTests.TOKEN,
        "agora.identity.token=" + MerchantGrpcTests.TOKEN, "spring.config.import=" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MerchantGrpcTests {
    static final String TOKEN = "merchant-test-internal-token-at-least-32-characters";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.agora.merchant.application.IdentityPort identity;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcMerchantStore store;
    @Autowired ShopService shops;
    @Autowired MerchantGrpcServer server;
    ManagedChannel channel;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @BeforeEach void start() {
        org.mockito.Mockito.when(identity.activeSellers(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call -> { java.util.List<UUID> ids = call.getArgument(0); return java.util.Set.copyOf(ids); });
        server.start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().disableRetry().build();
    }
    @AfterEach void stop() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.stop();
    }
    Shop shop(String status) {
        Shop shop = Shop.draft(UUID.randomUUID(), "Private merchant name", "Private description", "0901234567",
                new Shop.Address("VN", "opaque-P", "Province", "opaque-W", "Ward", "Private street"));
        store.insert(shop);
        jdbc.update("UPDATE merchant.shops SET status=? WHERE id=?", status, shop.id());
        return shop;
    }
    MerchantAccessGrpc.MerchantAccessBlockingStub client(String token) {
        var stub = MerchantAccessGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS);
        if (token == null) return stub;
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-agora-internal-token", Metadata.ASCII_STRING_MARSHALLER), token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
    @Test void activeShopRequiresFreshEligibleSellerOwner() {
        var current = shop("ACTIVE");
        org.mockito.Mockito.when(identity.activeSellers(java.util.List.of(current.ownerAccountId()))).thenReturn(java.util.Set.of());
        denied(current.ownerAccountId().toString(), Status.Code.PERMISSION_DENIED);
        org.mockito.Mockito.when(identity.activeSellers(java.util.List.of(current.ownerAccountId())))
                .thenThrow(new com.agora.merchant.domain.MerchantFailure(503, "IDENTITY_UNAVAILABLE", "Unavailable"));
        denied(current.ownerAccountId().toString(), Status.Code.UNAVAILABLE);
    }
    ResolveActiveShopRequest account(String id) { return ResolveActiveShopRequest.newBuilder().setAccountId(id).build(); }
    void denied(String id, Status.Code code) {
        assertThatThrownBy(() -> client(TOKEN).resolveActiveShop(account(id)))
                .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                    assertThat(failure.getStatus().getCode()).isEqualTo(code);
                    assertThat(failure.getMessage()).doesNotContain("Private merchant name", "Private description", "Private street", "SELECT", "SQLException", "merchant.shops");
                });
    }

    @Test void activeOwnerReceivesOnlyShopId() {
        Shop shop = shop("ACTIVE");
        var response = client(TOKEN).resolveActiveShop(account(shop.ownerAccountId().toString().toUpperCase(java.util.Locale.ROOT)));
        assertThat(response.getShopId()).isEqualTo(shop.id().toString());
        assertThat(response.getAllFields()).hasSize(1);
        assertThat(response.getDescriptorForType().getFields()).extracting(field -> field.getName()).containsExactly("shop_id");
    }
    @Test void everyCallReadsCurrentStateAndOwner() {
        Shop shop = shop("ACTIVE");
        String owner = shop.ownerAccountId().toString();
        assertThat(client(TOKEN).resolveActiveShop(account(owner)).getShopId()).isEqualTo(shop.id().toString());
        for (String status : List.of("DRAFT", "PENDING_REVIEW", "REJECTED", "SUSPENDED")) {
            jdbc.update("UPDATE merchant.shops SET status=? WHERE id=?", status, shop.id());
            denied(owner, Status.Code.PERMISSION_DENIED);
            jdbc.update("UPDATE merchant.shops SET status='ACTIVE' WHERE id=?", shop.id());
            assertThat(client(TOKEN).resolveActiveShop(account(owner)).getShopId()).isEqualTo(shop.id().toString());
        }
        UUID newOwner = UUID.randomUUID();
        jdbc.update("UPDATE merchant.shops SET owner_account_id=? WHERE id=?", newOwner, shop.id());
        denied(owner, Status.Code.PERMISSION_DENIED);
        assertThat(client(TOKEN).resolveActiveShop(account(newOwner.toString())).getShopId()).isEqualTo(shop.id().toString());
    }
    @Test void absentShopCannotResolveAnotherOwnersActiveShop() {
        shop("ACTIVE");
        denied(UUID.randomUUID().toString(), Status.Code.PERMISSION_DENIED);
    }
    @Test void missingAndWrongInternalCredentialsFailClosed() {
        Shop shop = shop("ACTIVE");
        for (String token : new String[]{null, "", "wrong", TOKEN + "x", "x" + TOKEN.substring(1)}) {
            assertThatThrownBy(() -> client(token).resolveActiveShop(account(shop.ownerAccountId().toString())))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure -> {
                        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                        assertThat(failure.getStatus().getDescription()).isNull();
                    });
        }
    }
    @Test void malformedAndNonCanonicalAccountIdsAreInvalidArguments() {
        for (String id : List.of("", "not-a-uuid", "1-1-1-1-1", UUID.randomUUID() + " ",
                "------------------------------------", UUID.randomUUID().toString().replace("-", "")))
            denied(id, Status.Code.INVALID_ARGUMENT);
    }
    @Test void requestsAboveFourKiBAreRejectedBeforeResolution() {
        denied("x".repeat(5000), Status.Code.RESOURCE_EXHAUSTED);
    }
    @Test void persistenceOutageReturnsUnavailableWithoutDiagnosticsAndRecovers() {
        Shop shop = shop("ACTIVE");
        jdbc.execute("ALTER TABLE merchant.shops RENAME TO shops_unavailable");
        try { denied(shop.ownerAccountId().toString(), Status.Code.UNAVAILABLE); }
        finally { jdbc.execute("ALTER TABLE merchant.shops_unavailable RENAME TO shops"); }
        assertThat(client(TOKEN).resolveActiveShop(account(shop.ownerAccountId().toString())).getShopId()).isEqualTo(shop.id().toString());
    }
    @Test void startIsIdempotentAndShutdownClosesTheListener() {
        Shop shop = shop("ACTIVE");
        int port = server.port();
        server.start();
        assertThat(server.port()).isEqualTo(port);
        assertThat(server.isRunning()).isTrue();
        var listener = (io.grpc.Server) org.springframework.test.util.ReflectionTestUtils.getField(server, "server");
        assertThat(listener.getListenSockets()).singleElement().satisfies(address ->
                assertThat(((java.net.InetSocketAddress) address).getAddress().getHostAddress()).isEqualTo("127.0.0.1"));
        assertThat(client(TOKEN).resolveActiveShop(account(shop.ownerAccountId().toString())).getShopId()).isEqualTo(shop.id().toString());
        server.stop();
        server.stop();
        assertThat(server.isRunning()).isFalse();
        denied(shop.ownerAccountId().toString(), Status.Code.UNAVAILABLE);
    }
    @Test void internalCredentialRequires32To256UrlSafeCharacters() {
        for (String token : new String[]{null, "a".repeat(31), "a".repeat(257), "a".repeat(32) + "+",
                "a".repeat(32) + "/", "a".repeat(32) + "=", "a".repeat(32) + " ", "a".repeat(32) + "é"})
            assertThatThrownBy(() -> new MerchantGrpcServer(shops, identity, 0, token)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new MerchantGrpcServer(shops, identity, 0, "a".repeat(32))).isNotNull();
        assertThat(new MerchantGrpcServer(shops, identity, 0, "A_0-".repeat(64))).isNotNull();
    }
    @Test void listenerRequiresExplicitEnablementAndSpringStopsIt() {
        var runner = new ApplicationContextRunner().withUserConfiguration(MerchantGrpcServer.class)
                .withBean(ShopService.class, () -> shops)
                .withBean(com.agora.merchant.application.IdentityPort.class, () -> identity);
        runner.run(context -> assertThat(context).doesNotHaveBean(MerchantGrpcServer.class));
        runner.withPropertyValues("agora.grpc.enabled=false").run(context -> assertThat(context).doesNotHaveBean(MerchantGrpcServer.class));
        var started = new java.util.concurrent.atomic.AtomicReference<MerchantGrpcServer>();
        runner.withPropertyValues("agora.grpc.enabled=true", "agora.grpc.port=0", "agora.grpc.token=" + TOKEN).run(context -> {
            assertThat(context).hasSingleBean(MerchantGrpcServer.class);
            started.set(context.getBean(MerchantGrpcServer.class));
            assertThat(started.get().isRunning()).isTrue();
        });
        assertThat(started.get().isRunning()).isFalse();
    }
    @Test void yamlUsesMerchantSpecificEnablementPortAndSharedCredential() throws Exception {
        var env = new MockEnvironment();
        new YamlPropertySourceLoader().load("merchant", new ClassPathResource("application.yaml"))
                .forEach(source -> env.getPropertySources().addLast(source));
        assertThat(env.getProperty("agora.grpc.enabled")).isEqualTo("false");
        assertThat(env.getProperty("agora.grpc.port")).isEqualTo("9092");
        env.getPropertySources().addFirst(new MapPropertySource("merchant-grpc", Map.of(
                "MERCHANT_GRPC_ENABLED", "true", "MERCHANT_GRPC_PORT", "19092", "INTERSERVICE_TOKEN", TOKEN)));
        assertThat(env.getProperty("agora.grpc.enabled")).isEqualTo("true");
        assertThat(env.getProperty("agora.grpc.port")).isEqualTo("19092");
        assertThat(env.getProperty("agora.grpc.token")).isEqualTo(TOKEN);
    }
}
