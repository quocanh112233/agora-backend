package com.agora.identity.application;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Fixed-window, single-instance limiter. A full key map fails closed, never grows unbounded. */
public final class AttemptLimiter {
    private final int attempts;
    private final Duration window;
    private final int maxKeys;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public AttemptLimiter(int attempts, Duration window, int maxKeys) {
        if (attempts < 1 || window.isNegative() || window.isZero() || maxKeys < 1) {
            throw new IllegalArgumentException("Invalid rate limit configuration");
        }
        this.attempts = attempts; this.window = window; this.maxKeys = maxKeys;
    }

    public synchronized boolean allow(String key, Instant now) {
        Bucket bucket = buckets.get(key);
        if (bucket != null && !now.isBefore(bucket.expires())) { buckets.remove(key); bucket = null; }
        if (bucket == null) {
            if (buckets.size() >= maxKeys) buckets.values().removeIf(value -> !now.isBefore(value.expires()));
            if (buckets.size() >= maxKeys) return false;
            bucket = new Bucket(now.plus(window), 0);
        }
        if (bucket.count() >= attempts) return false;
        buckets.put(key, new Bucket(bucket.expires(), bucket.count() + 1));
        return true;
    }

    private record Bucket(Instant expires, int count) { }
}
