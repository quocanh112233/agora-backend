package com.agora.identity.adapter.outbound;

import com.agora.identity.application.AccountRepository;
import com.agora.identity.domain.Account;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAccounts implements AccountRepository {
    private final JdbcTemplate jdbc;

    public JdbcAccounts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public Account create(Account account, String passwordHash) {
        jdbc.update("""
                INSERT INTO identity.accounts(id,email,password_hash,display_name,status,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """, account.id(), account.email(), passwordHash, account.displayName(), account.status(),
                Timestamp.from(account.createdAt()), Timestamp.from(account.createdAt()));
        jdbc.update("INSERT INTO identity.account_roles(account_id,role) VALUES (?, 'USER')", account.id());
        return account;
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return jdbc.query("SELECT * FROM identity.accounts WHERE id = ?", this::map, id).stream().findFirst();
    }

    @Override
    public Optional<Credentials> findByEmail(String email) {
        return jdbc.query("SELECT * FROM identity.accounts WHERE email = ?",
                (rs, row) -> new Credentials(map(rs, row), rs.getString("password_hash")), email).stream().findFirst();
    }

    private Account map(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Set<String> roles = Set.copyOf(jdbc.queryForList(
                "SELECT role FROM identity.account_roles WHERE account_id = ? ORDER BY role", String.class, id));
        return new Account(id, rs.getString("email"), rs.getString("display_name"), roles,
                rs.getString("status"), rs.getTimestamp("created_at").toInstant());
    }
}
