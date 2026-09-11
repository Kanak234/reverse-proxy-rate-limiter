# Technical Requirements Document (TRD)

## Project: `reverse-proxy-rate-limiter`
**Project ID:** P170  
**Language & Runtime:** Java 21 LTS  
**Build System:** Apache Maven (v3.9+)  
**Author:** Kanak Prabhakar (`Kanak234`)  

---

## 1. System Architecture Overview

`reverse-proxy-rate-limiter` is structured as a modular, reactive, non-blocking L7 reverse proxy. The system consists of five primary subsystems:

```
                  +----------------------------------------------+
                  |            Incoming Client Traffic           |
                  +----------------------------------------------+
                                         | HTTP/1.1
                                         v
                  +----------------------------------------------+
                  |         Netty Inbound Network Pipeline       |
                  |     - SSL Offload / TCP Socket Reader        |
                  |     - HTTP Request Decoder & Aggregator      |
                  +----------------------------------------------+
                                         |
                                         v
                  +----------------------------------------------+
                  |         Rule Matcher & Key Extractor         |
                  |     - Route Prefix Trie (O(k) matching)      |
                  |     - Key: IP / API Key / Route Composite    |
                  +----------------------------------------------+
                                         |
                       +-----------------+-----------------+
                       | Rate Limit Check                  |
                       v                                   v
             [Admitted (< Limit)]               [Rejected (>= Limit)]
                       |                                   |
                       v                                   v
        +-----------------------------+     +-----------------------------+
        |  Async Upstream Client Pool |     | Fast-Path 429 Handler       |
        |  - Non-blocking Forwarding  |     | - RFC 6585 Headers          |
        |  - Header Injection         |     | - Retry-After Calculation   |
        |  - Streaming Back to Client |     | - Immediate TCP Flush       |
        +-----------------------------+     +-----------------------------+
                       |                                   |
                       +-----------------+-----------------+
                                         |
                                         v
                  +----------------------------------------------+
                  |          Telemetry & Metrics Engine          |
                  |  - Lock-free Latency Histogram (p50/p90/p99) |
                  |  - JMX & HTTP /_admin/metrics Exporter       |
                  +----------------------------------------------+
```

---

## 2. Core Rate Limiting Algorithms

### 2.1 Lock-Free Bit-Packed Token Bucket

To achieve line-rate performance ($\ge 100\text{k req/sec}$) without garbage collection overhead or synchronization mutexes, the Token Bucket algorithm packs the bucket's state into a single 64-bit primitive integer (`long`).

#### 64-Bit Bit-Packing Specification:
```
+-----------------------------------+-----------------------------------+
|       Tokens (32 bits)            |      Timestamp Millis (32 bits)   |
|   Bits 63..32 (Unsigned int)      |       Bits 31..0 (Unsigned int)   |
+-----------------------------------+-----------------------------------+
```
- **Bits 63..32 (Tokens):** Represents current available token balance. A 32-bit unsigned field supports up to $4,294,967,295$ tokens.
- **Bits 31..0 (Timestamp Millis):** Milliseconds elapsed since the rate limiter epoch (`System.currentTimeMillis() - BOOT_EPOCH`). The 32-bit unsigned millisecond space provides a contiguous cycle of $49.71$ days before rollover. Rollover is handled using modulo delta arithmetic:
  $$\Delta t = (t_{\text{now}} - t_{\text{last}}) \ \& \ \text{0xFFFFFFFFL}$$
- **Atomic Mutation via CAS:**
  ```java
  long currentWord = bucketWord.get();
  while (true) {
      int tokens = (int) (currentWord >>> 32);
      int lastTime = (int) (currentWord & 0xFFFFFFFFL);
      long deltaMillis = (currentTime & 0xFFFFFFFFL) - (lastTime & 0xFFFFFFFFL);
      if (deltaMillis < 0) deltaMillis += 0x100000000L;
      
      long tokensToAdd = (deltaMillis * refillRatePerSec) / 1000L;
      long refreshedTokens = Math.min(capacity, (long) tokens + tokensToAdd);
      
      if (refreshedTokens < 1) {
          return RateLimitResult.rejected(retryAfterMillis(refreshedTokens, refillRatePerSec));
      }
      
      long nextTokens = refreshedTokens - 1;
      long nextWord = (nextTokens << 32) | (currentTime & 0xFFFFFFFFL);
      if (bucketWord.compareAndSet(currentWord, nextWord)) {
          return RateLimitResult.admitted(nextTokens, capacity);
      }
      currentWord = bucketWord.get();
  }
  ```
- **Benefits:**
  - Zero memory allocation per rate-limit check.
  - Zero lock contention or thread context switches.
  - Guaranteed atomic update of both token balance and elapsed time.

---

### 2.2 Off-Heap Sliding Window Counter (Foreign Function & Memory API)

