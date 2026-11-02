package com.agora.merchant;

import com.agora.merchant.adapter.outbound.JdbcMerchantStore;
import com.agora.merchant.application.MerchantStore.Key;
import com.agora.merchant.application.ShopService;
import com.agora.merchant.domain.Shop.Address;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class MerchantMigrationTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));

    @Test void upgradesAppliedV1WithoutChangingDraftAuditOrIdempotencySnapshot() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).schemas("merchant").target("1").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var store = new JdbcMerchantStore(jdbc, JsonMapper.builder().build());
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var service = new ShopService(store);
        UUID owner = UUID.randomUUID();
        var key = new Key(owner, "createShop", UUID.randomUUID(), "a".repeat(64));
        var input = new ShopService.Create("Existing shop", "V1 data", "0901234567",
                new Address("VN", "opaque-P", "Province", "opaque-W", "Ward", "12 Street"));
        var original = transactions.execute(status -> service.create(key, input, UUID.randomUUID().toString()));
        var oldAudits = jdbc.queryForList("select * from merchant.audit_entries");
        var oldKeys = jdbc.queryForList("select * from merchant.idempotency_records");

        var migration = Flyway.configure().dataSource(source).schemas("merchant").load().migrate();

        assertThat(migration.migrationsExecuted).isEqualTo(2);
        assertThat(service.mine(owner)).isEqualTo(original.shop());
        assertThat(jdbc.queryForList("select id,actor_account_id,shop_id,action,previous_status,next_status,reason,trace_id,created_at from merchant.audit_entries")).isEqualTo(oldAudits);
        assertThat(jdbc.queryForList("select * from merchant.idempotency_records")).isEqualTo(oldKeys);
        var replay = transactions.execute(status -> store.claim(key).orElseThrow());
        assertThat(replay).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from merchant.shop_reviews", Long.class)).isZero();
        assertThat(jdbc.queryForList("select version from merchant.flyway_schema_history where success and version is not null order by installed_rank",
                String.class)).containsExactly("1", "2", "3");
    }
}
