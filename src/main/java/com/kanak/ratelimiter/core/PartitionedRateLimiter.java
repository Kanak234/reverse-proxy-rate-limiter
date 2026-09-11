package com.kanak.ratelimiter.core;

import com.kanak.ratelimiter.config.RateLimitAlgorithm;
import com.kanak.ratelimiter.config.RateLimitRule;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-concurrency striped rate limiter partitioning client keys across 256 cache-line padded shards.
 * <p>
 * Eliminates CPU cache line false sharing and CAS starvation under high concurrency.
 */
public final class PartitionedRateLimiter implements AutoCloseable {

    private static final int NUM_SHARDS = 256;
    private final Shard[] shards;
    private final AtomicLong totalKeys = new AtomicLong(0);

    /**
     * Cache-line padded shard structure (64-byte padding before and after).
     */
    private static final class Shard {
        // 56 bytes padding to isolate cache line
        long p1, p2, p3, p4, p5, p6, p7;

        final ConcurrentHashMap<String, Entry> buckets = new ConcurrentHashMap<>();

        // 56 bytes trailing padding
        long p8, p9, p10, p11, p12, p13, p14;
    }

    private static final class Entry {
        final RateLimiter limiter;
        volatile long lastAccessTime;

        Entry(RateLimiter limiter) {
            this.limiter = limiter;
            this.lastAccessTime = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    public PartitionedRateLimiter() {
        this.shards = new Shard[NUM_SHARDS];
        for (int i = 0; i < NUM_SHARDS; i++) {
            shards[i] = new Shard();
        }
    }

    /**
     * Evaluates rate limiting for a specific client key according to rule specifications.
     */
    public RateLimitResult tryAcquire(String key, RateLimitRule rule, long tokens) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(rule, "rule must not be null");

        String compositeKey = rule.getRoutePrefix() + "#" + key;
        Shard shard = getShard(compositeKey);
        Entry entry = shard.buckets.computeIfAbsent(compositeKey, k -> {
            totalKeys.incrementAndGet();
            RateLimiter limiter;
            if (rule.getAlgorithm() == RateLimitAlgorithm.SLIDING_WINDOW) {
                limiter = new SlidingWindowCounter(
                        rule.getCapacity(),
                        rule.getWindowMillis(),
                        rule.getSliceCount()
                );
            } else {
                limiter = new TokenBucket(
                        rule.getCapacity(),
                        rule.getRefillRatePerSecond()
                );
            }
            return new Entry(limiter);
        });

        entry.touch();
        return entry.limiter.tryAcquire(tokens);
    }

    public RateLimitResult tryAcquire(String key, RateLimitRule rule) {
        return tryAcquire(key, rule, 1);
    }

    /**
     * Removes keys that have been idle longer than maxIdleMillis to prevent unbounded memory growth.
     *
     * @param maxIdleMillis Inactivity threshold in milliseconds.
     * @return Number of purged keys.
     */
    public int cleanupIdleKeys(long maxIdleMillis) {
        long now = System.currentTimeMillis();
        int purged = 0;

        for (Shard shard : shards) {
            var iterator = shard.buckets.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (now - entry.getValue().lastAccessTime > maxIdleMillis) {
                    entry.getValue().limiter.close();
                    iterator.remove();
                    totalKeys.decrementAndGet();
                    purged++;
                }
            }
        }
        return purged;
    }

    public long totalActiveKeys() {
        return totalKeys.get();
    }

    private Shard getShard(String key) {
        int hash = key.hashCode();
        hash = hash ^ (hash >>> 16);
        int index = (hash & (NUM_SHARDS - 1));
        return shards[index];
    }

    @Override
    public void close() {
        for (Shard shard : shards) {
            for (Entry entry : shard.buckets.values()) {
                entry.limiter.close();
            }
            shard.buckets.clear();
        }
        totalKeys.set(0);
    }
}
