package com.statefulscanner.core;

import com.statefulscanner.model.ScanRequest;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded thread-safe queue of {@link ScanRequest} items, used as the hand-off
 * point between URL-producing producers and HTTP-consuming workers.
 *
 * <p>Backed by a {@link LinkedBlockingQueue} with a fixed capacity. Producers
 * call {@link #enqueue(ScanRequest)} (blocking), {@link #tryEnqueue(ScanRequest)}
 * (non-blocking), or {@link #tryEnqueue(ScanRequest, Duration)} (bounded wait).
 * Consumers call {@link #dequeue()} or {@link #tryDequeue(Duration)}.
 *
 * <h2>Capacity guidance</h2>
 * Pick capacity based on expected memory pressure and the desired backpressure
 * window — each {@code ScanRequest} is small (~200 bytes), so the default of
 * {@value #DEFAULT_CAPACITY} costs roughly 200 KB at full saturation. Increase
 * for very fast wordlists where producers should run ahead of consumers; lower
 * for tight memory budgets or to surface backpressure faster.
 *
 * <h2>Lifecycle</h2>
 * This queue does <strong>not</strong> implement an end-of-stream signal. When
 * producers finish, consumers blocking in {@link #dequeue()} will hang
 * indefinitely. The orchestrator is expected to terminate consumers explicitly,
 * either by interrupting their virtual threads (idiomatic for structured
 * concurrency / {@code StructuredTaskScope}) or by switching them to
 * {@link #tryDequeue(Duration)} once the producer side is known to have closed.
 *
 * <h2>Thread-safety</h2>
 * All operations are safe for concurrent use by virtual threads.
 * {@link LinkedBlockingQueue} uses {@link java.util.concurrent.locks.ReentrantLock}
 * internally, whose blocking methods do <em>not</em> pin the carrier thread on
 * Java 21+, so this class is safe for the project's all-virtual-threads model.
 * No {@code synchronized} blocks are used.
 */
public final class RequestQueue {

    /** Default capacity when none is specified. */
    public static final int DEFAULT_CAPACITY = 1024;

    private final BlockingQueue<ScanRequest> queue;
    private final int capacity;

    /** Creates a queue with {@link #DEFAULT_CAPACITY}. */
    public RequestQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity maximum number of items the queue can hold; must be positive
     * @throws IllegalArgumentException if {@code capacity <= 0}
     */
    public RequestQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Inserts the request, waiting indefinitely if the queue is full. This is the
     * primary mechanism for producer backpressure.
     *
     * @param request the request to enqueue (non-null)
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void enqueue(ScanRequest request) throws InterruptedException {
        Objects.requireNonNull(request, "request must not be null");
        queue.put(request);
    }

    /**
     * Inserts the request if space is immediately available.
     *
     * @param request the request to enqueue (non-null)
     * @return {@code true} if the request was enqueued; {@code false} if the queue is full
     */
    public boolean tryEnqueue(ScanRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return queue.offer(request);
    }

    /**
     * Inserts the request, waiting up to the given timeout if the queue is full.
     * A zero timeout is equivalent to {@link #tryEnqueue(ScanRequest)}.
     *
     * @param request the request to enqueue (non-null)
     * @param timeout maximum time to wait (non-null, non-negative); huge durations
     *                that would overflow nanoseconds are clamped to {@link Long#MAX_VALUE}
     *                nanoseconds (~292 years), which is effectively unbounded
     * @return {@code true} if the request was enqueued before the timeout elapsed
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean tryEnqueue(ScanRequest request, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        return queue.offer(request, toNanosClamped(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Retrieves and removes the head of the queue, waiting if necessary until an
     * item is available.
     *
     * @return the head of the queue
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public ScanRequest dequeue() throws InterruptedException {
        return queue.take();
    }

    /**
     * Retrieves and removes the head of the queue, waiting up to the given timeout.
     * A zero timeout returns immediately, equivalent to a non-blocking poll.
     *
     * @param timeout maximum time to wait (non-null, non-negative); huge durations
     *                that would overflow nanoseconds are clamped to {@link Long#MAX_VALUE}
     *                nanoseconds (~292 years)
     * @return the head of the queue, or {@code null} if the timeout elapsed before
     *         an item became available
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public ScanRequest tryDequeue(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        return queue.poll(toNanosClamped(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Drains all currently available requests into {@code target} without blocking.
     *
     * @param target destination collection (non-null)
     * @return the number of requests transferred
     */
    public int drainTo(Collection<? super ScanRequest> target) {
        Objects.requireNonNull(target, "target must not be null");
        return queue.drainTo(target);
    }

    /**
     * Drains up to {@code maxElements} requests into {@code target} without blocking.
     * Useful when consumers want to batch-process without risking unbounded growth
     * of the receiving collection.
     *
     * @param target      destination collection (non-null)
     * @param maxElements maximum number of items to transfer; must be non-negative.
     *                    A value of zero is a no-op and returns {@code 0}.
     * @return the number of requests transferred
     */
    public int drainTo(Collection<? super ScanRequest> target, int maxElements) {
        Objects.requireNonNull(target, "target must not be null");
        if (maxElements < 0) {
            throw new IllegalArgumentException(
                    "maxElements must not be negative: " + maxElements);
        }
        return queue.drainTo(target, maxElements);
    }

    /** @return the current number of queued requests. */
    public int size() {
        return queue.size();
    }

    /** @return the number of additional requests the queue can accept without blocking. */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    /** @return the configured maximum capacity. */
    public int capacity() {
        return capacity;
    }

    /** @return {@code true} if the queue currently holds no requests. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    private static long toNanosClamped(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException _) {
            return Long.MAX_VALUE;
        }
    }
}
