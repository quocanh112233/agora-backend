CREATE TABLE feedback.reviews (
    id uuid PRIMARY KEY,
    buyer_id uuid NOT NULL,
    order_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    product_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    rating integer NOT NULL CHECK(rating BETWEEN 1 AND 5),
    content text NOT NULL CHECK(char_length(content) BETWEEN 1 AND 2000),
    reply text CHECK(char_length(reply) BETWEEN 1 AND 2000),
    hidden boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0 CHECK(version BETWEEN 0 AND 9007199254740991),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(order_id,sku_id)
);
CREATE INDEX reviews_public ON feedback.reviews(product_id,created_at DESC,id DESC) WHERE NOT hidden;
CREATE TABLE feedback.moderation_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id uuid NOT NULL REFERENCES feedback.reviews(id),
    actor_id uuid NOT NULL,
    hidden boolean NOT NULL,
    reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 500),
    review_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(review_id,review_version)
);
