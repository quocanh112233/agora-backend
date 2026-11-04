CREATE TABLE email.deliveries (
    id UUID PRIMARY KEY,
    payload_hash BYTEA NOT NULL CHECK (octet_length(payload_hash) = 32),
    delivered BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX delivery_retention ON email.deliveries(created_at);
