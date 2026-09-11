package com.kanak.ratelimiter;

import com.kanak.ratelimiter.core.RateLimitResult;
import com.kanak.ratelimiter.core.SlidingWindowCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlidingWindowCounterTest {

    @Test
    @DisplayName("Should admit up to limit within sliding window and reject excess")
    void testSlidingWindowLimit() {
        // Window 1000ms, 10 slices (100ms each), limit 5
        try (SlidingWindowCounter counter = new SlidingWindowCounter(5, 1000, 10)) {
            for (int i = 0; i < 5; i++) {
                RateLimitResult res = counter.tryAcquire();
                assertThat(res.allowed()).isTrue();
                assertThat(res.remainingTokens()).isEqualTo(4 - i);
            }

            RateLimitResult rejected = counter.tryAcquire();
            assertThat(rejected.allowed()).isFalse();
            assertThat(rejected.remainingTokens()).isZero();
            assertThat(rejected.retryAfterMillis()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("Should expire slices and recover capacity as time slides forward")
    void testSliceExpiration() throws InterruptedException {
        // Window 100ms, 5 slices (20ms each), limit 3
        try (SlidingWindowCounter counter = new SlidingWindowCounter(3, 100, 5)) {
            assertThat(counter.tryAcquire().allowed()).isTrue();
            assertThat(counter.tryAcquire().allowed()).isTrue();
            assertThat(counter.tryAcquire().allowed()).isTrue();
            assertThat(counter.tryAcquire().allowed()).isFalse();

            // Wait past full window
            Thread.sleep(120);

            // Capacity should be fully recovered
            assertThat(counter.tryAcquire().allowed()).isTrue();
            assertThat(counter.remainingTokens()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("Should reject invalid construction arguments")
    void testInvalidArguments() {
        assertThatThrownBy(() -> new SlidingWindowCounter(0, 1000, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowCounter(10, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowCounter(10, 1000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
