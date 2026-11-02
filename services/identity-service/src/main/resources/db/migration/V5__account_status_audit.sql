CREATE TABLE identity.account_status_audit (
    id UUID PRIMARY KEY,
    target_id UUID NOT NULL REFERENCES identity.accounts(id),
    actor_id UUID NOT NULL REFERENCES identity.accounts(id),
    actor_display_name VARCHAR(100) NOT NULL,
    previous_status VARCHAR(20) NOT NULL CHECK (previous_status IN ('ACTIVE', 'SUSPENDED')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    reason TEXT NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 10 AND 1000),
    version_after BIGINT NOT NULL CHECK (version_after BETWEEN 1 AND 9007199254740991),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (previous_status <> status),
    CHECK (actor_id <> target_id),
    UNIQUE (target_id, version_after)
);
CREATE INDEX account_status_history_page ON identity.account_status_audit(target_id, created_at DESC, id DESC);
