package com.agora.catalog.application;

import com.agora.catalog.domain.ProductImage.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MediaStore {
    record Key(UUID actor, UUID shop, UUID product, UUID key, String hash) {}
    record Intent(UUID id, UUID actor, UUID shop, UUID product, String publicId, String state,
                  String mime, long bytes, int width, int height, Instant expiresAt) {
        public Upload response() { return new Upload(id, product, expiresAt); }
        public String reservedId() { return "agora/products/" + product + "/" + id; }
    }
    record Reservation(Intent intent, boolean created) {}
    Images list(UUID shop, UUID product);
    Reservation reserve(Key key, String mime, long bytes, int width, int height);
    Upload uploaded(Key key, UUID id, ImageStorage.Stored stored);
    Images mutate(Key key, UUID image, long expectedVersion, boolean attach, String traceId);
    List<Intent> claimCleanup();
    void deleted(UUID id);
}
