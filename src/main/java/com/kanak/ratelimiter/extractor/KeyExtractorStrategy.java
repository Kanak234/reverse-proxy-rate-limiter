package com.kanak.ratelimiter.extractor;

/**
 * Strategy for extracting client identity keys for rate-limiting partitions.
 */
public enum KeyExtractorStrategy {
    /**
     * Rate limit based on client IP address (from X-Forwarded-For, X-Real-IP, or remote socket).
     */
    IP,

    /**
     * Rate limit based on API key provided in headers (X-API-Key or Authorization Bearer).
     */
    API_KEY,

    /**
     * Rate limit composite key combining the route prefix and client IP.
     */
    ROUTE_IP,

    /**
     * Rate limit composite key combining the route prefix and client API key.
     */
    ROUTE_API_KEY,

    /**
     * Global rate limit applied uniformly across all clients hitting the route.
     */
    GLOBAL
}
