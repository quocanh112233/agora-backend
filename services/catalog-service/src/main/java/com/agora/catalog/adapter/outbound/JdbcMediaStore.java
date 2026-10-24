package com.agora.catalog.adapter.outbound;

import com.agora.catalog.application.ImageStorage;
import com.agora.catalog.application.MediaStore;
import com.agora.catalog.domain.CatalogFailure;
import com.agora.catalog.domain.ProductImage;
import com.agora.catalog.domain.ProductImage.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcMediaStore implements MediaStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public JdbcMediaStore(JdbcTemplate jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }

    private long product(UUID shop, UUID id, boolean lock) {
        return jdbc.query("SELECT version,status FROM catalog.products WHERE shop_id=? AND id=?" + (lock ? " FOR UPDATE" : ""), (r, i) -> {
            if (!Set.of("DRAFT", "HIDDEN").contains(r.getString("status")))
                throw failure(409, "PRODUCT_NOT_DRAFT", "Hide a published product before managing its images.");
            return r.getLong("version");
        }, shop, id).stream().findFirst().orElseThrow(JdbcMediaStore::notFound);
    }
    private Images images(UUID shop, UUID id, long version) {
        return new Images(id, version, jdbc.query("""
                SELECT u.* FROM catalog.product_images p JOIN catalog.image_uploads u ON u.id=p.upload_id
                WHERE p.product_id=? AND p.shop_id=? ORDER BY p.position
                """, (r, i) -> new ProductImage(r.getObject("id", UUID.class), r.getString("url"), r.getInt("width"),
                r.getInt("height"), r.getLong("bytes"), r.getString("format")), id, shop));
    }
    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Images list(UUID shop, UUID id) { return images(shop, id, product(shop, id, false)); }

    @Override @Transactional
    public Reservation reserve(Key key, String mime, long bytes, int width, int height) {
        // One actor can reserve against multiple products; the actor lock makes the quota global.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "media-actor:" + key.actor());
        product(key.shop(), key.product(), true);
        var existing = jdbc.query("SELECT * FROM catalog.image_uploads WHERE actor_id=? AND key=? FOR UPDATE",
                (r, i) -> {
                    matching(key, r);
                    return intent(r, i);
                }, key.actor(), key.key());
        if (!existing.isEmpty()) {
            Intent intent = existing.getFirst();
            usable(intent);
            return new Reservation(intent, false);
        }
        long count = jdbc.queryForObject("""
                SELECT count(*) FROM catalog.image_uploads u WHERE actor_id=? AND state<>'DELETED'
                AND NOT EXISTS (SELECT 1 FROM catalog.product_images p WHERE p.upload_id=u.id)
                """, Long.class, key.actor());
        if (count >= 20) throw failure(409, "UPLOAD_QUOTA_EXCEEDED", "At most 20 unattached uploads are allowed.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog.image_uploads(id,actor_id,shop_id,product_id,key,request_hash,public_id,state,mime,bytes,width,height)
                VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?,?)
                """, id, key.actor(), key.shop(), key.product(), key.key(), key.hash(), "agora/products/" + key.product() + "/" + id,
                mime, bytes, width, height);
        return new Reservation(locked(key, id), true);
    }
    private Intent intent(ResultSet r, int i) throws SQLException {
        return new Intent(r.getObject("id", UUID.class), r.getObject("actor_id", UUID.class), r.getObject("shop_id", UUID.class),
                r.getObject("product_id", UUID.class), r.getString("public_id"), r.getString("state"), r.getString("mime"),
                r.getLong("bytes"), r.getInt("width"), r.getInt("height"), r.getTimestamp("expires_at").toInstant());
    }
    private Intent locked(Key key, UUID id) {
        return jdbc.query("SELECT * FROM catalog.image_uploads WHERE id=? AND actor_id=? AND shop_id=? AND product_id=? FOR UPDATE",
                this::intent, id, key.actor(), key.shop(), key.product()).stream().findFirst().orElseThrow(JdbcMediaStore::notFound);
    }
    private void usable(Intent intent) {
        boolean expired = Boolean.TRUE.equals(jdbc.queryForObject("SELECT expires_at<=now() FROM catalog.image_uploads WHERE id=? AND product_id=? AND shop_id=?",
                Boolean.class, intent.id(), intent.product(), intent.shop()));
        if (expired || !Set.of("PENDING", "UPLOADED").contains(intent.state()))
            throw failure(410, "UPLOAD_EXPIRED", "Upload is no longer available.");
    }
    @Override @Transactional
    public Upload uploaded(Key key, UUID id, ImageStorage.Stored stored) {
        product(key.shop(), key.product(), true);
        Intent intent = locked(key, id);
        usable(intent);
        if ("PENDING".equals(intent.state())) jdbc.update("""
                UPDATE catalog.image_uploads SET state='UPLOADED',provider_version=?,format=?,url=?
                WHERE id=? AND actor_id=? AND shop_id=? AND product_id=? AND state='PENDING'
                """, stored.version(), stored.format(), stored.url(), id, key.actor(), key.shop(), key.product());
        return intent.response();
    }
    private void matching(Key key, ResultSet row) throws SQLException {
        if (!key.shop().equals(row.getObject("shop_id", UUID.class))) throw notFound();
        if (!key.product().equals(row.getObject("product_id", UUID.class)) || !key.hash().equals(row.getString("request_hash")))
            throw failure(422, "IDEMPOTENCY_PAYLOAD_MISMATCH", "Key was used for a different request.");
    }

    @Override @Transactional
    public Images mutate(Key key, UUID image, long expectedVersion, boolean attach, String traceId) {
        boolean claimed = Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",
                Boolean.class, "media-key:" + key.actor() + ":" + key.key()));
        if (!claimed) throw failure(409, "IDEMPOTENCY_IN_PROGRESS", "Request is already being processed.");
        long version = product(key.shop(), key.product(), true);
        String operation = attach ? "ATTACH_IMAGE" : "DETACH_IMAGE";
        jdbc.update("DELETE FROM catalog.media_requests WHERE actor_id=? AND key=? AND expires_at<=now()", key.actor(), key.key());
        var replay = jdbc.query("SELECT * FROM catalog.media_requests WHERE actor_id=? AND key=?", (r, i) -> {
            matching(key, r);
            if (!operation.equals(r.getString("operation")))
                throw failure(422, "IDEMPOTENCY_PAYLOAD_MISMATCH", "Key was used for a different request.");
            return json.readValue(r.getString("response_snapshot"), Images.class);
        }, key.actor(), key.key());
        if (!replay.isEmpty()) return replay.getFirst();
        if (version != expectedVersion || version == 9007199254740991L)
            throw failure(409, "STALE_VERSION", "Product version has changed.");
        Intent intent = locked(key, image);
        if (attach) {
            usable(intent);
            if (!"UPLOADED".equals(intent.state())) throw failure(503, "MEDIA_UNAVAILABLE", "Upload is not yet verified.");
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM catalog.product_images WHERE upload_id=?)", Boolean.class, image)))
                throw failure(409, "IMAGE_ALREADY_ATTACHED", "Image is already attached.");
            List<Integer> positions = jdbc.query("""
                    SELECT n FROM generate_series(0,4) n WHERE NOT EXISTS
                    (SELECT 1 FROM catalog.product_images WHERE product_id=? AND shop_id=? AND position=n) ORDER BY n LIMIT 1
                    """, (r, i) -> r.getInt(1), key.product(), key.shop());
            if (positions.isEmpty()) throw failure(409, "IMAGE_LIMIT_EXCEEDED", "At most five images may be attached.");
            jdbc.update("INSERT INTO catalog.product_images(upload_id,product_id,shop_id,position) VALUES (?,?,?,?)",
                    image, key.product(), key.shop(), positions.getFirst());
        } else {
            int changed = jdbc.update("DELETE FROM catalog.product_images WHERE upload_id=? AND product_id=? AND shop_id=?", image, key.product(), key.shop());
            if (changed != 1) throw notFound();
            jdbc.update("UPDATE catalog.image_uploads SET expires_at=now() WHERE id=? AND product_id=? AND shop_id=?", image, key.product(), key.shop());
        }
        jdbc.update("UPDATE catalog.products SET version=version+1,updated_at=now() WHERE id=? AND shop_id=?", key.product(), key.shop());
        jdbc.update("""
                INSERT INTO catalog.audit_entries(id,actor_account_id,shop_id,product_id,action,product_version,trace_id)
                VALUES (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), key.actor(), key.shop(), key.product(), operation, version + 1, UUID.fromString(traceId));
        Images response = images(key.shop(), key.product(), version + 1);
        jdbc.update("""
                INSERT INTO catalog.media_requests(actor_id,key,shop_id,product_id,operation,request_hash,response_snapshot)
                VALUES (?,?,?,?,?,?,?::jsonb)
                """, key.actor(), key.key(), key.shop(), key.product(), operation, key.hash(), json.writeValueAsString(response));
        return response;
    }

    @Override @Transactional
    public List<Intent> claimCleanup() {
        jdbc.update("DELETE FROM catalog.media_requests WHERE expires_at<=now()");
        var claims = jdbc.query("""
                SELECT u.* FROM catalog.image_uploads u WHERE u.state<>'DELETED' AND u.expires_at<=now()
                AND NOT EXISTS (SELECT 1 FROM catalog.product_images p WHERE p.upload_id=u.id)
                ORDER BY u.expires_at,u.id LIMIT 20 FOR UPDATE OF u SKIP LOCKED
                """, this::intent);
        var claimed = new ArrayList<Intent>();
        // Recheck in a fresh statement snapshot after acquiring the upload lock.
        for (Intent intent : claims) if (jdbc.update("""
                UPDATE catalog.image_uploads u SET state='DELETE_PENDING'
                WHERE id=? AND product_id=? AND shop_id=? AND state<>'DELETED' AND expires_at<=now()
                AND NOT EXISTS (SELECT 1 FROM catalog.product_images p WHERE p.upload_id=u.id)
                """, intent.id(), intent.product(), intent.shop()) == 1) claimed.add(intent);
        return claimed;
    }
    @Override @Transactional
    public void deleted(UUID id) {
        jdbc.update("""
                UPDATE catalog.image_uploads u SET state='DELETED' WHERE id=? AND state='DELETE_PENDING'
                AND NOT EXISTS (SELECT 1 FROM catalog.product_images p WHERE p.upload_id=u.id)
                """, id);
    }
    private static CatalogFailure notFound() { return failure(404, "PRODUCT_NOT_FOUND", "Product or image was not found."); }
    private static CatalogFailure failure(int status, String code, String message) { return new CatalogFailure(status, code, message); }
}
