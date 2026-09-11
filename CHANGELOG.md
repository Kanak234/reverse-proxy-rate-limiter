# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-09-11

### Added
- **High-Performance Netty Reverse Proxy Server:**
  - Non-blocking NIO HTTP/1.1 reverse proxy pipeline with transparent header forwarding and connection pooling.
  - Prefix-trie route matching supporting granular per-route rate limiting policies.
- **Lock-Free Bit-Packed Token Bucket Engine:**
  - Zero-heap allocation Token Bucket compressing available token balance (32 bits) and refill timestamp (32 bits) into a single atomic 64-bit word (`AtomicLong`).
  - Hardware-level compare-and-swap (`CAS`) atomic updates eliminating mutex lock contention.
  - Modulo-safe rollover arithmetic for millisecond timestamps.
- **Off-Heap Sliding Window Counter:**
  - Native direct memory circular ring buffer (`ByteBuffer.allocateDirect`) bypassing JVM heap and eliminating GC pauses.
  - Sub-window time slice tracking with atomic `VarHandle` view updates.
- **Cache-Line Striped Partitioning:**
  - 256 independent shards with 64-byte padding to eliminate L1/L2 cache false sharing across CPU cores under high concurrency.
  - Background daemon thread purging idle keys to bound memory footprint during spoofed IP attacks.
- **Multi-Dimensional Key Extraction:**
  - Client IP extraction supporting `X-Forwarded-For` proxy chains, `X-Real-IP`, and remote TCP socket addresses.
  - API key extraction from `X-API-Key` and `Authorization: Bearer <token>` headers.
  - Composite routing keys (`ROUTE_IP`, `ROUTE_API_KEY`, `GLOBAL`).
- **RFC 6585 Compliance:**
  - Returns standard HTTP `429 Too Many Requests` responses with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` headers.
- **Telemetry & Administration:**
  - Real-time `/_admin/health` and `/_admin/metrics` endpoints reporting request throughput, rejection rates, and p50/p90/p99/p99.9 latency percentiles.
  - Lock-free `LatencyReservoir` recording request durations in microseconds.
- **Benchmarking & Load Generator:**
  - Built-in multi-threaded load harness evaluating both end-to-end HTTP proxy throughput and raw in-memory microbenchmark rate.
  - Mathematical zero over-admission test suite verifying exact burst enforcement without leakage.
- **Containerization & CI:**
  - Multi-stage `Dockerfile` with minimal JRE runtime and Generational ZGC.
  - GitHub Actions automated CI workflow across Java 21 LTS.
