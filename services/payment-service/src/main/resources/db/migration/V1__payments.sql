CREATE TABLE payment.attempts (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    order_id uuid NOT NULL,
    request_key uuid NOT NULL,
    checkout_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    txn_ref text NOT NULL UNIQUE CHECK(txn_ref ~ '^[0-9a-f]{32}$'),
    amount_vnd bigint NOT NULL CHECK(amount_vnd BETWEEN 5000 AND 9999999999),
    currency text NOT NULL CHECK(currency='VND'),
    state text NOT NULL CHECK(state IN ('PENDING','FAILED','EXPIRED','SUCCEEDED')),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    ip_address text NOT NULL,
    redirect_url text NOT NULL,
    needs_reconciliation boolean NOT NULL DEFAULT false,
    UNIQUE(actor_id,request_key)
);
-- A late success from a previously failed attempt must not collide with a newer pending attempt.
CREATE UNIQUE INDEX attempts_one_unresolved ON payment.attempts(order_id) WHERE state IN ('PENDING','EXPIRED');

CREATE TABLE payment.captures (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL REFERENCES payment.attempts(id),
    actor_id uuid NOT NULL,
    order_id uuid NOT NULL,
    merchant text NOT NULL,
    provider_transaction_no text NOT NULL CHECK(provider_transaction_no ~ '^[1-9][0-9]{0,14}$'),
    amount_vnd bigint NOT NULL CHECK(amount_vnd BETWEEN 5000 AND 9999999999),
    currency text NOT NULL CHECK(currency='VND'),
    application_state text NOT NULL DEFAULT 'PROCESSING' CHECK(application_state IN ('PROCESSING','APPLIED','REFUND_REQUIRED')),
    needs_reconciliation boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(merchant,provider_transaction_no),
    UNIQUE(id,amount_vnd)
);
CREATE TABLE payment.ledger_entries (
    capture_id uuid NOT NULL REFERENCES payment.captures(id),
    kind text NOT NULL CHECK(kind IN ('CAPTURE','REFUND_RESERVED','REFUND_SUCCEEDED')),
    amount_vnd bigint NOT NULL CHECK(amount_vnd>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(capture_id,kind)
);
CREATE TABLE payment.capture_jobs (
    capture_id uuid PRIMARY KEY REFERENCES payment.captures(id),
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    lease_until timestamptz NOT NULL DEFAULT '-infinity',
    state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','DONE'))
);
CREATE INDEX capture_jobs_recovery ON payment.capture_jobs(lease_until,capture_id) WHERE state='PENDING';
CREATE TABLE payment.observations (
    attempt_id uuid NOT NULL REFERENCES payment.attempts(id),
    transaction_no text NOT NULL,
    response_code text NOT NULL CHECK(response_code ~ '^[0-9]{2}$'),
    transaction_status text NOT NULL CHECK(transaction_status ~ '^[0-9]{2}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(attempt_id,transaction_no,response_code,transaction_status)
);

CREATE TABLE payment.refunds (
    capture_id uuid PRIMARY KEY,
    amount_vnd bigint NOT NULL,
    request_id uuid NOT NULL UNIQUE,
    state text NOT NULL DEFAULT 'RESERVED' CHECK(state IN ('RESERVED','PENDING','SUCCEEDED','NEEDS_RECONCILIATION')),
    provider_transaction_no text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY(capture_id,amount_vnd) REFERENCES payment.captures(id,amount_vnd)
);

CREATE TABLE payment.provider_queries (
    attempt_id uuid PRIMARY KEY REFERENCES payment.attempts(id),
    request_id uuid,
    requested_at timestamptz,
    next_run_at timestamptz NOT NULL,
    lease_until timestamptz NOT NULL DEFAULT '-infinity',
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    done boolean NOT NULL DEFAULT false
);
CREATE INDEX provider_queries_recovery ON payment.provider_queries(next_run_at,lease_until) WHERE NOT done;
ALTER TABLE payment.refunds ADD COLUMN requested_at timestamptz;
ALTER TABLE payment.refunds ADD COLUMN lease_until timestamptz NOT NULL DEFAULT '-infinity';
ALTER TABLE payment.refunds ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0);
ALTER TABLE payment.refunds ADD COLUMN next_run_at timestamptz NOT NULL DEFAULT '-infinity';
