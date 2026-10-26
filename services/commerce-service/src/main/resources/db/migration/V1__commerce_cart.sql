CREATE TABLE commerce.carts (
    account_id uuid PRIMARY KEY,
    version bigint NOT NULL CHECK (version BETWEEN 0 AND 9007199254740991)
);
CREATE TABLE commerce.cart_items (
    account_id uuid NOT NULL REFERENCES commerce.carts(account_id),
    sku_id uuid NOT NULL,
    product_id uuid NOT NULL,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    PRIMARY KEY (account_id,sku_id)
);
CREATE TABLE commerce.cart_requests (
    actor_id uuid NOT NULL REFERENCES commerce.carts(account_id),
    key uuid NOT NULL,
    operation text NOT NULL CHECK (operation IN ('PUT_ITEM','DELETE_ITEM')),
    request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    response_version bigint NOT NULL CHECK (response_version BETWEEN 0 AND 9007199254740991),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id,key)
);
CREATE INDEX cart_requests_created ON commerce.cart_requests(created_at);
