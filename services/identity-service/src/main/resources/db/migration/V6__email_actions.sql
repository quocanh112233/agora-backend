ALTER TABLE identity.accounts ADD COLUMN email_verified_at TIMESTAMPTZ;
-- Existing identities deliberately remain unverified; no blind email-ownership backfill.
CREATE TABLE identity.email_actions (
 id UUID PRIMARY KEY,
 account_id UUID NOT NULL REFERENCES identity.accounts(id) ON DELETE CASCADE,
 purpose TEXT NOT NULL CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD','CHANGE_PASSWORD')),
 account_version BIGINT NOT NULL,
 token_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
 token_cipher BYTEA,
 pending_password_hash VARCHAR(255),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 expires_at TIMESTAMPTZ NOT NULL,
 used_at TIMESTAMPTZ,
 revoked_at TIMESTAMPTZ,
 delivered_at TIMESTAMPTZ,
 attempts INT NOT NULL DEFAULT 0,
 retry_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX email_action_account ON identity.email_actions(account_id,purpose,created_at DESC);
CREATE INDEX email_action_outbox ON identity.email_actions(retry_at) WHERE delivered_at IS NULL AND used_at IS NULL AND revoked_at IS NULL;
CREATE INDEX email_action_retention ON identity.email_actions(expires_at);
