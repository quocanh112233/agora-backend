CREATE INDEX products_public_newest ON catalog.products(created_at DESC,id DESC) WHERE status='PUBLISHED';
