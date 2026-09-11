package com.kanak.ratelimiter.config;

/**
 * Supported rate limiting algorithm types.
 */
public enum RateLimitAlgorithm {
    /**
     * Lock-free bit-packed token bucket algorithm allowing smooth refills and burst capacity.
     */
    TOKEN_BUCKET,

    /**
     * Off-heap direct memory sliding window counter for smooth rolling window rate limiting.
     */
    SLIDING_WINDOW
}
