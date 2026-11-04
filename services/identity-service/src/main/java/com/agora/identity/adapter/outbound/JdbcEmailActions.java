package com.agora.identity.adapter.outbound;

import com.agora.identity.application.EmailActionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEmailActions implements EmailActionStore {
    private final JdbcTemplate jdbc;

    public JdbcEmailActions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> find(String email) {
        return jdbc.query(
                        "SELECT id FROM identity.accounts WHERE email=?",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        email)
                .stream()
                .findFirst();
    }

    public Optional<State> lock(UUID id) {
        return jdbc.query(
                        "SELECT *,clock_timestamp() AS db_now FROM identity.accounts WHERE id=? FOR UPDATE",
                        (rs, row) -> new State(
                                id,
                                rs.getString("email"),
                                rs.getString("password_hash"),
                                rs.getString("status"),
                                rs.getString("account_type"),
                                rs.getLong("version"),
                                rs.getTimestamp("email_verified_at") != null,
                                rs.getTimestamp("db_now").toInstant()),
                        id)
                .stream()
                .findFirst();
    }

    public boolean coolingDown(UUID id, String purpose) {
        return jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM identity.email_actions WHERE account_id=? AND purpose=? AND created_at>clock_timestamp()-interval '60 seconds')",
                Boolean.class,
                id,
                purpose);
    }

    public void queue(UUID id, State account, String purpose, byte[] digest, byte[] cipher, String pendingHash, Instant expires) {
        jdbc.update(
                "UPDATE identity.email_actions SET revoked_at=clock_timestamp(),token_cipher=NULL,pending_password_hash=NULL WHERE account_id=? AND purpose=? AND used_at IS NULL AND revoked_at IS NULL",
                account.id(),
                purpose);
        jdbc.update(
                "INSERT INTO identity.email_actions(id,account_id,purpose,account_version,token_hash,token_cipher,pending_password_hash,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                id,
                account.id(),
                purpose,
                account.version(),
                digest,
                cipher,
                pendingHash,
                Timestamp.from(expires));
    }

    public Optional<Ref> locate(byte[] digest) {
        return jdbc.query(
                        "SELECT id,account_id FROM identity.email_actions WHERE token_hash=?",
                        (rs, row) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                        digest)
                .stream()
                .findFirst();
    }

    public Action action(UUID id) {
        return jdbc.queryForObject(
                "SELECT *,used_at IS NULL AND revoked_at IS NULL AND expires_at>clock_timestamp() AS usable FROM identity.email_actions WHERE id=? FOR UPDATE",
                (rs, row) -> new Action(
                        id,
                        rs.getObject("account_id", UUID.class),
                        rs.getString("purpose"),
                        rs.getLong("account_version"),
                        rs.getString("pending_password_hash"),
                        rs.getString("pending_email"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("usable")),
                id);
    }

    public void targetEmail(UUID action,String email) { jdbc.update("UPDATE identity.email_actions SET pending_email=? WHERE id=?",email,action); }
    public void applyEmail(State account,UUID action,String email) {
        jdbc.update("UPDATE identity.accounts SET email=?,email_verified_at=clock_timestamp() WHERE id=?",email,account.id());
        apply(account,action,null);
    }
    public void apply(State account, UUID action, String passwordHash) {
        int changed = jdbc.update(
                "UPDATE identity.accounts SET password_hash=COALESCE(?,password_hash),email_verified_at=COALESCE(email_verified_at,clock_timestamp()),version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=? AND status='ACTIVE'",
                passwordHash,
                account.id(),
                account.version());
        if (changed != 1)
            throw new IllegalStateException("Email action lost account version.");
        jdbc.update("DELETE FROM identity.spring_session WHERE principal_name=?", account.id().toString());
        jdbc.update(
                "UPDATE identity.email_actions SET revoked_at=clock_timestamp(),token_cipher=NULL,pending_password_hash=NULL WHERE account_id=? AND revoked_at IS NULL",
                account.id());
        jdbc.update("UPDATE identity.email_actions SET used_at=clock_timestamp() WHERE id=?", action);
    }

    public Optional<Delivery> pending() {
        return jdbc.query(
                        """
                        SELECT e.id,COALESCE(e.pending_email,a.email),e.purpose,e.token_cipher,e.expires_at FROM identity.email_actions e JOIN identity.accounts a ON a.id=e.account_id
                        WHERE e.delivered_at IS NULL AND e.used_at IS NULL AND e.revoked_at IS NULL AND e.token_cipher IS NOT NULL
                        AND e.expires_at>clock_timestamp() AND e.retry_at<=clock_timestamp() AND a.status='ACTIVE' AND a.account_type IS NOT NULL AND a.version=e.account_version
                        ORDER BY e.retry_at,e.id LIMIT 1 FOR UPDATE OF e SKIP LOCKED
                        """,
                        (rs, row) -> new Delivery(
                                rs.getObject(1, UUID.class),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getBytes(4),
                                rs.getTimestamp(5).toInstant()))
                .stream()
                .findFirst();
    }

    public void sent(UUID id) {
        jdbc.update("UPDATE identity.email_actions SET delivered_at=clock_timestamp(),token_cipher=NULL WHERE id=?", id);
    }

    public void retry(UUID id) {
        jdbc.update(
                "UPDATE identity.email_actions SET attempts=LEAST(attempts+1,30),retry_at=clock_timestamp()+make_interval(secs=>LEAST(300,power(2,LEAST(attempts+1,8))::integer)) WHERE id=?",
                id);
    }

    public void cleanup() {
        jdbc.update(
                "UPDATE identity.email_actions SET token_cipher=NULL,pending_password_hash=NULL WHERE expires_at<=clock_timestamp() AND (token_cipher IS NOT NULL OR pending_password_hash IS NOT NULL)");
        jdbc.update("DELETE FROM identity.email_actions WHERE expires_at<clock_timestamp()-interval '7 days'");
    }
}
