package com.agora.catalog.application;

import com.agora.catalog.application.MediaStore.*;
import com.agora.catalog.domain.CatalogFailure;
import com.agora.catalog.domain.ProductImage.*;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MediaService {
    private final MediaStore store;
    private final ImageStorage storage;
    public MediaService(MediaStore store, ImageStorage storage) { this.store = store; this.storage = storage; }
    public Images list(UUID shop, UUID product) { return store.list(shop, product); }
    public Upload upload(Key key, byte[] data, String mime, int width, int height) {
        Reservation reservation = store.reserve(key, mime, data.length, width, height);
        Intent intent = reservation.intent();
        if ("UPLOADED".equals(intent.state())) return intent.response();
        ImageStorage.Stored stored;
        try {
            stored = reservation.created() ? storage.upload(data, mime, intent.publicId())
                    : storage.find(intent.publicId()).orElseThrow(MediaService::unavailable);
            if (stored == null || !intent.reservedId().equals(stored.publicId()) || stored.version() < 1
                    || !(mime.equals("image/png") ? "png" : "jpg").equals(stored.format())
                    || stored.bytes() != intent.bytes() || stored.width() != intent.width() || stored.height() != intent.height()
                    || stored.url() == null || !stored.url().matches("https://res\\.cloudinary\\.com/[A-Za-z0-9_-]+/image/upload/v"
                        + stored.version() + "/" + intent.reservedId() + "\\." + stored.format())) throw unavailable();
        } catch (RuntimeException e) { throw unavailable(); }
        return store.uploaded(key, intent.id(), stored);
    }
    public Images attach(Key key, UUID image, long version, String trace) { return store.mutate(key, image, version, true, trace); }
    public Images detach(Key key, UUID image, long version, String trace) { return store.mutate(key, image, version, false, trace); }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void cleanup() {
        for (Intent intent : store.claimCleanup()) {
            if (!intent.reservedId().equals(intent.publicId())) continue;
            try {
                storage.delete(intent.publicId());
                store.deleted(intent.id());
            } catch (RuntimeException e) {
                // DELETE_PENDING survives provider failures and is retried by the next scheduled run.
            }
        }
    }
    private static CatalogFailure unavailable() { return new CatalogFailure(503, "MEDIA_UNAVAILABLE", "Image storage is unavailable. Retry with the same key."); }
}
