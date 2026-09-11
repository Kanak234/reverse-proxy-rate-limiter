package com.kanak.ratelimiter.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, lock-free, zero-allocation Token Bucket rate limiter.
 * <p>
 * State is compressed into a single 64-bit atomic integer (AtomicLong):
 * - Bits 63..32: Current available token balance (unsigned 32-bit int)
 * - Bits 31..0:  Last refill timestamp in milliseconds relative to boot epoch (unsigned 32-bit int)
 * <p>
 * Refills and token acquisitions are executed atomically via hardware CAS loops.
 */
public final class TokenBucket implements RateLimiter {

    private static final long BOOT_EPOCH = System.currentTimeMillis();

    private final long capacity;
    private final long refillRatePerSecond;
    private final AtomicLong state;

    /**
     * Constructs a TokenBucket with initial full capacity.
     *
     * @param capacity            Maximum token burst capacity.
     * @param refillRatePerSecond Refill rate in tokens per second.
     */
    public TokenBucket(long capacity, long refillRatePerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        if (refillRatePerSecond < 0) {
            throw new IllegalArgumentException("Refill rate must be non-negative: " + refillRatePerSecond);
        }
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;

        long initialTime = (System.currentTimeMillis() - BOOT_EPOCH) & 0xFFFFFFFFL;
        long initialWord = (capacity << 32) | initialTime;
        this.state = new AtomicLong(initialWord);
    }

    @Override
    public RateLimitResult tryAcquire(long tokensToAcquire) {
        if (tokensToAcquire <= 0) {
            throw new IllegalArgumentException("Tokens to acquire must be positive: " + tokensToAcquire);
        }

        long current = state.get();
        while (true) {
            long curTokens = (current >>> 32);
            long lastTime = current & 0xFFFFFFFFL;
            long now = (System.currentTimeMillis() - BOOT_EPOCH) & 0xFFFFFFFFL;

            // Compute elapsed milliseconds accounting for 32-bit rollover
            long deltaMillis = (now - lastTime) & 0xFFFFFFFFL;

            long tokensToAdd = 0;
            long newLastTime = lastTime;

            if (refillRatePerSecond > 0 && deltaMillis > 0) {
                tokensToAdd = (deltaMillis * refillRatePerSecond) / 1000L;
                if (tokensToAdd > 0) {
                    long timeAccounted = (tokensToAdd * 1000L) / refillRatePerSecond;
                    newLastTime = (lastTime + timeAccounted) & 0xFFFFFFFFL;
                }
            }

            long refreshedTokens = Math.min(capacity, curTokens + tokensToAdd);

            if (refreshedTokens < tokensToAcquire) {
                // Not enough tokens available -> compute retry delay
                long retryAfterMillis;
                if (refillRatePerSecond > 0) {
                    long needed = tokensToAcquire - refreshedTokens;
                    retryAfterMillis = (needed * 1000L + refillRatePerSecond - 1) / refillRatePerSecond;
                } else {
                    retryAfterMillis = 60_000L; // Static burst without refill
                }

                // If tokens were refilled, attempt to advance the state without consuming
                if (tokensToAdd > 0) {
                    long refreshedWord = (refreshedTokens << 32) | newLastTime;
                    state.compareAndSet(current, refreshedWord);
                }

                long resetEpochSec = (System.currentTimeMillis() + retryAfterMillis) / 1000L;
                return RateLimitResult.rejected(capacity, retryAfterMillis, resetEpochSec);
            }

            // Consume requested tokens
            long nextTokens = refreshedTokens - tokensToAcquire;
            long nextWord = (nextTokens << 32) | newLastTime;

            if (state.compareAndSet(current, nextWord)) {
                long resetEpochSec;
                if (refillRatePerSecond > 0 && nextTokens < capacity) {
                    long fillTimeMillis = ((capacity - nextTokens) * 1000L) / refillRatePerSecond;
                    resetEpochSec = (System.currentTimeMillis() + fillTimeMillis) / 1000L;
                } else {
                    resetEpochSec = System.currentTimeMillis() / 1000L;
                }
                return RateLimitResult.admitted(nextTokens, capacity, resetEpochSec);
            }

            // CAS conflict: refresh current and retry
            current = state.get();
        }
    }

    @Override
    public long remainingTokens() {
        long current = state.get();
        long curTokens = (current >>> 32);
        long lastTime = current & 0xFFFFFFFFL;
        long now = (System.currentTimeMillis() - BOOT_EPOCH) & 0xFFFFFFFFL;
        long deltaMillis = (now - lastTime) & 0xFFFFFFFFL;

        if (refillRatePerSecond > 0 && deltaMillis > 0) {
            long tokensToAdd = (deltaMillis * refillRatePerSecond) / 1000L;
            return Math.min(capacity, curTokens + tokensToAdd);
        }
        return curTokens;
    }

    @Override
    public long capacity() {
        return capacity;
    }

    public long getRefillRatePerSecond() {
        return refillRatePerSecond;
    }
}
