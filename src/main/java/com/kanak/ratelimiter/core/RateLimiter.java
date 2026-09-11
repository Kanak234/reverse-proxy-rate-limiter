package com.kanak.ratelimiter.core;

/**
 * Common contract for high-performance rate limiters.
 */
public interface RateLimiter extends AutoCloseable {

    /**
     * Attempts to acquire a single token for an incoming request.
     *
     * @return Result containing admission decision and rate limit telemetry.
     */
    default RateLimitResult tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire specified number of tokens.
     *
     * @param tokens Number of tokens to acquire.
     * @return Result containing admission decision and rate limit telemetry.
     */
    RateLimitResult tryAcquire(long tokens);

    /**
     * Returns current remaining tokens (snapshot).
     */
    long remainingTokens();

    /**
     * Returns the maximum burst capacity or window limit.
     */
    long capacity();

    /**
     * Closes the rate limiter, releasing off-heap native resources if applicable.
     */
    @Override
    default void close() {
        // Default no-op for heap-based or non-allocating limiters
    }
}
