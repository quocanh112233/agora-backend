CREATE TABLE merchant.shops (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL UNIQUE,
    name text NOT NULL CHECK (char_length(name) BETWEEN 3 AND 120),
    description text CHECK (char_length(description) <= 2000),
    contact_phone text NOT NULL CHECK (char_length(contact_phone) BETWEEN 9 AND 20),
    pickup_address jsonb NOT NULL CHECK (jsonb_typeof(pickup_address) = 'object'),
    status text NOT NULL CHECK (status IN ('DRAFT','PENDING_REVIEW','ACTIVE','REJECTED','SUSPENDED')),
    version bigint NOT NULL CHECK (version >= 0),
    last_review_reason text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX shops_status_created_id ON merchant.shops (status, created_at DESC, id DESC);

CREATE TABLE merchant.audit_entries (
    id uuid PRIMARY KEY,
    actor_account_id uuid NOT NULL,
    shop_id uuid NOT NULL REFERENCES merchant.shops(id),
    action text NOT NULL,
    previous_status text,
    next_status text NOT NULL,
    reason text,
    trace_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_shop_created ON merchant.audit_entries (shop_id, created_at);

CREATE TABLE merchant.idempotency_records (
    actor_id uuid NOT NULL,
    operation text NOT NULL,
    key uuid NOT NULL,
    request_hash text NOT NULL CHECK (char_length(request_hash) = 64),
    state text NOT NULL CHECK (state IN ('IN_PROGRESS','COMPLETED')),
    http_status integer,
    response_snapshot jsonb,
    resource_id uuid REFERENCES merchant.shops(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '168 hours'),
    PRIMARY KEY (actor_id, operation, key),
    CHECK ((state = 'IN_PROGRESS' AND http_status IS NULL AND response_snapshot IS NULL AND resource_id IS NULL)
        OR (state = 'COMPLETED' AND http_status IN (200,201) AND response_snapshot IS NOT NULL AND resource_id IS NOT NULL))
);
CREATE INDEX idempotency_expires ON merchant.idempotency_records (expires_at);
