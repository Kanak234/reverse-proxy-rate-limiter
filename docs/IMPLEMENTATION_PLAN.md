# Implementation Plan: `reverse-proxy-rate-limiter`

## 1. Directory Structure
```
reverse-proxy-rate-limiter/
├── pom.xml
├── Dockerfile
├── README.md
├── CHANGELOG.md
├── LICENSE
├── .gitignore
├── .github/
│   └── workflows/
│       └── ci.yml
├── docs/
│   ├── PRD.md
│   ├── TRD.md
│   ├── IMPLEMENTATION_PLAN.md
│   └── EXPLAIN.md
└── src/
    ├── main/
    │   └── java/
    │       └── com/
    │           └── kanak/
    │               └── ratelimiter/
    │                   ├── Main.java
    │                   ├── config/
    │                   │   ├── ProxyConfig.java
    │                   │   ├── RateLimitRule.java
    │                   │   └── RateLimitAlgorithm.java
    │                   ├── core/
    │                   │   ├── RateLimiter.java
    │                   │   ├── RateLimitResult.java
    │                   │   ├── TokenBucket.java
    │                   │   ├── SlidingWindowCounter.java
    │                   │   └── PartitionedRateLimiter.java
    │                   ├── extractor/
    │                   │   ├── KeyExtractor.java
    │                   │   └── KeyExtractorStrategy.java
    │                   ├── proxy/
    │                   │   ├── ReverseProxyServer.java
    │                   │   ├── InboundRateLimitHandler.java
    │                   │   ├── UpstreamClientPool.java
    │                   │   └── AdminHandler.java
    │                   ├── metrics/
    │                   │   ├── MetricsCollector.java
    │                   │   └── LatencyReservoir.java
    │                   └── benchmark/
    │                       ├── LoadHarness.java
    │                       └── BenchmarkResult.java
    └── test/
        └── java/
            └── com/
                └── kanak/
                    └── ratelimiter/
                        ├── TokenBucketTest.java
                        ├── SlidingWindowCounterTest.java
                        ├── ZeroOverAdmissionTest.java
                        ├── PartitionedRateLimiterTest.java
                        ├── KeyExtractorTest.java
                        └── ReverseProxyIntegrationTest.java
```

## 2. Phased Execution Steps

### Phase 1: Build Setup & Project Initialization
- Create `pom.xml` configured for Java 21 LTS with Maven Compiler Plugin, Shade Plugin (for standalone fat-jar executable), Surefire Plugin.
- Dependencies:
  - `io.netty:netty-all:4.1.112.Final` (NIO HTTP server, client, codec)
  - `com.fasterxml.jackson.core:jackson-databind:2.17.2` (JSON serialization)
  - `org.junit.jupiter:junit-jupiter:5.11.0` (Unit testing)
  - `org.assertj:assertj-core:3.26.3` (Fluent assertions)

### Phase 2: Core Rate Limiting Implementations
- `TokenBucket`: 64-bit atomic bit-packed token bucket using `AtomicLong` CAS loop.
- `SlidingWindowCounter`: Native direct off-heap memory ring buffer (`ByteBuffer.allocateDirect`) with slice epoch verification.
- `PartitionedRateLimiter`: Striped hash map with 256 cache-line padded shards to avoid false sharing.

### Phase 3: Extraction & Configuration Layer
- `KeyExtractor`: Robust client IP parsing (supporting `X-Forwarded-For`, `X-Real-IP`, direct remote address), API key extraction (`X-API-Key`, `Authorization: Bearer`).
- `ProxyConfig`: Flexible configuration loading with default rules.

### Phase 4: Netty Reverse Proxy Server & Handlers
- `ReverseProxyServer`: Netty bootstrap with master/worker event loops.
- `InboundRateLimitHandler`: Intercepts requests, checks rate limit, generates immediate HTTP 429 or forwards to upstream.
- `UpstreamClientPool`: Asynchronous upstream forwarding via Netty channels with response piping.
- `AdminHandler`: Handles `/_admin/health` and `/_admin/metrics`.

### Phase 5: Metrics & Benchmarking
- `MetricsCollector`: Lock-free `LongAdder` counters and `LatencyReservoir` for p50/p90/p99 latency calculation.
- `LoadHarness`: High-throughput concurrent benchmark client firing against local proxy.

### Phase 6: Verification & Test Execution
- Run `mvn clean test` covering 100% of test suites.
- Run `ZeroOverAdmissionTest`: Validate 10,000 requests against burst capacity 100 with refill 0 -> exactly 100 admitted, 9,900 rejected.
- Run standalone load benchmark verifying $\ge 50,000$ req/s and p99 $< 1$ ms.

### Phase 7: Docker, CI & Documentation
- Build multi-stage `Dockerfile`.
- Create `.github/workflows/ci.yml`.
- Write `README.md`, `CHANGELOG.md`, `docs/EXPLAIN.md`.
