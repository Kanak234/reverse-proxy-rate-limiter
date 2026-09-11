package com.kanak.ratelimiter.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregates runtime rate limiting and reverse proxy operational metrics.
 */
public final class MetricsCollector {

    private static final MetricsCollector INSTANCE = new MetricsCollector();

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder admittedRequests = new LongAdder();
    private final LongAdder rejectedRequests = new LongAdder();
    private final LongAdder upstreamSuccesses = new LongAdder();
    private final LongAdder upstreamErrors = new LongAdder();
    private final LatencyReservoir latencyReservoir = new LatencyReservoir();

    private final long startTimeMillis = System.currentTimeMillis();

    private MetricsCollector() {
    }

    public static MetricsCollector getInstance() {
        return INSTANCE;
    }

    public void markRequestReceived() {
        totalRequests.increment();
    }

    public void markAdmitted() {
        admittedRequests.increment();
    }

    public void markRejected() {
        rejectedRequests.increment();
    }

    public void markUpstreamSuccess(long durationMicros) {
        upstreamSuccesses.increment();
        latencyReservoir.record(durationMicros);
    }

    public void markUpstreamError() {
        upstreamErrors.increment();
    }

    public void recordLatency(long durationMicros) {
        latencyReservoir.record(durationMicros);
    }

    public Map<String, Object> getSnapshot(long activeKeys) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        long total = totalRequests.sum();
        long admitted = admittedRequests.sum();
        long rejected = rejectedRequests.sum();
        long upSuccess = upstreamSuccesses.sum();
        long upError = upstreamErrors.sum();
        long uptimeSec = Math.max(1, (System.currentTimeMillis() - startTimeMillis) / 1000);

        LatencyReservoir.Percentiles percentiles = latencyReservoir.getPercentiles();

        snapshot.put("uptimeSeconds", uptimeSec);
        snapshot.put("totalRequests", total);
        snapshot.put("admittedRequests", admitted);
        snapshot.put("rejectedRequests", rejected);
        snapshot.put("rejectionRatePercent", total > 0 ? ((double) rejected / total) * 100.0 : 0.0);
        snapshot.put("upstreamSuccesses", upSuccess);
        snapshot.put("upstreamErrors", upError);
        snapshot.put("activeRateLimitKeys", activeKeys);
        snapshot.put("throughputReqPerSec", (double) total / uptimeSec);

        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("p50_us", percentiles.p50Micros());
        latency.put("p90_us", percentiles.p90Micros());
        latency.put("p99_us", percentiles.p99Micros());
        latency.put("p999_us", percentiles.p999Micros());
        latency.put("max_us", percentiles.maxMicros());
        snapshot.put("latencyMicros", latency);

        return snapshot;
    }

    public void reset() {
        // Only used for tests if needed
    }
}
