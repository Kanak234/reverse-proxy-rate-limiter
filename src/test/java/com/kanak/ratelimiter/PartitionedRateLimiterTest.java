package com.kanak.ratelimiter;

import com.kanak.ratelimiter.config.RateLimitAlgorithm;
import com.kanak.ratelimiter.config.RateLimitRule;
import com.kanak.ratelimiter.core.PartitionedRateLimiter;
import com.kanak.ratelimiter.core.RateLimitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionedRateLimiterTest {

    @Test
    @DisplayName("Should isolate limits across independent client partition keys")
    void testClientKeyIsolation() {
        try (PartitionedRateLimiter limiter = new PartitionedRateLimiter()) {
            RateLimitRule rule = RateLimitRule.builder()
                    .routePrefix("/")
                    .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                    .capacity(2)
                    .refillRatePerSecond(0)
                    .build();

            // Client A consumes 2 tokens
            assertThat(limiter.tryAcquire("client-A", rule).allowed()).isTrue();
            assertThat(limiter.tryAcquire("client-A", rule).allowed()).isTrue();
            assertThat(limiter.tryAcquire("client-A", rule).allowed()).isFalse();

            // Client B must still have full 2 tokens available
            assertThat(limiter.tryAcquire("client-B", rule).allowed()).isTrue();
            assertThat(limiter.tryAcquire("client-B", rule).allowed()).isTrue();
            assertThat(limiter.tryAcquire("client-B", rule).allowed()).isFalse();

            assertThat(limiter.totalActiveKeys()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("Should cleanup idle keys older than threshold")
    void testCleanupIdleKeys() throws InterruptedException {
        try (PartitionedRateLimiter limiter = new PartitionedRateLimiter()) {
            RateLimitRule rule = RateLimitRule.builder()
                    .routePrefix("/")
                    .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                    .capacity(10)
                    .refillRatePerSecond(10)
                    .build();

            limiter.tryAcquire("key1", rule);
            limiter.tryAcquire("key2", rule);
            limiter.tryAcquire("key3", rule);
            assertThat(limiter.totalActiveKeys()).isEqualTo(3);

            Thread.sleep(50);

            // Cleanup keys idle for more than 20ms
            int purged = limiter.cleanupIdleKeys(20);
            assertThat(purged).isEqualTo(3);
            assertThat(limiter.totalActiveKeys()).isZero();
        }
    }
}
