package com.agora.identity;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AccountTypeMigrationTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("agora.test.postgres-image", "postgres:16.15-alpine"));

    @Test
    void upgradePreservesLegacyIdentityAndCredentialsWithoutGrantingEitherType() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).defaultSchema("identity").schemas("identity").target("3").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.accounts(id,email,password_hash,display_name,status,created_at,updated_at) "
                + "VALUES (?, 'legacy@example.test', 'synthetic-existing-hash', 'Legacy', 'ACTIVE', now(), now())", id);
        jdbc.update("INSERT INTO identity.account_roles(account_id,role) VALUES (?, 'USER'), (?, 'ADMIN')", id, id);
        Flyway.configure().dataSource(source).defaultSchema("identity").schemas("identity").load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.accounts WHERE id = ? AND account_type IS NULL "
                + "AND password_hash = 'synthetic-existing-hash' AND version = 0", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.account_roles WHERE account_id = ?", Integer.class, id)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE identity.accounts SET account_type = 'ADMIN' WHERE id = ?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        POSTGRES.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(
                java.nio.file.Path.of("scripts/classify-legacy-account.sql").toAbsolutePath()), "/tmp/classify.sql");
        String primary = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO identity.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) "
                + "VALUES (?, ?, 0, 0, 1800, 9999999999999, ?)", primary, UUID.randomUUID().toString(), id.toString());
        jdbc.update("INSERT INTO identity.spring_session_attributes VALUES (?, 'test', ?)", primary, new byte[]{1});
        // Same expected version: exactly one operator may classify this account.
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> classify(id, "SELLER", 0));
            var second = pool.submit(() -> classify(id, "BUYER", 0));
            assertThat(java.util.List.of(first.get().getExitCode(), second.get().getExitCode()))
                    .containsExactlyInAnyOrder(0, 3);
        }
        String type = jdbc.queryForObject("SELECT account_type FROM identity.accounts WHERE id = ?", String.class, id);
        assertThat(type).isIn("BUYER", "SELLER");
        assertThat(jdbc.queryForObject("SELECT version FROM identity.accounts WHERE id = ?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.spring_session WHERE principal_name = ?", Integer.class, id.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.spring_session_attributes WHERE session_primary_id = ?", Integer.class, primary)).isZero();
        assertThat(classify(id, type.equals("BUYER") ? "SELLER" : "BUYER", 1).getExitCode()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT account_type FROM identity.accounts WHERE id = ?", String.class, id)).isEqualTo(type);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity.accounts WHERE id = ?", String.class, id)).isEqualTo("synthetic-existing-hash");

        UUID rollback = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.accounts(id,email,password_hash,display_name,status,created_at,updated_at) "
                + "VALUES (?, 'rollback@example.test', 'synthetic', 'Rollback', 'ACTIVE', now(), now())", rollback);
        assertThat(classify(rollback, "SELLER", 9).getExitCode()).isNotZero();
        jdbc.update("INSERT INTO identity.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) "
                + "VALUES (?, ?, 0, 0, 1800, 9999999999999, ?)", UUID.randomUUID().toString(), UUID.randomUUID().toString(), rollback.toString());
        jdbc.execute("CREATE FUNCTION identity.reject_session_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic deletion failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON identity.spring_session FOR EACH ROW EXECUTE FUNCTION identity.reject_session_delete()");
        assertThat(classify(rollback, "SELLER", 0).getExitCode()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.accounts WHERE id = ? AND account_type IS NULL AND version = 0", Integer.class, rollback)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.spring_session WHERE principal_name = ?", Integer.class, rollback.toString())).isEqualTo(1);
    }

    private org.testcontainers.containers.Container.ExecResult classify(UUID id, String type, int version) throws Exception {
        return POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "-v", "account_id=" + id, "-v", "account_type=" + type, "-v", "expected_version=" + version,
                "-f", "/tmp/classify.sql");
    }
}
