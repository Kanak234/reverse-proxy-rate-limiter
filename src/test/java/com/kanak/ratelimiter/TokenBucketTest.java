package com.kanak.ratelimiter;

import com.kanak.ratelimiter.core.RateLimitResult;
import com.kanak.ratelimiter.core.TokenBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketTest {

    @Test
    @DisplayName("Should admit requests up to burst capacity and reject subsequent requests")
    void testBurstCapacityAndExhaustion() {
        TokenBucket bucket = new TokenBucket(5, 0); // 5 tokens, no refill

        for (int i = 0; i < 5; i++) {
            RateLimitResult result = bucket.tryAcquire();
            assertThat(result.allowed()).isTrue();
            assertThat(result.remainingTokens()).isEqualTo(4 - i);
            assertThat(result.limit()).isEqualTo(5);
        }

        // 6th request must be rejected
        RateLimitResult rejected = bucket.tryAcquire();
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remainingTokens()).isZero();
        assertThat(rejected.retryAfterMillis()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should refill tokens over elapsed time")
    void testTokenRefill() throws InterruptedException {
        // 10 capacity, 100 tokens/sec (1 token every 10ms)
        TokenBucket bucket = new TokenBucket(10, 100);

        // Exhaust all 10 tokens
        for (int i = 0; i < 10; i++) {
            assertThat(bucket.tryAcquire().allowed()).isTrue();
        }
        assertThat(bucket.tryAcquire().allowed()).isFalse();

        // Wait 50ms -> should regenerate at least 4-5 tokens
        Thread.sleep(60);

        RateLimitResult result = bucket.tryAcquire();
        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Should handle multi-token acquisition atomically")
    void testMultiTokenAcquisition() {
        TokenBucket bucket = new TokenBucket(10, 0);

        RateLimitResult res1 = bucket.tryAcquire(4);
        assertThat(res1.allowed()).isTrue();
        assertThat(res1.remainingTokens()).isEqualTo(6);

        RateLimitResult res2 = bucket.tryAcquire(6);
        assertThat(res2.allowed()).isTrue();
        assertThat(res2.remainingTokens()).isEqualTo(0);

        // Cannot acquire 1 more token
        RateLimitResult res3 = bucket.tryAcquire(1);
        assertThat(res3.allowed()).isFalse();
    }

    @Test
    @DisplayName("Should reject invalid construction parameters")
    void testInvalidParameters() {
        assertThatThrownBy(() -> new TokenBucket(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(-5, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should maintain atomic consistency across concurrent threads")
    void testConcurrentAcquisitions() throws InterruptedException {
        int capacity = 1000;
        TokenBucket bucket = new TokenBucket(capacity, 0); // No refill

        int threads = 16;
        int requestsPerThread = 100; // Total 1600 requests
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger admitted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (bucket.tryAcquire().allowed()) {
                            admitted.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertThat(admitted.get()).isEqualTo(capacity);
        assertThat(rejected.get()).isEqualTo((threads * requestsPerThread) - capacity);
        assertThat(bucket.remainingTokens()).isZero();
    }
}
