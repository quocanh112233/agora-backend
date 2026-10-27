CREATE TABLE commerce.checkout_previews (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    request_key uuid NOT NULL,
    request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    snapshot jsonb NOT NULL,
    expires_at timestamptz NOT NULL,
    UNIQUE(actor_id,request_key),
    UNIQUE(actor_id,id)
);
CREATE TABLE commerce.checkout_attempts (
    actor_id uuid NOT NULL,
    request_key uuid NOT NULL,
    preview_id uuid NOT NULL UNIQUE,
    checkout_id uuid NOT NULL UNIQUE,
    state text NOT NULL DEFAULT 'PROCESSING' CHECK(state IN ('PROCESSING','ORDER_CREATED','FAILED')),
    order_id uuid UNIQUE,
    failure_code text CHECK(failure_code IN ('OFFER_UNAVAILABLE','CHECKOUT_EXPIRED','RESERVATION_RELEASED')),
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    lease_until timestamptz NOT NULL DEFAULT '-infinity',
    PRIMARY KEY(actor_id,request_key),
    FOREIGN KEY(actor_id,preview_id) REFERENCES commerce.checkout_previews(actor_id,id),
    CHECK((state='PROCESSING' AND order_id IS NULL AND failure_code IS NULL) OR (state='ORDER_CREATED' AND order_id IS NOT NULL AND failure_code IS NULL) OR (state='FAILED' AND order_id IS NULL AND failure_code IS NOT NULL))
);
CREATE INDEX checkout_attempts_recovery ON commerce.checkout_attempts(lease_until) WHERE state='PROCESSING';
CREATE TABLE commerce.orders (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    checkout_id uuid NOT NULL UNIQUE REFERENCES commerce.checkout_attempts(checkout_id),
    snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    cart_cleared boolean NOT NULL
);
ALTER TABLE commerce.checkout_attempts ADD FOREIGN KEY(order_id) REFERENCES commerce.orders(id);
CREATE INDEX orders_actor_created ON commerce.orders(actor_id,created_at DESC,id DESC);
