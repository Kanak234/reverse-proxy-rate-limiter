package com.kanak.ratelimiter.benchmark;

import com.kanak.ratelimiter.config.RateLimitRule;
import com.kanak.ratelimiter.core.PartitionedRateLimiter;
import com.kanak.ratelimiter.core.RateLimitResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-concurrency load generator and benchmark harness.
 */
public final class LoadHarness {

    private LoadHarness() {
    }

    /**
     * Executes a high-concurrency HTTP load benchmark against a running reverse proxy server.
     *
     * @param targetUri     URL of the target proxy endpoint (e.g., http://localhost:8080/api/test).
     * @param totalRequests Total number of requests to execute.
     * @param concurrency   Number of concurrent worker threads.
     * @return BenchmarkResult containing throughput and latency statistics.
     */
    public static BenchmarkResult runHttpBenchmark(URI targetUri, int totalRequests, int concurrency) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        AtomicInteger admitted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        long[] latenciesMicros = new long[totalRequests];
        AtomicInteger latencyIdx = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(totalRequests);

        long startNanos = System.nanoTime();

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long reqStart = System.nanoTime();

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(targetUri)
                            .GET()
                            .timeout(Duration.ofSeconds(5))
                            .build();

                    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                    long durationMicros = (System.nanoTime() - reqStart) / 1000;

                    int idx = latencyIdx.getAndIncrement();
                    if (idx < latenciesMicros.length) {
                        latenciesMicros[idx] = durationMicros;
                    }

                    int code = response.statusCode();
                    if (code == 200) {
                        admitted.incrementAndGet();
                    } else if (code == 429) {
                        rejected.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();

        try {
            completionLatch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        long totalNanos = System.nanoTime() - startNanos;
        long durationMillis = Math.max(1, totalNanos / 1_000_000);
        double throughput = (totalRequests * 1000.0) / durationMillis;

        int recorded = Math.min(latencyIdx.get(), latenciesMicros.length);
        long[] validLatencies = Arrays.copyOf(latenciesMicros, recorded);
        Arrays.sort(validLatencies);

        long p50 = recorded > 0 ? validLatencies[(int) (recorded * 0.50)] : 0;
        long p90 = recorded > 0 ? validLatencies[(int) (recorded * 0.90)] : 0;
        long p99 = recorded > 0 ? validLatencies[Math.min(recorded - 1, (int) (recorded * 0.99))] : 0;
        long p999 = recorded > 0 ? validLatencies[Math.min(recorded - 1, (int) (recorded * 0.999))] : 0;
        long max = recorded > 0 ? validLatencies[recorded - 1] : 0;

        return new BenchmarkResult(
                totalRequests,
                admitted.get(),
                rejected.get(),
                errors.get(),
                durationMillis,
                throughput,
                p50, p90, p99, p999, max
        );
    }

    /**
     * Microbenchmark evaluating raw in-memory PartitionedRateLimiter performance without network overhead.
     */
    public static BenchmarkResult runInMemoryBenchmark(PartitionedRateLimiter limiter, RateLimitRule rule,
                                                        int totalRequests, int concurrency, int keyCount) {
        AtomicInteger admitted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        long[] latenciesMicros = new long[totalRequests];
        AtomicInteger latencyIdx = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(totalRequests);

        long startNanos = System.nanoTime();

        for (int i = 0; i < totalRequests; i++) {
            final int reqId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long reqStart = System.nanoTime();

                    String key = "client-ip-" + (reqId % keyCount);
                    RateLimitResult res = limiter.tryAcquire(key, rule);

                    long durationMicros = (System.nanoTime() - reqStart) / 1000;
                    int idx = latencyIdx.getAndIncrement();
                    if (idx < latenciesMicros.length) {
                        latenciesMicros[idx] = durationMicros;
                    }

                    if (res.allowed()) {
                        admitted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();

        try {
            completionLatch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        long totalNanos = System.nanoTime() - startNanos;
        long durationMillis = Math.max(1, totalNanos / 1_000_000);
        double throughput = (totalRequests * 1000.0) / durationMillis;

        int recorded = Math.min(latencyIdx.get(), latenciesMicros.length);
        long[] validLatencies = Arrays.copyOf(latenciesMicros, recorded);
        Arrays.sort(validLatencies);

        long p50 = recorded > 0 ? validLatencies[(int) (recorded * 0.50)] : 0;
        long p90 = recorded > 0 ? validLatencies[(int) (recorded * 0.90)] : 0;
        long p99 = recorded > 0 ? validLatencies[Math.min(recorded - 1, (int) (recorded * 0.99))] : 0;
        long p999 = recorded > 0 ? validLatencies[Math.min(recorded - 1, (int) (recorded * 0.999))] : 0;
        long max = recorded > 0 ? validLatencies[recorded - 1] : 0;

        return new BenchmarkResult(
                totalRequests,
                admitted.get(),
                rejected.get(),
                0,
                durationMillis,
                throughput,
                p50, p90, p99, p999, max
        );
    }
}
