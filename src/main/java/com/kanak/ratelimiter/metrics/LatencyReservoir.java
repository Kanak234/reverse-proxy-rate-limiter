package com.kanak.ratelimiter.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Thread-safe, lock-free microsecond latency reservoir for calculating percentiles (p50, p90, p99, p99.9, max).
 */
public final class LatencyReservoir {

    private static final int RESERVOIR_SIZE = 4096;
    private final AtomicLongArray reservoir = new AtomicLongArray(RESERVOIR_SIZE);
    private final AtomicInteger index = new AtomicInteger(0);
    private volatile long maxLatencyMicros = 0;

    public void record(long durationMicros) {
        if (durationMicros < 0) {
            durationMicros = 0;
        }

        // Update max
        long curMax = maxLatencyMicros;
        while (durationMicros > curMax) {
            if (maxLatencyMicros == curMax) {
                maxLatencyMicros = durationMicros;
                break;
            }
            curMax = maxLatencyMicros;
        }

        // Circular ring insert
        int idx = (index.getAndIncrement() & 0x7FFFFFFF) % RESERVOIR_SIZE;
        reservoir.set(idx, durationMicros);
    }

    public Percentiles getPercentiles() {
        int count = Math.min(index.get(), RESERVOIR_SIZE);
        if (count == 0) {
            return new Percentiles(0, 0, 0, 0, 0);
        }

        long[] snapshot = new long[count];
        for (int i = 0; i < count; i++) {
            snapshot[i] = reservoir.get(i);
        }
        Arrays.sort(snapshot);

        long p50 = snapshot[(int) (count * 0.50)];
        long p90 = snapshot[(int) (count * 0.90)];
        long p99 = snapshot[Math.min(count - 1, (int) (count * 0.99))];
        long p999 = snapshot[Math.min(count - 1, (int) (count * 0.999))];
        long max = Math.max(maxLatencyMicros, snapshot[count - 1]);

        return new Percentiles(p50, p90, p99, p999, max);
    }

    public record Percentiles(long p50Micros, long p90Micros, long p99Micros, long p999Micros, long maxMicros) {
    }
}
