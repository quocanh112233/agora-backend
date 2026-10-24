ALTER TABLE catalog.products DROP CONSTRAINT products_status_check;
ALTER TABLE catalog.products ADD CONSTRAINT products_status_check
    CHECK (status IN ('DRAFT','PUBLISHED','HIDDEN'));
