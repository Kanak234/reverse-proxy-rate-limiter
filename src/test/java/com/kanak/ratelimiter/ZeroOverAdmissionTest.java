package com.kanak.ratelimiter;

import com.kanak.ratelimiter.core.SlidingWindowCounter;
import com.kanak.ratelimiter.core.TokenBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ZeroOverAdmissionTest {

    @Test
    @DisplayName("TokenBucket: Exactly zero over-admission under 10,000 concurrent requests across 32 threads")
    void testTokenBucketZeroOverAdmission() throws InterruptedException {
        int capacity = 100;
        TokenBucket bucket = new TokenBucket(capacity, 0); // Zero refill

        int threads = 20;
        int requestsPerThread = 500;
        int totalRequests = threads * requestsPerThread; // exactly 10,000

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

        // Release all threads simultaneously for maximum contention
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Invariant: EXACTLY 100 admitted, EXACTLY 9,900 rejected
        assertThat(admitted.get())
                .as("Admitted requests must match configured capacity with zero over-admission")
                .isEqualTo(capacity);

        assertThat(rejected.get())
                .as("Rejected requests must exactly equal total minus capacity")
                .isEqualTo(totalRequests - capacity);

        assertThat(bucket.remainingTokens()).isZero();
    }

    @Test
    @DisplayName("SlidingWindow: Exactly zero over-admission under 10,000 concurrent requests across 32 threads")
    void testSlidingWindowZeroOverAdmission() throws InterruptedException {
        int limit = 100;
        int threads = 20;
        int requestsPerThread = 500;
        int totalRequests = threads * requestsPerThread; // exactly 10,000

        try (SlidingWindowCounter counter = new SlidingWindowCounter(limit, 60_000, 60)) {
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
                            if (counter.tryAcquire().allowed()) {
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

            assertThat(admitted.get())
                    .as("Admitted requests in sliding window must match limit with zero over-admission")
                    .isEqualTo(limit);

            assertThat(rejected.get())
                    .as("Rejected requests must match total minus limit")
                    .isEqualTo(totalRequests - limit);

            assertThat(counter.remainingTokens()).isZero();
        }
    }
}
