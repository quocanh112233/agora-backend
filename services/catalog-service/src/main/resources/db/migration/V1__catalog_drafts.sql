CREATE TABLE catalog.products (
    id uuid PRIMARY KEY,
    shop_id uuid NOT NULL,
    name text NOT NULL CHECK (char_length(name) BETWEEN 3 AND 160),
    description text CHECK (char_length(description) <= 5000),
    category text NOT NULL CHECK (category IN ('PRODUCE','SEAFOOD')),
    condition text NOT NULL CHECK (condition IN ('FRESH','FROZEN','DRIED','PROCESSED')),
    origin text NOT NULL CHECK (char_length(origin) BETWEEN 1 AND 120),
    producer_name text CHECK (char_length(producer_name) <= 160),
    storage_instructions text NOT NULL CHECK (char_length(storage_instructions) BETWEEN 1 AND 1000),
    status text NOT NULL CHECK (status = 'DRAFT'),
    version bigint NOT NULL CHECK (version BETWEEN 0 AND 9007199254740991),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (id, shop_id)
);
CREATE INDEX products_shop_created_id ON catalog.products (shop_id, created_at DESC, id DESC);
CREATE TABLE catalog.skus (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    code text NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,39}$'),
    label text NOT NULL CHECK (char_length(label) BETWEEN 1 AND 100),
    price_vnd bigint NOT NULL CHECK (price_vnd BETWEEN 1 AND 1000000000),
    net_weight_grams integer NOT NULL CHECK (net_weight_grams BETWEEN 1 AND 50000),
    shipping_weight_grams integer NOT NULL CHECK (shipping_weight_grams BETWEEN net_weight_grams AND 50000),
    length_cm integer NOT NULL CHECK (length_cm BETWEEN 1 AND 200),
    width_cm integer NOT NULL CHECK (width_cm BETWEEN 1 AND 200),
    height_cm integer NOT NULL CHECK (height_cm BETWEEN 1 AND 200),
    sale_unit text NOT NULL CHECK (sale_unit = 'PACKAGE'),
    position integer NOT NULL CHECK (position BETWEEN 0 AND 9),
    FOREIGN KEY (product_id, shop_id) REFERENCES catalog.products(id, shop_id),
    UNIQUE (shop_id, code),
    UNIQUE (product_id, position)
);
CREATE TABLE catalog.audit_entries (
    id uuid PRIMARY KEY,
    actor_account_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    product_id uuid NOT NULL REFERENCES catalog.products(id),
    action text NOT NULL,
    product_version bigint NOT NULL,
    trace_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_product_created ON catalog.audit_entries (product_id, created_at);
CREATE TABLE catalog.idempotency_records (
    actor_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    operation text NOT NULL,
    key uuid NOT NULL,
    request_hash text NOT NULL CHECK (char_length(request_hash) = 64),
    state text NOT NULL CHECK (state IN ('IN_PROGRESS','COMPLETED')),
    http_status integer,
    response_snapshot jsonb,
    resource_id uuid REFERENCES catalog.products(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '168 hours'),
    PRIMARY KEY (actor_id, operation, key),
    CHECK ((state = 'IN_PROGRESS' AND http_status IS NULL AND response_snapshot IS NULL AND resource_id IS NULL)
        OR (state = 'COMPLETED' AND http_status IN (200,201) AND response_snapshot IS NOT NULL AND resource_id IS NOT NULL))
);
CREATE INDEX idempotency_expires ON catalog.idempotency_records (expires_at);
