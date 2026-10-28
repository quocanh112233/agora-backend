CREATE TABLE payment.query_wakeups (
    attempt_id uuid PRIMARY KEY REFERENCES payment.attempts(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO payment.query_wakeups(attempt_id)
SELECT a.id FROM payment.attempts a WHERE a.needs_reconciliation
   OR EXISTS(SELECT 1 FROM payment.captures c WHERE c.attempt_id=a.id AND c.needs_reconciliation);
