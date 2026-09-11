package com.kanak.ratelimiter.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance off-heap Sliding Window Counter rate limiter.
 * <p>
 * Allocates native direct memory (ByteBuffer.allocateDirect) outside the JVM garbage collector heap.
 * Slices are stored in an off-heap circular ring buffer.
 * Each slice occupies 8 bytes manipulated atomically via VarHandle:
 * - High 32 bits: Relative Slice Epoch ID (1 + (timestamp - BOOT_EPOCH) / sliceDurationMillis)
 * - Low 32 bits:  Request count within the slice
 */
public final class SlidingWindowCounter implements RateLimiter {

    private static final VarHandle SLICE_VIEW =
            MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final long BOOT_EPOCH = System.currentTimeMillis();

    private final long limit;
    private final long windowMillis;
    private final int sliceCount;
    private final long sliceDurationMillis;
    private final ByteBuffer directBuffer;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Constructs an off-heap sliding window rate limiter.
     *
     * @param limit        Maximum allowed requests within the sliding window.
     * @param windowMillis Duration of the sliding window in milliseconds.
     * @param sliceCount   Number of discrete sub-window slices in the circular ring.
     */
    public SlidingWindowCounter(long limit, long windowMillis, int sliceCount) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be positive: " + limit);
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("Window millis must be positive: " + windowMillis);
        }
        if (sliceCount <= 0) {
            throw new IllegalArgumentException("Slice count must be positive: " + sliceCount);
        }

        this.limit = limit;
        this.windowMillis = windowMillis;
        this.sliceCount = sliceCount;
        this.sliceDurationMillis = Math.max(1L, windowMillis / sliceCount);

        // Allocate 8 bytes per slice off-heap
        int bufferBytes = sliceCount * Long.BYTES;
        this.directBuffer = ByteBuffer.allocateDirect(bufferBytes);
    }

    @Override
    public RateLimitResult tryAcquire(long tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Tokens to acquire must be positive: " + tokens);
        }
        if (closed.get()) {
            throw new IllegalStateException("SlidingWindowCounter has been closed");
        }

        while (true) {
            long now = System.currentTimeMillis();
            long currentEpoch = 1 + (now - BOOT_EPOCH) / sliceDurationMillis;
            int currentSlot = (int) (currentEpoch % (long) sliceCount);
            int targetByteOffset = currentSlot * Long.BYTES;

            // Read target slot first to get its current state
            long targetWord = (long) SLICE_VIEW.getVolatile(directBuffer, targetByteOffset);
            long targetSlotEpoch = (targetWord >>> 32) & 0xFFFFFFFFL;
            long targetSlotCount = targetWord & 0xFFFFFFFFL;

            // Aggregate counts across all active slices in the sliding window
            long totalCount = 0;
            long oldestActiveEpoch = Long.MAX_VALUE;

            for (int i = 0; i < sliceCount; i++) {
                int byteOffset = i * Long.BYTES;
                long sliceWord = (byteOffset == targetByteOffset)
                        ? targetWord
                        : (long) SLICE_VIEW.getVolatile(directBuffer, byteOffset);

                long sliceEpoch = (sliceWord >>> 32) & 0xFFFFFFFFL;
                long sliceCountVal = sliceWord & 0xFFFFFFFFL;

                if (sliceEpoch > 0) {
                    long epochDelta = currentEpoch - sliceEpoch;
                    if (epochDelta >= 0 && epochDelta < sliceCount) {
                        totalCount += sliceCountVal;
                        if (sliceEpoch < oldestActiveEpoch && sliceCountVal > 0) {
                            oldestActiveEpoch = sliceEpoch;
                        }
                    }
                }
            }

            // Check if adding tokens exceeds capacity
            if (totalCount + tokens > limit) {
                long retryAfterMillis;
                if (oldestActiveEpoch != Long.MAX_VALUE && oldestActiveEpoch <= currentEpoch) {
                    long oldestSliceExpiry = BOOT_EPOCH + (oldestActiveEpoch + sliceCount - 1) * sliceDurationMillis;
                    retryAfterMillis = Math.max(1L, oldestSliceExpiry - now);
                } else {
                    retryAfterMillis = sliceDurationMillis;
                }

                long resetEpochSec = (now + retryAfterMillis) / 1000L;
                return RateLimitResult.rejected(limit, retryAfterMillis, resetEpochSec);
            }

            long newSlotCount;
            if (targetSlotEpoch == (currentEpoch & 0xFFFFFFFFL)) {
                newSlotCount = targetSlotCount + tokens;
            } else {
                newSlotCount = tokens; // Previous epoch expired, reset count for new epoch
            }

            long newWord = ((currentEpoch & 0xFFFFFFFFL) << 32) | (newSlotCount & 0xFFFFFFFFL);

            // Attempt atomic CAS update on direct off-heap memory
            if (SLICE_VIEW.compareAndSet(directBuffer, targetByteOffset, targetWord, newWord)) {
                long remaining = Math.max(0, limit - (totalCount + tokens));
                long resetEpochSec = (now + windowMillis) / 1000L;
                return RateLimitResult.admitted(remaining, limit, resetEpochSec);
            }

            // CAS conflict: retry with fresh state
        }
    }

    @Override
    public long remainingTokens() {
        long now = System.currentTimeMillis();
        long currentEpoch = 1 + (now - BOOT_EPOCH) / sliceDurationMillis;
        long totalCount = 0;

        for (int i = 0; i < sliceCount; i++) {
            int byteOffset = i * Long.BYTES;
            long sliceWord = (long) SLICE_VIEW.getVolatile(directBuffer, byteOffset);
            long sliceEpoch = (sliceWord >>> 32) & 0xFFFFFFFFL;
            long sliceCountVal = sliceWord & 0xFFFFFFFFL;

            if (sliceEpoch > 0) {
                long epochDelta = currentEpoch - sliceEpoch;
                if (epochDelta >= 0 && epochDelta < sliceCount) {
                    totalCount += sliceCountVal;
                }
            }
        }
        return Math.max(0, limit - totalCount);
    }

    @Override
    public long capacity() {
        return limit;
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    public int getSliceCount() {
        return sliceCount;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (int i = 0; i < sliceCount; i++) {
                SLICE_VIEW.setVolatile(directBuffer, i * Long.BYTES, 0L);
            }
        }
    }
}