For strict time-window policies where burst spikes within sub-intervals must be smoothed across a sliding historical window (e.g. max 1,000 requests per sliding 60 seconds):
- **Storage:** Off-heap circular buffer allocated via Java 21 `java.lang.foreign.Arena.ofShared().allocate(...)` or direct `ByteBuffer`.
- **Ring Layout:**
  - Window Duration: $W$ (e.g., 60,000 ms)
  - Slice Resolution: $S$ (e.g., 1,000 ms, total $N = W/S = 60$ slices)
  - Each slice consists of 8 bytes:
    - 4 bytes: Slice Epoch ID (`sliceIndex`)
    - 4 bytes: Request Count (`count`)
- **Aggregation:**
  - On each request, determine current slice index $k = (t / S) \pmod N$.
  - Atomic CAS increment of current slice count if current slice epoch matches; otherwise reset count to 1 for the new epoch.
  - Sum all valid slices whose timestamps fall within $[t - W, t]$.
  - Zero GC footprint: native memory resides entirely outside the JVM heap.

---

### 2.3 Partitioned Striped Bucketing Table

To eliminate cache-line false sharing and thread contention across multiple CPU cores:
- Buckets are organized into $K = 256$ independent shards.
- Each shard contains its own concurrent map with cache-line padding (64 bytes) to isolate L1/L2/L3 cache coherence invalidations.
- Shard selection:
  $$\text{shardIndex} = (\text{hash}(key) \oplus (\text{hash}(key) \ggg 16)) \ \& \ (K - 1)$$

---

## 3. Network & Proxy Pipeline (Netty 4.1)

### 3.1 Server Pipeline Architecture
- **Worker Thread Model:** Multi-threaded event loop (`NioEventLoopGroup`) sized to `Runtime.getRuntime().availableProcessors() * 2`.
- **Channel Pipeline:**
  1. `HttpServerCodec`: Decodes raw TCP bytes into HTTP request objects and encodes outgoing HTTP responses.
  2. `HttpObjectAggregator`: Aggregates chunked HTTP bodies up to 10 MB.
  3. `RateLimiterInboundHandler`:
     - Inspects request method, URI, headers (`X-Forwarded-For`, `X-Real-IP`, `X-API-Key`).
     - Extracts rate limiting key.
     - Resolves matching policy.
     - Executes lock-free rate limiter.
     - If rejected: immediately crafts HTTP 429 response with RFC 6585 headers and flushes channel.
     - If admitted: forwards request to upstream client pool.

### 3.2 Upstream Client Forwarding
- Asynchronous HTTP client connection pool (`ChannelPoolMap`) targeting backend service (`127.0.0.1:8081`).
- Transparent header pass-through:
  - Preserves `Host`, `User-Agent`, `Accept`, `Content-Type`, `Content-Length`.
  - Injects `X-Forwarded-For`, `X-Forwarded-Proto`, and Rate Limit response headers:
    - `X-RateLimit-Limit: <capacity>`
    - `X-RateLimit-Remaining: <remaining>`
    - `X-RateLimit-Reset: <epoch_seconds>`

---

## 4. Telemetry & Administrative API

1. **Metrics Collector:**
   - Lock-free `LongAdder` counters for total requests, admitted requests, rejected requests, upstream errors.
   - Padded lock-free reservoir for sub-millisecond request latency tracking (p50, p90, p99, p99.9, max).
2. **Admin HTTP Endpoints:**
   - `GET /_admin/health`: HTTP 200 `{"status": "UP"}`
   - `GET /_admin/metrics`: HTTP 200 JSON report
   - `POST /_admin/rules`: Dynamic registration of rate limit rules

---

## 5. Verification & Testing Strategy

1. **Unit Tests:**
   - `TokenBucketTest`: Bit-packing correctness, token replenishment rate, epoch rollover safety, single-threaded correctness.
   - `SlidingWindowTest`: Off-heap direct memory management, slice eviction, rolling window calculation.
   - `RuleEngineTest`: Route prefix pattern matching, IP extraction from headers, API key extraction.
2. **Concurrency & Zero Over-Admission Verification:**
   - `ZeroOverAdmissionTest`: Configure bucket with capacity $C = 100$, refill rate $R = 0$. Launch 32 worker threads firing a total of 10,000 requests simultaneously. Assert that admitted count is **exactly 100** and rejected count is **exactly 9,900**.
3. **End-to-End Reverse Proxy Integration:**
   - Embedded upstream HTTP server responding to test endpoints (`/api/test`, `/api/health`).
   - Reverse proxy running on random ephemeral port.
   - Client issuing real HTTP requests via `java.net.http.HttpClient`:
     - Verifying 200 OK responses with matching upstream payloads.
     - Verifying 429 Too Many Requests upon exceeding burst limit.
     - Verifying injection of RFC 6585 headers.
4. **Synthetic High-Throughput Load Benchmark:**
   - Built-in multi-threaded HTTP benchmarking harness measuring raw requests/sec and latency percentiles.
