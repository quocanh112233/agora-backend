ALTER TABLE commerce.orders ADD COLUMN state text NOT NULL DEFAULT 'AWAITING_PAYMENT'
    CHECK(state IN ('AWAITING_PAYMENT','PAYMENT_PROCESSING','PAID','EXPIRED'));
ALTER TABLE commerce.orders ADD COLUMN winner_capture_id uuid UNIQUE;
ALTER TABLE commerce.orders ADD COLUMN reconciliation_after timestamptz NOT NULL DEFAULT '-infinity';
CREATE TABLE commerce.capture_applications (
    capture_id uuid PRIMARY KEY,
    request_hash text NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    buyer_id uuid NOT NULL,
    order_id uuid NOT NULL REFERENCES commerce.orders(id),
    attempt_id uuid NOT NULL,
    provider_transaction_no text NOT NULL CHECK(provider_transaction_no ~ '^[0-9]{1,15}$' AND provider_transaction_no !~ '^0+$'),
    amount_vnd bigint NOT NULL CHECK(amount_vnd BETWEEN 1 AND 9999999999),
    currency text NOT NULL CHECK(currency='VND'),
    state text NOT NULL CHECK(state IN ('PROCESSING','APPLIED','REFUND_REQUIRED')),
    reason text CHECK(reason IN ('ORDER_EXPIRED','DUPLICATE_CAPTURE','AMOUNT_MISMATCH','RESERVATION_RELEASED')),
    needs_reconciliation boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    lease_until timestamptz NOT NULL DEFAULT '-infinity',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(order_id,capture_id),
    CHECK((state='REFUND_REQUIRED')=(reason IS NOT NULL))
);
ALTER TABLE commerce.orders ADD FOREIGN KEY(id,winner_capture_id) REFERENCES commerce.capture_applications(order_id,capture_id);
ALTER TABLE commerce.orders ADD CHECK(state NOT IN ('PAYMENT_PROCESSING','PAID') OR winner_capture_id IS NOT NULL);
CREATE INDEX capture_applications_recovery ON commerce.capture_applications(lease_until,capture_id) WHERE state='PROCESSING';
CREATE INDEX orders_payment_recovery ON commerce.orders(reconciliation_after,expires_at,id) WHERE state IN ('AWAITING_PAYMENT','PAYMENT_PROCESSING');
