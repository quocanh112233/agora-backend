CREATE TABLE catalog.checkout_decisions (
    checkout_id uuid PRIMARY KEY,
    buyer_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    request_hash text NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    closure_reason text NOT NULL CHECK(closure_reason IN ('OFFER_UNAVAILABLE','CHECKOUT_EXPIRED')),
    closed_at timestamptz NOT NULL
);
-- Raw M3b reservations retain their original time semantics; accepted checkouts use DB time throughout.
ALTER TABLE catalog.reservations ADD COLUMN accepted_not_after timestamptz;
