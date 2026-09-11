# Technical Deep-Dive: Lock-Free Rate Limiting & Off-Heap Memory Architecture

## 1. The Bottleneck of Traditional Rate Limiters

Traditional rate limiters in enterprise Java applications suffer from three fatal bottlenecks under high-concurrency L7 attack traffic:

1. **Lock Contention (`synchronized` / `ReentrantLock`):**
   When hundreds of worker threads service incoming HTTP requests for the same IP or API key, mutex locks force threads to block, enter OS kernel wait queues, and incur expensive context switches ($\sim 1\text{--}5\ \mu\text{s}$ per switch).
2. **JVM Garbage Collection (GC) Pauses:**
   Creating small state objects (e.g., `TimestampedRequest`, `TokenNode`, `WindowBucket`) on the JVM heap for every request generates gigabytes of short-lived heap allocations per second at line rate ($100\text{k req/s}$). This overwhelms young generation garbage collection, causing Stop-The-World (STW) pauses.
3. **Cache-Line False Sharing:**
   When multiple CPU cores write to adjacent variables residing on the same 64-byte CPU cache line, the L1/L2 cache coherency protocol (MESI/MOESI) forces constant cache line invalidations across cores, degrading multi-threaded throughput by up to $10\times$.

`reverse-proxy-rate-limiter` overcomes all three limitations through **Atomic Bit-Packing**, **Off-Heap Direct Memory Buffers**, and **Striped Cache-Line Isolation**.

---

## 2. Lock-Free Atomic Bit-Packing

### 2.1 The 64-Bit State Word

A Token Bucket requires tracking two mutable state variables:
1. Current token count ($T$).
2. Timestamp of last refill ($t_{\text{last}}$).

Normally, updating both variables consistently requires either a lock or wrapping them in an immutable tuple object with an `AtomicReference` (which causes heap allocation on every update).

Instead, we compress both fields into a single primitive 64-bit `long`:

```
Bit:  63                            32 31                             0
      +-------------------------------+-------------------------------+
      |    Available Tokens (32-bit)  |   Last Timestamp Millis (32)  |
      +-------------------------------+-------------------------------+
```

- **Tokens (Bits 63..32):**
  Unsigned 32-bit integer holding up to $4,294,967,295$ tokens.
  Extraction: `int tokens = (int) (word >>> 32);`
- **Timestamp (Bits 31..0):**
  Unsigned 32-bit integer representing milliseconds elapsed since boot epoch ($t_0$).
  $2^{32} \text{ ms} \approx 49.71 \text{ days}$ of continuous operation before rollover.
  Extraction: `int lastTime = (int) (word & 0xFFFFFFFFL);`

### 2.2 Atomic Mutation via Hardware CAS Loop

When an incoming request arrives at time $t_{\text{now}}$:
1. Read current 64-bit word: `long current = word.get();`
2. Extract $T$ and $t_{\text{last}}$.
3. Compute elapsed time with rollover-safe arithmetic:
   $$\Delta t = (t_{\text{now}} - t_{\text{last}}) \ \& \ \text{0xFFFFFFFFL}$$
4. Calculate replenished tokens:
   $$\Delta T = \left\lfloor \frac{\Delta t \times R}{1000} \right\rfloor$$
   $$T_{\text{new}} = \min(C, T + \Delta T)$$
5. Check admission:
   - If $T_{\text{new}} < 1$: request is rejected (429). Calculate `retryAfter` based on missing fractional tokens.
   - If $T_{\text{new}} \ge 1$: consume 1 token ($T_{\text{next}} = T_{\text{new}} - 1$).
6. Pack new state:
   $$\text{nextWord} = (T_{\text{next}} \ll 32) \ | \ (t_{\text{now}} \ \& \ \text{0xFFFFFFFFL})$$
7. Perform hardware CAS (`compareAndSet(current, nextWord)`). If CAS succeeds, admit request. If CAS fails (another thread updated the bucket in the interim), retry the loop with the fresh state.

**Complexity:** $O(1)$ time, $0$ bytes heap allocation, $0$ OS context switches.

---

## 3. Off-Heap Sliding Window Counter

### 3.1 Memory Layout

