# Product Requirements Document (PRD)

## Project: `reverse-proxy-rate-limiter`
**Project ID:** P170  
**Domain:** Defensive Cybersecurity / Distributed Systems  
**Primary Language:** Java 21 LTS (Maven)  
**Author:** Kanak Prabhakar (`Kanak234`)  

---

## 1. Executive Summary & Problem Statement

Modern web applications and distributed APIs are increasingly subject to Layer 7 (L7) HTTP flood Distributed Denial of Service (DDoS) attacks, brute-force credential stuffing, scraping automation, and API resource exhaustion. Traditional application-level rate limiting written inside monolithic frameworks introduces excessive garbage collection (GC) pauses, thread contention under lock contention, and high latency overhead.

`reverse-proxy-rate-limiter` is an industrial-grade, ultra-high-throughput reverse proxy and perimeter defense engine built in Java 21 LTS. It sits between external clients and backend microservices, intercepting incoming HTTP requests and enforcing fine-grained rate limits at wire speed. By using lock-free atomic bit-packing and off-heap memory-mapped circular buffers, the engine eliminates JVM garbage collection pressure on the hot path, sustaining over 100,000 requests per second with sub-millisecond p99 latency while guaranteeing mathematical zero over-admission.

---

## 2. Product Goals & Non-Goals

### 2.1 Goals
1. **High-Throughput Inline Reverse Proxy:** Intercept and proxy HTTP/1.1 client traffic to backend upstream services with transparent header forwarding and zero unnecessary buffer allocations.
2. **Dual Core Rate-Limiting Algorithms:**
   - **Lock-Free Bit-Packed Token Bucket:** Atomically updates token balances and timestamps in a single 64-bit word CAS loop, supporting smooth replenishment and burst capacities without mutexes.
   - **Off-Heap Sliding Window Counter:** Granular sub-window time slices stored in direct off-heap native memory buffers, calculating rolling request rates without GC pauses.
3. **Multi-Dimensional Partitioned Bucketing:** Sharded bucket partitioning preventing lock contention and cache-line false sharing across high-cardinality keys (Client IP, API Key, Route prefix).
4. **RFC 6585 Compliance:** Returns standard HTTP `429 Too Many Requests` responses with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` headers.
5. **Deterministic Zero Over-Admission:** Mathematical guarantee that under burst traffic exceeding bucket capacity, not a single excess request is admitted into upstream backends.
6. **Embedded Microbenchmark & Verification Harness:** High-concurrency synthetic load generator measuring raw throughput (req/sec), p50, p90, and p99 latency percentiles, and verifying over-admission bounds.

### 2.2 Non-Goals
- Full HTTP/2 and HTTP/3 multiplexing proxying (HTTP/1.1 pipelining and persistent connections are prioritized for raw proxy performance).
- TLS termination (designed to operate behind cloud load balancers or edge CDN ingress like AWS ALB or Cloudflare, or alongside internal TLS offloaders).
- Distributed cluster coordination via Raft or Redis (the proxy focuses on line-rate node-local protection; multi-node coordination is handled via partitioned consistent hashing at the load balancer layer).

---

## 3. Target Users & Personas

1. **Site Reliability & Infrastructure Engineers (SREs):** Deploying lightweight edge proxies to defend vulnerable backend services against sudden traffic spikes, botnets, and API abuse.
2. **Security Operations (SecOps):** Enforcing strict rate thresholds on critical authentication routes (`/api/v1/login`, `/oauth/token`) to block credential stuffing attacks.
3. **Backend API Platform Teams:** Establishing multi-tier rate limiting policies (free vs premium API keys) with standardized rate-limiting response headers.

---

## 4. Functional Requirements

### 4.1 Traffic Interception & Forwarding
- Accept inbound HTTP connections on a configurable port (default: `8080`).
- Extract request metadata:
  - Client IP: resolved from TCP socket or trusted proxy headers (`X-Forwarded-For`, `X-Real-IP`).
  - API Key: extracted from `X-API-Key` or `Authorization: Bearer <token>` headers.
  - Request Path & Method: URI routing prefix (`/api/v1/...`).
- If allowed: forward the request payload and headers to configured upstream backend target (`http://localhost:8081`).
- Inject rate-limit telemetry headers into both allowed upstream responses and rate-limited error responses.

### 4.2 Rate Limiting Policies & Rules
- Support route-specific rule configurations:
  - Route pattern (exact match or prefix, e.g., `/api/v1/auth/**`, `/api/v1/data/**`).
  - Key extractor strategy: `IP`, `API_KEY`, `ROUTE_IP`, or `GLOBAL`.
  - Rate limiting algorithm: `TOKEN_BUCKET` or `SLIDING_WINDOW`.
  - Burst capacity: maximum tokens allowed in a burst ($C$).
  - Refill rate: tokens replenished per second ($R$).
  - Sliding window duration and slice granularity (e.g. 60-second window with 1-second slices).

### 4.3 Rate Limit Violation Response
When a client exceeds their allowance:
- Drop upstream forwarding immediately.
- Respond with HTTP `429 Too Many Requests`.
- Set headers:
  - `Retry-After: <seconds>`
  - `X-RateLimit-Limit: <capacity>`
  - `X-RateLimit-Remaining: 0`
  - `X-RateLimit-Reset: <epoch_seconds>`
- Payload: JSON error structure:
  ```json
  {
    "status": 429,
    "error": "Too Many Requests",
    "message": "Rate limit exceeded. Please retry after specified interval.",
    "retryAfterSeconds": 2
  }
  ```

### 4.4 Health & Administrative Endpoints
- `GET /_admin/health`: Returns HTTP 200 `{"status": "UP"}`.
- `GET /_admin/metrics`: Returns JSON statistics including:
  - Total requests received
  - Admitted requests forwarded to upstream
  - Rejected 429 requests
  - Active rate limit keys tracked in memory
  - Upstream backend response status breakdown
  - Latency percentiles: p50, p90, p99, max (in microseconds)

---

## 5. Non-Functional Requirements & Performance Constraints

| Metric | Target Specification |
| :--- | :--- |
| Single-Node Throughput | $\ge 50,000$ req/sec sustained locally |
| p99 Latency Overhead | $< 1.0$ ms proxy evaluation overhead |
| Over-Admission Invariant | **Exactly 0** excess requests admitted beyond burst threshold |
| Memory Management | Off-heap direct buffers for sliding window logs, zero GC allocation on rate limit check |
| Concurrency Safety | Lock-free CAS operations across all hot paths |

---

## 6. Acceptance Criteria

1. **Test Suite:** 100% test pass rate across unit, concurrency, integration, and load test suites.
2. **Burst Over-Admission Test:** Configure bucket with capacity 100 and refill 0; issue 10,000 concurrent requests across 32 threads. Exactly 100 must receive 200 OK; exactly 9,900 must receive 429.
3. **Refill Rate Recovery:** Configure bucket with refill rate 10 tokens/sec. After depletion, verify tokens regenerate smoothly over time.
4. **End-to-End Proxy Verification:** Full proxy round-trip from client -> proxy -> backend mock server -> client passes all HTTP headers and body bytes unmodified.
5. **Off-Heap Cleanup:** Explicit off-heap memory deallocation and ring buffer recycling with zero memory leaks.
6. **Docker & CI:** Clean multi-stage build running tests and producing standalone runnable container.
