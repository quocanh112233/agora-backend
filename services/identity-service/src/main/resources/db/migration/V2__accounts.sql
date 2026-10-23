CREATE TABLE identity.accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (email = lower(btrim(email))),
    CHECK (char_length(display_name) BETWEEN 2 AND 100)
);

CREATE TABLE identity.account_roles (
    account_id UUID NOT NULL REFERENCES identity.accounts(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    PRIMARY KEY (account_id, role)
);
