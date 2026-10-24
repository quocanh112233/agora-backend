CREATE TABLE catalog.image_uploads (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    product_id uuid NOT NULL,
    key uuid NOT NULL,
    request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    public_id text NOT NULL UNIQUE CHECK (public_id = 'agora/products/' || product_id::text || '/' || id::text),
    state text NOT NULL CHECK (state IN ('PENDING','UPLOADED','DELETE_PENDING','DELETED')),
    mime text NOT NULL CHECK (mime IN ('image/jpeg','image/png')),
    bytes bigint NOT NULL CHECK (bytes BETWEEN 1 AND 5242880),
    width integer NOT NULL CHECK (width BETWEEN 1 AND 20000000),
    height integer NOT NULL CHECK (height BETWEEN 1 AND 20000000),
    provider_version bigint CHECK (provider_version > 0),
    format text CHECK (format IN ('jpg','png')),
    url text CHECK (url ~ '^https://res\.cloudinary\.com/[A-Za-z0-9_-]+/image/upload/v[0-9]+/agora/products/'),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    UNIQUE (actor_id, key),
    UNIQUE (id, product_id, shop_id),
    FOREIGN KEY (product_id, shop_id) REFERENCES catalog.products(id, shop_id),
    CHECK (width::bigint * height <= 20000000),
    CHECK ((provider_version IS NULL AND format IS NULL AND url IS NULL AND state <> 'UPLOADED')
        OR (provider_version IS NOT NULL AND format IS NOT NULL AND url IS NOT NULL)),
    CHECK (format IS NULL OR (mime = 'image/jpeg' AND format = 'jpg') OR (mime = 'image/png' AND format = 'png'))
);
CREATE INDEX image_uploads_actor ON catalog.image_uploads(actor_id, state, expires_at);
CREATE INDEX image_uploads_cleanup ON catalog.image_uploads(expires_at) WHERE state <> 'DELETED';

CREATE TABLE catalog.product_images (
    upload_id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    shop_id uuid NOT NULL,
    position smallint NOT NULL CHECK (position BETWEEN 0 AND 4),
    FOREIGN KEY (upload_id, product_id, shop_id) REFERENCES catalog.image_uploads(id, product_id, shop_id),
    UNIQUE (product_id, position)
);

CREATE TABLE catalog.media_requests (
    actor_id uuid NOT NULL,
    key uuid NOT NULL,
    shop_id uuid NOT NULL,
    product_id uuid NOT NULL,
    operation text NOT NULL CHECK (operation IN ('ATTACH_IMAGE','DETACH_IMAGE')),
    request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    response_snapshot jsonb NOT NULL CHECK (jsonb_typeof(response_snapshot) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '7 days'),
    PRIMARY KEY (actor_id, key),
    FOREIGN KEY (product_id, shop_id) REFERENCES catalog.products(id, shop_id)
);
CREATE INDEX media_requests_expires ON catalog.media_requests(expires_at);