For sliding window policies, tracking individual timestamp logs on the heap is memory-prohibitive. We implement an off-heap ring buffer divided into $N$ discrete sub-second slices.

```
Direct Off-Heap Native Memory (ByteBuffer.allocateDirect)
+-------------------+-------------------+-------------------+-----+
| Slice 0 (8 bytes) | Slice 1 (8 bytes) | Slice 2 (8 bytes) | ... |
| [Epoch:4][Cnt:4]  | [Epoch:4][Cnt:4]  | [Epoch:4][Cnt:4]  | ... |
+-------------------+-------------------+-------------------+-----+
```

- Each slice occupies exactly 8 bytes:
  - 4 bytes: `epochId = timestamp / sliceDurationMillis`
  - 4 bytes: `requestCount`
- Ring size: $N = \text{windowDuration} / \text{sliceDuration}$.
- Target slot: $\text{slot} = \text{epochId} \pmod N$.

### 3.2 Rolling Aggregation

To evaluate whether a request at time $t$ is allowed:
1. Identify the current epoch ID $E = \lfloor t / S \rfloor$.
2. Traverse the $N$ slots in the direct buffer.
3. For each slot, read `epochId`. If $E - \text{epochId} < N$ (the slice is still within the sliding window $[t - W, t]$), accumulate `requestCount`.
4. If $\text{totalCount} + 1 \le \text{Limit}$:
   - In slot $\text{slot} = E \pmod N$:
     - If slot's `epochId` matches $E$, atomically increment `requestCount`.
     - If slot's `epochId` is older, overwrite `epochId = E` and reset `requestCount = 1`.
   - Admit request.
5. If $\text{totalCount} + 1 > \text{Limit}$: reject request with 429.

Because the buffer resides in off-heap memory outside the JVM garbage collector's scope, high-throughput updates do not trigger GC sweeps.

---

## 4. Cache-Line False Sharing & Striped Partitioning

When multiple processor cores concurrently access adjacent elements in a hash map, their cache lines conflict. A single cache line on x86-64 is 64 bytes.

To eliminate false sharing:
1. The global rate limiter divides keys into $K = 256$ independent shards.
2. Each shard is padded to 64 bytes:
   ```java
   class PaddedShard {
       long p1, p2, p3, p4, p5, p6, p7; // 56 bytes padding
       final ConcurrentHashMap<String, RateLimiter> map = new ConcurrentHashMap<>();
       long p8, p9, p10, p11, p12, p13, p14; // 56 bytes trailing padding
   }
   ```
3. Shard routing uses a fast bitwise mixing function:
   ```java
   int hash = key.hashCode();
   int shardIndex = (hash ^ (hash >>> 16)) & (NUM_SHARDS - 1);
   ```
4. This ensures that threads modifying different keys on different cores operate on disjoint cache lines without cache-coherence bus invalidation penalties.

---

## 5. Mathematical Proof of Zero Over-Admission

Let $C \in \mathbb{N}$ be the configured burst capacity, $R \in \mathbb{R}^+$ be the refill rate in tokens/sec, and $t_0$ be the initial time with $T(t_0) = C$.

Let $m$ concurrent worker threads issue $M \gg C$ requests within an infinitesimal interval $\Delta t \to 0$ where $R \cdot \Delta t < 1$.

**Invariant:** At any time $t$, the total number of admitted requests $A(t)$ must satisfy:
$$A(t) \le C + \int_{t_0}^t R(\tau) \, d\tau$$

Under atomic CAS updates:
1. Every successful admission requires a distinct, non-overlapping transition $T_{k} \to T_{k} - 1$ where $T_k \ge 1$.
2. The initial state is $T_0 = C$.
3. Because $\Delta t \to 0$, token replenishment $\Delta T = 0$.
4. The sequence of states is strictly monotonically decreasing: $C \to C-1 \to C-2 \to \dots \to 1 \to 0$.
5. When $T = 0$, all subsequent CAS attempts fail the condition $T_{\text{refreshed}} \ge 1$, and return `rejected`.
6. Therefore, exactly $C$ requests transition the state, yielding $A(t_0 + \Delta t) = C$.
7. Zero over-admission holds strictly: $A \ngtr C$.
