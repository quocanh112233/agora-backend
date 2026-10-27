ALTER TABLE catalog.inventory_lots ADD CONSTRAINT inventory_lots_reservation_sku UNIQUE(id,sku_id);

CREATE TABLE catalog.reservations (
    checkout_id uuid PRIMARY KEY,
    buyer_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    request_hash text NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    state text NOT NULL CHECK(state IN ('HELD','RELEASED')),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    released_at timestamptz,
    release_reason text,
    CHECK(expires_at = created_at + interval '15 minutes'),
    CHECK((state='HELD' AND released_at IS NULL AND release_reason IS NULL)
       OR (state='RELEASED' AND released_at IS NOT NULL AND released_at >= created_at
           AND release_reason IS NOT NULL AND release_reason IN ('CANCELLED','EXPIRED'))),
    CHECK(release_reason IS DISTINCT FROM 'EXPIRED' OR released_at >= expires_at)
);
CREATE INDEX reservations_due ON catalog.reservations(expires_at,checkout_id) WHERE state='HELD';
CREATE TABLE catalog.reservation_lines (
    checkout_id uuid NOT NULL REFERENCES catalog.reservations(checkout_id),
    product_id uuid NOT NULL REFERENCES catalog.products(id),
    sku_id uuid NOT NULL REFERENCES catalog.skus(id),
    quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 99),
    PRIMARY KEY(checkout_id,sku_id)
);
CREATE TABLE catalog.reservation_allocations (
    checkout_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    lot_id uuid NOT NULL,
    quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 99),
    PRIMARY KEY(checkout_id,sku_id,lot_id),
    FOREIGN KEY(checkout_id,sku_id) REFERENCES catalog.reservation_lines(checkout_id,sku_id),
    FOREIGN KEY(lot_id,sku_id) REFERENCES catalog.inventory_lots(id,sku_id)
);
CREATE TABLE catalog.reservation_audit (
    id uuid PRIMARY KEY,
    checkout_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    lot_id uuid NOT NULL,
    action text NOT NULL CHECK(action IN ('HOLD','RELEASE','EXPIRE')),
    quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 99),
    lot_version bigint NOT NULL CHECK(lot_version BETWEEN 1 AND 9007199254740991),
    created_at timestamptz NOT NULL,
    FOREIGN KEY(checkout_id,sku_id,lot_id) REFERENCES catalog.reservation_allocations(checkout_id,sku_id,lot_id),
    UNIQUE(checkout_id,lot_id,action)
);
