package com.agora.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductImage(UUID id, String url, int width, int height, long bytes, String format) {
    public record Images(UUID productId, long productVersion, List<ProductImage> items) {}
    public record Upload(UUID uploadId, UUID productId, Instant expiresAt) {}
}
