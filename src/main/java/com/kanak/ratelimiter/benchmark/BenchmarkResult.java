package com.kanak.ratelimiter.benchmark;

import java.util.Locale;

/**
 * Encapsulates the results of a high-throughput load benchmark run.
 */
public record BenchmarkResult(
        int totalRequests,
        int admittedCount,
        int rejectedCount,
        int errorCount,
        long durationMillis,
        double throughputReqPerSec,
        long p50Micros,
        long p90Micros,
        long p99Micros,
        long p999Micros,
        long maxMicros
) {

    public String formatReport() {
        return String.format(Locale.US,
                """
                ========================================================================
                 Reverse Proxy Rate Limiter Benchmark Report
                ========================================================================
                  Total Requests:          %,d
                  Admitted (HTTP 200):     %,d (%.2f%%)
                  Rejected (HTTP 429):     %,d (%.2f%%)
                  Errors:                  %,d
                  Elapsed Time:            %,d ms
                  Throughput:              %,.2f req/sec
                ------------------------------------------------------------------------
                 Latency Percentiles:
                  p50 (Median):            %.3f ms (%,d us)
                  p90:                     %.3f ms (%,d us)
                  p99:                     %.3f ms (%,d us)
                  p99.9:                   %.3f ms (%,d us)
                  Max:                     %.3f ms (%,d us)
                ========================================================================
                """,
                totalRequests,
                admittedCount, totalRequests > 0 ? (admittedCount * 100.0 / totalRequests) : 0,
                rejectedCount, totalRequests > 0 ? (rejectedCount * 100.0 / totalRequests) : 0,
                errorCount,
                durationMillis,
                throughputReqPerSec,
                p50Micros / 1000.0, p50Micros,
                p90Micros / 1000.0, p90Micros,
                p99Micros / 1000.0, p99Micros,
                p999Micros / 1000.0, p999Micros,
                maxMicros / 1000.0, maxMicros
        );
    }
}
