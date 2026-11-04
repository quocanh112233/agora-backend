ALTER TABLE identity.accounts ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0 CHECK (profile_version BETWEEN 0 AND 9007199254740991);
ALTER TABLE identity.email_actions DROP CONSTRAINT email_actions_purpose_check;
ALTER TABLE identity.email_actions ADD CONSTRAINT email_actions_purpose_check CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD','CHANGE_PASSWORD','CHANGE_EMAIL'));
ALTER TABLE identity.email_actions ADD COLUMN pending_email VARCHAR(254);
CREATE TABLE identity.avatar_uploads (
 id UUID PRIMARY KEY,
 account_id UUID NOT NULL REFERENCES identity.accounts(id),
 request_hash CHAR(64) NOT NULL,
 expected_version BIGINT NOT NULL,
 public_id TEXT NOT NULL UNIQUE,
 mime VARCHAR(16) NOT NULL CHECK (mime IN ('image/png','image/jpeg')),
 bytes BIGINT NOT NULL CHECK (bytes BETWEEN 1 AND 5242880),
 width INT NOT NULL CHECK (width>0), height INT NOT NULL CHECK (height>0),
 url TEXT,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','UPLOADING','ATTACHED','DELETING','DELETED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 retry_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 expires_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()+interval '1 hour',
 UNIQUE(id,account_id),
 CHECK (width::bigint*height<=20000000),
 CHECK (public_id='agora/avatars/'||account_id::text||'/'||id::text),
 CHECK (url IS NULL OR url ~ ('^https://res[.]cloudinary[.]com/[A-Za-z0-9_-]+/image/upload/v[1-9][0-9]*/'||public_id||'[.](jpg|png)$'))
);
ALTER TABLE identity.accounts ADD COLUMN avatar_upload_id UUID;
ALTER TABLE identity.accounts ADD CONSTRAINT own_avatar FOREIGN KEY(avatar_upload_id,id) REFERENCES identity.avatar_uploads(id,account_id);
CREATE INDEX avatar_cleanup ON identity.avatar_uploads(expires_at) WHERE state IN ('PENDING','UPLOADING','DELETING');
CREATE INDEX avatar_owner ON identity.avatar_uploads(account_id,created_at);
CREATE INDEX avatar_reconcile ON identity.avatar_uploads(retry_at) WHERE state='DELETED';
