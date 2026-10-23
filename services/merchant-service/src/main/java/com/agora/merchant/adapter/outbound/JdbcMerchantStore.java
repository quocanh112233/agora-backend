package com.agora.merchant.adapter.outbound;

import com.agora.merchant.application.MerchantStore;
import com.agora.merchant.domain.MerchantFailure;
import com.agora.merchant.domain.Shop;
import com.agora.merchant.domain.Shop.Address;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcMerchantStore implements MerchantStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public JdbcMerchantStore(JdbcTemplate jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override public Optional<Shop> owned(UUID owner, UUID shopId) {
        return (shopId == null
                ? jdbc.query("SELECT * FROM merchant.shops WHERE owner_account_id=?", this::shop, owner)
                : jdbc.query("SELECT * FROM merchant.shops WHERE owner_account_id=? AND id=?", this::shop, owner, shopId))
                .stream().findFirst();
    }
    @Override public Optional<Shop> find(UUID shopId) {
        return jdbc.query("SELECT * FROM merchant.shops WHERE id=?", this::shop, shopId).stream().findFirst();
    }
    @Override public Page list(String status, int page, int pageSize) {
        String filter = status == null ? "" : " WHERE status=?";
        Object[] countArgs = status == null ? new Object[]{} : new Object[]{status};
        Object[] pageArgs = status == null ? new Object[]{pageSize, (long) page * pageSize}
                : new Object[]{status, pageSize, (long) page * pageSize};
        long total = jdbc.queryForObject("SELECT count(*) FROM merchant.shops" + filter, Long.class, countArgs);
        var items = jdbc.query("SELECT * FROM merchant.shops" + filter
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", this::shop, pageArgs);
        return new Page(items, page, pageSize, total);
    }
    private Shop shop(ResultSet row, int index) throws SQLException {
        return new Shop(row.getObject("id", UUID.class), row.getObject("owner_account_id", UUID.class),
                row.getString("name"), row.getString("description"), row.getString("contact_phone"),
                json.readValue(row.getString("pickup_address"), Address.class), row.getString("status"), row.getLong("version"),
                row.getString("last_review_reason"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    @Override public Optional<Result> claim(Key key) {
        // The unique row is the durable claim; the transaction lock makes a live duplicate return immediately.
        boolean locked = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))", Boolean.class,
                key.actor() + ":" + key.operation() + ":" + key.key()));
        if (!locked) throw inProgress();
        jdbc.update("DELETE FROM merchant.idempotency_records WHERE actor_id=? AND operation=? AND key=? AND expires_at <= now()",
                key.actor(), key.operation(), key.key());
        int inserted = jdbc.update("""
                INSERT INTO merchant.idempotency_records(actor_id,operation,key,request_hash,state)
                VALUES (?,?,?,?,'IN_PROGRESS') ON CONFLICT (actor_id,operation,key) DO NOTHING
                """, key.actor(), key.operation(), key.key(), key.hash());
        if (inserted == 1) return Optional.empty();
        return jdbc.query("SELECT * FROM merchant.idempotency_records WHERE actor_id=? AND operation=? AND key=?",
                (row, index) -> {
                    if (!key.hash().equals(row.getString("request_hash")))
                        throw new MerchantFailure(422, "IDEMPOTENCY_PAYLOAD_MISMATCH", "Key was used for a different request.");
                    if (!"COMPLETED".equals(row.getString("state"))) throw inProgress();
                    return new Result(row.getInt("http_status"), json.readValue(row.getString("response_snapshot"), Shop.class));
                }, key.actor(), key.operation(), key.key()).stream().findFirst();
    }
    private MerchantFailure inProgress() {
        return new MerchantFailure(409, "IDEMPOTENCY_IN_PROGRESS", "Request is already being processed.");
    }

    @Override public void insert(Shop shop) {
        jdbc.update("""
                INSERT INTO merchant.shops(id,owner_account_id,name,description,contact_phone,pickup_address,status,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?::jsonb,?,?,?,?)
                """, shop.id(), shop.ownerAccountId(), shop.name(), shop.description(), shop.contactPhone(),
                json.writeValueAsString(shop.pickupAddress()), shop.status(), shop.version(),
                Timestamp.from(shop.createdAt()), Timestamp.from(shop.updatedAt()));
    }
    @Override public void update(Shop shop, long expectedVersion, String previousStatus) {
        int changed = jdbc.update("""
                UPDATE merchant.shops SET name=?,description=?,contact_phone=?,pickup_address=?::jsonb,status=?,last_review_reason=?,version=?,updated_at=?
                WHERE id=? AND owner_account_id=? AND version=? AND status=?
                """, shop.name(), shop.description(), shop.contactPhone(), json.writeValueAsString(shop.pickupAddress()),
                shop.status(), shop.lastReviewReason(), shop.version(), Timestamp.from(shop.updatedAt()),
                shop.id(), shop.ownerAccountId(), expectedVersion, previousStatus);
        if (changed != 1) throw new MerchantFailure(409, "STALE_VERSION", "Shop has changed.");
    }
    @Override public void review(Shop shop, UUID actor, Shop.Review review) {
        jdbc.update("""
                INSERT INTO merchant.shop_reviews(id,shop_id,actor_account_id,decision,reason,reviewed_version)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), shop.id(), actor, review.decision(), review.reason(), review.expectedVersion());
    }
    @Override public void audit(Shop shop, UUID actor, String action, String previousStatus, String reason, String traceId) {
        jdbc.update("""
                INSERT INTO merchant.audit_entries(id,actor_account_id,shop_id,action,previous_status,next_status,reason,trace_id)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), actor, shop.id(), action, previousStatus, shop.status(), reason, UUID.fromString(traceId));
    }
    @Override public void complete(Key key, Result result) {
        int changed = jdbc.update("""
                UPDATE merchant.idempotency_records SET state='COMPLETED',http_status=?,response_snapshot=?::jsonb,resource_id=?
                WHERE actor_id=? AND operation=? AND key=? AND state='IN_PROGRESS'
                """, result.status(), json.writeValueAsString(result.shop()), result.shop().id(), key.actor(), key.operation(), key.key());
        if (changed != 1) throw new IllegalStateException("Idempotency claim was lost.");
    }
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void expireResults() { jdbc.update("DELETE FROM merchant.idempotency_records WHERE expires_at <= now()"); }
}
