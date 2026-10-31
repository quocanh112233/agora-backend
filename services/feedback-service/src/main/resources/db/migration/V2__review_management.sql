CREATE INDEX reviews_shop_created ON feedback.reviews(shop_id,created_at DESC,id DESC);
CREATE INDEX reviews_created ON feedback.reviews(created_at DESC,id DESC);
