ALTER TABLE catalog.skus ADD CONSTRAINT skus_inventory_identity UNIQUE(id,product_id,shop_id);
ALTER TABLE catalog.skus DROP CONSTRAINT skus_product_id_position_key;
ALTER TABLE catalog.skus ADD CONSTRAINT skus_product_id_position_key UNIQUE(product_id,position) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE catalog.inventory_lots (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    code text NOT NULL CHECK(code ~ '^[A-Z0-9][A-Z0-9_-]{0,39}$'),
    produced_on date,
    expires_on date NOT NULL,
    on_hand bigint NOT NULL CHECK(on_hand BETWEEN 0 AND 1000000),
    held bigint NOT NULL DEFAULT 0 CHECK(held BETWEEN 0 AND 1000000),
    allocated bigint NOT NULL DEFAULT 0 CHECK(allocated BETWEEN 0 AND 1000000),
    state text NOT NULL CHECK(state IN ('ACTIVE','BLOCKED')),
    version bigint NOT NULL CHECK(version BETWEEN 0 AND 9007199254740991),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK(held+allocated <= on_hand),
    CHECK(produced_on IS NULL OR produced_on <= expires_on),
    UNIQUE(sku_id,code),
    UNIQUE(id,product_id,shop_id),
    FOREIGN KEY(sku_id,product_id,shop_id) REFERENCES catalog.skus(id,product_id,shop_id)
);
CREATE INDEX inventory_lots_product_expiry ON catalog.inventory_lots(product_id,expires_on,id);

CREATE TABLE catalog.inventory_requests (
    actor_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    product_id uuid NOT NULL,
    operation text NOT NULL CHECK(operation IN ('CREATE_LOT','CHANGE_LOT_STATUS')),
    key uuid NOT NULL,
    request_hash text NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    state text NOT NULL CHECK(state IN ('IN_PROGRESS','COMPLETED')),
    http_status integer,
    response_snapshot jsonb,
    lot_id uuid REFERENCES catalog.inventory_lots(id),
    expires_at timestamptz NOT NULL DEFAULT(now()+interval '7 days'),
    PRIMARY KEY(actor_id,operation,key),
    CHECK((state='IN_PROGRESS' AND http_status IS NULL AND response_snapshot IS NULL AND lot_id IS NULL)
       OR (state='COMPLETED' AND http_status IN(200,201) AND response_snapshot IS NOT NULL AND lot_id IS NOT NULL))
);
CREATE INDEX inventory_requests_expiry ON catalog.inventory_requests(expires_at);

CREATE TABLE catalog.inventory_audit (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    lot_id uuid NOT NULL,
    product_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    action text NOT NULL CHECK(action IN('CREATE_LOT','BLOCK_LOT','OPEN_LOT')),
    quantity_delta bigint NOT NULL,
    lot_version bigint NOT NULL,
    trace_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(lot_id,product_id,shop_id) REFERENCES catalog.inventory_lots(id,product_id,shop_id),
    CHECK((action='CREATE_LOT' AND quantity_delta BETWEEN 1 AND 1000000) OR (action<>'CREATE_LOT' AND quantity_delta=0))
);
CREATE INDEX inventory_audit_lot ON catalog.inventory_audit(lot_id,created_at);
