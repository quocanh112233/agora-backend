package com.agora.catalog.adapter.outbound;

import com.agora.catalog.application.ProductStore;
import com.agora.catalog.domain.CatalogFailure;
import com.agora.catalog.domain.Product;
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
public class JdbcProductStore implements ProductStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public JdbcProductStore(JdbcTemplate jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }
    @Override public Optional<Product> owned(UUID shopId, UUID id) {
        return jdbc.query("SELECT * FROM catalog.products WHERE shop_id=? AND id=?", this::product, shopId, id).stream().findFirst();
    }
    @Override public Optional<Product> lockOwned(UUID shopId, UUID id) {
        return jdbc.query("SELECT * FROM catalog.products WHERE shop_id=? AND id=? FOR UPDATE", this::product, shopId, id).stream().findFirst();
    }
    @Override public boolean hasVerifiedImage(UUID shopId, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM catalog.product_images i JOIN catalog.image_uploads u
                    ON u.id=i.upload_id AND u.product_id=i.product_id AND u.shop_id=i.shop_id
                    WHERE i.shop_id=? AND i.product_id=? AND u.state='UPLOADED')
                """, Boolean.class, shopId, id));
    }
    @Override public void changeVisibility(Product product, long expectedVersion) {
        int changed = jdbc.update("""
                UPDATE catalog.products SET status=?,version=?,updated_at=? WHERE id=? AND shop_id=? AND version=?
                """, product.status(), product.version(), Timestamp.from(product.updatedAt()), product.id(), product.shopId(), expectedVersion);
        if (changed != 1) throw new CatalogFailure(409, "STALE_VERSION", "Product version has changed.");
    }
    @Override public Page list(UUID shopId, int page, int pageSize) {
        long total = jdbc.queryForObject("SELECT count(*) FROM catalog.products WHERE shop_id=?", Long.class, shopId);
        var items = jdbc.query("SELECT * FROM catalog.products WHERE shop_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::product, shopId, pageSize, (long) page * pageSize);
        return new Page(items, page, pageSize, total);
    }
    private Product product(ResultSet row, int index) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        var skus = jdbc.query("SELECT * FROM catalog.skus WHERE product_id=? ORDER BY position", (sku, i) ->
                new Product.Sku(sku.getObject("id", UUID.class), sku.getString("code"), sku.getString("label"), sku.getLong("price_vnd"),
                        sku.getInt("net_weight_grams"), sku.getInt("shipping_weight_grams"), sku.getInt("length_cm"),
                        sku.getInt("width_cm"), sku.getInt("height_cm"), sku.getString("sale_unit")), id);
        return new Product(id, row.getObject("shop_id", UUID.class), row.getString("name"), row.getString("description"),
                row.getString("category"), row.getString("condition"), row.getString("origin"), row.getString("producer_name"),
                row.getString("storage_instructions"), skus, row.getString("status"), row.getLong("version"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
    @Override public Optional<Result> claim(Key key) {
        boolean locked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))",
                Boolean.class, key.actor() + ":" + key.operation() + ":" + key.key()));
        if (!locked) throw inProgress();
        jdbc.update("DELETE FROM catalog.idempotency_records WHERE actor_id=? AND operation=? AND key=? AND expires_at <= now()",
                key.actor(), key.operation(), key.key());
        int inserted = jdbc.update("""
                INSERT INTO catalog.idempotency_records(actor_id,shop_id,operation,key,request_hash,state)
                VALUES (?,?,?,?,?,'IN_PROGRESS') ON CONFLICT (actor_id,operation,key) DO NOTHING
                """, key.actor(), key.shopId(), key.operation(), key.key(), key.hash());
        if (inserted == 1) return Optional.empty();
        return jdbc.query("SELECT * FROM catalog.idempotency_records WHERE actor_id=? AND operation=? AND key=?", (row, i) -> {
            if (!key.shopId().equals(row.getObject("shop_id", UUID.class)))
                throw new CatalogFailure(404, "PRODUCT_NOT_FOUND", "Product was not found.");
            if (!key.hash().equals(row.getString("request_hash")))
                throw new CatalogFailure(422, "IDEMPOTENCY_PAYLOAD_MISMATCH", "Key was used for a different request.");
            if (!"COMPLETED".equals(row.getString("state"))) throw inProgress();
            return new Result(row.getInt("http_status"), json.readValue(row.getString("response_snapshot"), Product.class));
        }, key.actor(), key.operation(), key.key()).stream().findFirst();
    }
    private CatalogFailure inProgress() { return new CatalogFailure(409, "IDEMPOTENCY_IN_PROGRESS", "Request is already being processed."); }
    @Override public void insert(Product product) {
        jdbc.update("""
                INSERT INTO catalog.products(id,shop_id,name,description,category,condition,origin,producer_name,storage_instructions,status,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, product.id(), product.shopId(), product.name(), product.description(), product.category(), product.condition(),
                product.origin(), product.producerName(), product.storageInstructions(), product.status(), product.version(),
                Timestamp.from(product.createdAt()), Timestamp.from(product.updatedAt()));
        insertSkus(product);
    }
    @Override public void update(Product product, long expectedVersion) {
        var referenced = jdbc.query("SELECT DISTINCT s.* FROM catalog.skus s JOIN catalog.inventory_lots l ON l.sku_id=s.id WHERE s.product_id=?",
                (r,i) -> new Product.Sku(r.getObject("id",UUID.class),r.getString("code"),r.getString("label"),r.getLong("price_vnd"),r.getInt("net_weight_grams"),r.getInt("shipping_weight_grams"),r.getInt("length_cm"),r.getInt("width_cm"),r.getInt("height_cm"),r.getString("sale_unit")),product.id());
        for(var previous:referenced){
            var changed=product.skus().stream().filter(sku->sku.id().equals(previous.id())).findFirst();
            if(changed.isEmpty() || changed.get().netWeightGrams()!=previous.netWeightGrams() || changed.get().shippingWeightGrams()!=previous.shippingWeightGrams()
                    || changed.get().lengthCm()!=previous.lengthCm() || changed.get().widthCm()!=previous.widthCm() || changed.get().heightCm()!=previous.heightCm()
                    || !changed.get().saleUnit().equals(previous.saleUnit()))
                throw new CatalogFailure(409,"SKU_HAS_INVENTORY","A SKU with lot history cannot be removed or repacked.");
        }
        int changed = jdbc.update("""
                UPDATE catalog.products SET name=?,description=?,category=?,condition=?,origin=?,producer_name=?,storage_instructions=?,version=?,updated_at=?
                WHERE id=? AND shop_id=? AND version=? AND status IN ('DRAFT','HIDDEN')
                """, product.name(), product.description(), product.category(), product.condition(), product.origin(), product.producerName(),
                product.storageInstructions(), product.version(), Timestamp.from(product.updatedAt()), product.id(), product.shopId(), expectedVersion);
        if (changed != 1) throw new CatalogFailure(409, "STALE_VERSION", "Product version has changed.");
        var retained=product.skus().stream().map(Product.Sku::id).toList();
        jdbc.queryForList("SELECT id FROM catalog.skus WHERE product_id=?",UUID.class,product.id()).stream()
                .filter(id->!retained.contains(id)).forEach(id->jdbc.update("DELETE FROM catalog.skus WHERE id=?",id));
        insertSkus(product);
    }
    private void insertSkus(Product product) {
        for (int position = 0; position < product.skus().size(); position++) {
            var sku = product.skus().get(position);
            jdbc.update("""
                    INSERT INTO catalog.skus(id,product_id,shop_id,code,label,price_vnd,net_weight_grams,shipping_weight_grams,length_cm,width_cm,height_cm,sale_unit,position)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET label=EXCLUDED.label,price_vnd=EXCLUDED.price_vnd,
                    net_weight_grams=EXCLUDED.net_weight_grams,shipping_weight_grams=EXCLUDED.shipping_weight_grams,
                    length_cm=EXCLUDED.length_cm,width_cm=EXCLUDED.width_cm,height_cm=EXCLUDED.height_cm,position=EXCLUDED.position
                    """, sku.id(), product.id(), product.shopId(), sku.code(), sku.label(), sku.priceVnd(), sku.netWeightGrams(),
                    sku.shippingWeightGrams(), sku.lengthCm(), sku.widthCm(), sku.heightCm(), sku.saleUnit(), position);
        }
    }
    @Override public void audit(Product product, UUID actor, String action, String traceId) {
        jdbc.update("""
                INSERT INTO catalog.audit_entries(id,actor_account_id,shop_id,product_id,action,product_version,trace_id)
                VALUES (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), actor, product.shopId(), product.id(), action, product.version(), UUID.fromString(traceId));
    }
    @Override public void complete(Key key, Result result) {
        int changed = jdbc.update("""
                UPDATE catalog.idempotency_records SET state='COMPLETED',http_status=?,response_snapshot=?::jsonb,resource_id=?
                WHERE actor_id=? AND shop_id=? AND operation=? AND key=? AND state='IN_PROGRESS'
                """, result.status(), json.writeValueAsString(result.product()), result.product().id(), key.actor(), key.shopId(), key.operation(), key.key());
        if (changed != 1) throw new IllegalStateException("Idempotency claim was lost.");
    }
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void expireResults() { jdbc.update("DELETE FROM catalog.idempotency_records WHERE expires_at <= now()"); }
}
