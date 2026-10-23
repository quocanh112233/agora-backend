CREATE TABLE merchant.shop_reviews (
    id uuid PRIMARY KEY,
    shop_id uuid NOT NULL REFERENCES merchant.shops(id),
    actor_account_id uuid NOT NULL,
    decision text NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    reason text CHECK (char_length(reason) BETWEEN 10 AND 1000),
    reviewed_version bigint NOT NULL CHECK (reviewed_version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (shop_id, reviewed_version),
    CHECK (decision <> 'REJECT' OR reason IS NOT NULL)
);
CREATE INDEX shops_created_id ON merchant.shops (created_at DESC, id DESC);
