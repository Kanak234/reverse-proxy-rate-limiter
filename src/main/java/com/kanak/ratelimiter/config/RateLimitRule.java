package com.kanak.ratelimiter.config;

import com.kanak.ratelimiter.extractor.KeyExtractorStrategy;

import java.util.Objects;

/**
 * Configuration rule specifying the rate limit policy applied to a route prefix.
 */
public final class RateLimitRule {

    private final String routePrefix;
    private final RateLimitAlgorithm algorithm;
    private final long capacity;
    private final long refillRatePerSecond;
    private final long windowMillis;
    private final int sliceCount;
    private final KeyExtractorStrategy strategy;

    private RateLimitRule(Builder builder) {
        this.routePrefix = Objects.requireNonNull(builder.routePrefix, "routePrefix must not be null");
        this.algorithm = Objects.requireNonNull(builder.algorithm, "algorithm must not be null");
        this.capacity = builder.capacity;
        this.refillRatePerSecond = builder.refillRatePerSecond;
        this.windowMillis = builder.windowMillis;
        this.sliceCount = builder.sliceCount;
        this.strategy = Objects.requireNonNull(builder.strategy, "strategy must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getRoutePrefix() {
        return routePrefix;
    }

    public RateLimitAlgorithm getAlgorithm() {
        return algorithm;
    }

    public long getCapacity() {
        return capacity;
    }

    public long getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    public int getSliceCount() {
        return sliceCount;
    }

    public KeyExtractorStrategy getStrategy() {
        return strategy;
    }

    public static final class Builder {
        private String routePrefix = "/";
        private RateLimitAlgorithm algorithm = RateLimitAlgorithm.TOKEN_BUCKET;
        private long capacity = 1000;
        private long refillRatePerSecond = 500;
        private long windowMillis = 60_000;
        private int sliceCount = 60;
        private KeyExtractorStrategy strategy = KeyExtractorStrategy.IP;

        public Builder routePrefix(String routePrefix) {
            this.routePrefix = routePrefix;
            return this;
        }

        public Builder algorithm(RateLimitAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder capacity(long capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive: " + capacity);
            }
            this.capacity = capacity;
            return this;
        }

        public Builder refillRatePerSecond(long refillRatePerSecond) {
            if (refillRatePerSecond < 0) {
                throw new IllegalArgumentException("refillRatePerSecond must be non-negative: " + refillRatePerSecond);
            }
            this.refillRatePerSecond = refillRatePerSecond;
            return this;
        }

        public Builder windowMillis(long windowMillis) {
            if (windowMillis <= 0) {
                throw new IllegalArgumentException("windowMillis must be positive: " + windowMillis);
            }
            this.windowMillis = windowMillis;
            return this;
        }

        public Builder sliceCount(int sliceCount) {
            if (sliceCount <= 0) {
                throw new IllegalArgumentException("sliceCount must be positive: " + sliceCount);
            }
            this.sliceCount = sliceCount;
            return this;
        }

        public Builder strategy(KeyExtractorStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public RateLimitRule build() {
            return new RateLimitRule(this);
        }
    }
}
