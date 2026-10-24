package com.agora.catalog.application;

import java.util.Optional;

/** Provider metadata is verified before it can be attached to a product. */
public interface ImageStorage {
    record Stored(String publicId, long version, String format, long bytes, int width, int height, String url) {}
    Stored upload(byte[] data, String mime, String publicId);
    Optional<Stored> find(String publicId);
    void delete(String publicId);
}
