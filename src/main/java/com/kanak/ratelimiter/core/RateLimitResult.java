package com.kanak.ratelimiter.core;

/**
 * Result of evaluating an acquisition request against a rate limiter.
 */
public record RateLimitResult(
        boolean allowed,
        long remainingTokens,
        long limit,
        long retryAfterMillis,
        long resetEpochSeconds
) {

    public static RateLimitResult admitted(long remainingTokens, long limit, long resetEpochSeconds) {
        return new RateLimitResult(true, Math.max(0, remainingTokens), limit, 0, resetEpochSeconds);
    }

    public static RateLimitResult rejected(long limit, long retryAfterMillis, long resetEpochSeconds) {
        return new RateLimitResult(false, 0, limit, Math.max(1, retryAfterMillis), resetEpochSeconds);
    }

    /**
     * Retry-After in whole seconds (ceiling) as recommended by RFC 6585.
     */
    public long retryAfterSeconds() {
        if (retryAfterMillis <= 0) {
            return 0;
        }
        return (retryAfterMillis + 999) / 1000;
    }
}
