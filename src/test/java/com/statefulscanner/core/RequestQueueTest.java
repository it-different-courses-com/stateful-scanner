package com.statefulscanner.core;

import com.statefulscanner.model.ScanRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class RequestQueueTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            // ExecutorService.close() (Java 19+) waits for in-flight tasks to
            // finish, then shuts the executor down — adequate cleanup per the
            // project's "always close AutoCloseables in @AfterEach" rule.
            executor.close();
        }
    }

    private static ScanRequest sample(String entry) {
        return ScanRequest.of("https://example.com/" + entry, entry, "https://example.com");
    }

    /**
     * Asserts that the given future has not yet completed, by attempting a
     * short bounded wait and expecting it to time out. This is the canonical
     * sleep-free way to verify "still blocked": we never busy-wait, and we
     * never assume "fast enough" on a slow CI — if the future does complete
     * within the wait window, that itself is the failure signal.
     */
    private static void assertStillBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(100, TimeUnit.MILLISECONDS))
                .as("future should still be blocked")
                .isInstanceOf(TimeoutException.class);
    }

    @Nested
    class Construction {

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -100, Integer.MIN_VALUE})
        void constructor_withNonPositiveCapacity_throwsIllegalArgumentException(int capacity) {
            assertThatThrownBy(() -> new RequestQueue(capacity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        void defaultConstructor_usesDefaultCapacity() {
            RequestQueue queue = new RequestQueue();

            assertThat(queue.capacity()).isEqualTo(RequestQueue.DEFAULT_CAPACITY);
        }

        @Test
        void constructor_storesCapacity() {
            RequestQueue queue = new RequestQueue(7);

            assertThat(queue.capacity()).isEqualTo(7);
            assertThat(queue.remainingCapacity()).isEqualTo(7);
            assertThat(queue.size()).isZero();
            assertThat(queue.isEmpty()).isTrue();
        }
    }

    @Nested
    class BasicOperations {

        @Test
        void enqueue_thenDequeue_returnsSameRequest() throws Exception {
            RequestQueue queue = new RequestQueue(4);
            ScanRequest request = sample("admin");

            queue.enqueue(request);

            assertThat(queue.size()).isEqualTo(1);
            assertThat(queue.dequeue()).isEqualTo(request);
            assertThat(queue.isEmpty()).isTrue();
        }

        @Test
        void enqueue_preservesFifoOrder() throws Exception {
            RequestQueue queue = new RequestQueue(4);
            ScanRequest a = sample("a");
            ScanRequest b = sample("b");
            ScanRequest c = sample("c");

            queue.enqueue(a);
            queue.enqueue(b);
            queue.enqueue(c);

            assertThat(queue.dequeue()).isEqualTo(a);
            assertThat(queue.dequeue()).isEqualTo(b);
            assertThat(queue.dequeue()).isEqualTo(c);
        }

        @Test
        void enqueue_withNullRequest_throwsNullPointerException() {
            RequestQueue queue = new RequestQueue(2);

            assertThatThrownBy(() -> queue.enqueue(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void tryEnqueue_withNullRequest_throwsNullPointerException() {
            RequestQueue queue = new RequestQueue(2);

            assertThatThrownBy(() -> queue.tryEnqueue(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void tryEnqueueWithTimeout_withNullRequest_throwsNullPointerException() {
            RequestQueue queue = new RequestQueue(2);

            assertThatThrownBy(() -> queue.tryEnqueue(null, Duration.ofMillis(10)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void tryEnqueueWithTimeout_withNullTimeout_throwsNullPointerException() {
            RequestQueue queue = new RequestQueue(2);

            assertThatThrownBy(() -> queue.tryEnqueue(sample("a"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void tryDequeue_withNullTimeout_throwsNullPointerException() {
            RequestQueue queue = new RequestQueue(2);

            assertThatThrownBy(() -> queue.tryDequeue(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class NonBlockingOps {

        @Test
        void tryEnqueue_returnsTrueWhenSpaceAvailable() {
            RequestQueue queue = new RequestQueue(2);

            assertThat(queue.tryEnqueue(sample("a"))).isTrue();
        }

        @Test
        void tryEnqueue_returnsFalseWhenFull() {
            RequestQueue queue = new RequestQueue(1);
            queue.tryEnqueue(sample("a"));

            assertThat(queue.tryEnqueue(sample("b"))).isFalse();
            assertThat(queue.size()).isEqualTo(1);
        }

        @Test
        void tryEnqueueWithTimeout_negativeTimeout_throwsIllegalArgumentException() {
            RequestQueue queue = new RequestQueue(1);

            assertThatThrownBy(() -> queue.tryEnqueue(sample("a"), Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void tryEnqueueWithTimeout_returnsFalseWhenTimeoutElapses() throws Exception {
            RequestQueue queue = new RequestQueue(1);
            queue.enqueue(sample("a"));

            assertThat(queue.tryEnqueue(sample("b"), Duration.ofMillis(50))).isFalse();
            assertThat(queue.size()).isEqualTo(1);
        }

        @Test
        void tryEnqueueWithTimeout_zeroDurationOnFullQueue_returnsFalseImmediately() throws Exception {
            // Documented behaviour: a zero timeout is equivalent to a non-blocking
            // try. Verify it doesn't accidentally fall through to indefinite waiting
            // (e.g. a `0 -> Long.MAX_VALUE` clamp bug).
            RequestQueue queue = new RequestQueue(1);
            queue.enqueue(sample("a"));

            assertThat(queue.tryEnqueue(sample("b"), Duration.ZERO)).isFalse();
            assertThat(queue.size()).isEqualTo(1);
        }

        @Test
        void tryEnqueueWithTimeout_zeroDurationOnAvailableQueue_returnsTrue() throws Exception {
            RequestQueue queue = new RequestQueue(1);

            assertThat(queue.tryEnqueue(sample("a"), Duration.ZERO)).isTrue();
            assertThat(queue.size()).isEqualTo(1);
        }

        @Test
        void tryDequeue_returnsNullWhenEmptyAndTimeoutElapses() throws Exception {
            RequestQueue queue = new RequestQueue(1);

            assertThat(queue.tryDequeue(Duration.ofMillis(50))).isNull();
        }

        @Test
        void tryDequeue_negativeTimeout_throwsIllegalArgumentException() {
            RequestQueue queue = new RequestQueue(1);

            assertThatThrownBy(() -> queue.tryDequeue(Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void tryDequeue_returnsItemWhenAvailable() throws Exception {
            RequestQueue queue = new RequestQueue(1);
            ScanRequest a = sample("a");
            queue.enqueue(a);

            assertThat(queue.tryDequeue(Duration.ofMillis(10))).isEqualTo(a);
        }

        @Test
        void tryDequeue_zeroDurationOnEmptyQueue_returnsNullImmediately() throws Exception {
            RequestQueue queue = new RequestQueue(1);

            assertThat(queue.tryDequeue(Duration.ZERO)).isNull();
        }

        @Test
        void tryDequeue_zeroDurationOnAvailableQueue_returnsItem() throws Exception {
            RequestQueue queue = new RequestQueue(1);
            ScanRequest a = sample("a");
            queue.enqueue(a);

            assertThat(queue.tryDequeue(Duration.ZERO)).isEqualTo(a);
        }
    }

    @Nested
    class BlockingBehaviour {

        @Test
        void enqueue_blocksWhenFull_unblocksAfterDequeue() throws Exception {
            RequestQueue queue = new RequestQueue(1);
            queue.enqueue(sample("first"));

            executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch started = new CountDownLatch(1);

            Future<Void> producer = executor.submit(() -> {
                started.countDown();
                queue.enqueue(sample("second"));
                return null;
            });

            // Wait for the producer task to actually start running.
            started.await();
            // Verify the producer is still blocked. We use a bounded Future.get
            // expecting TimeoutException rather than a Thread.sleep + isDone()
            // pair: the bounded wait gives the producer ample time to either
            // complete (which would be the bug) or stay blocked (the correct
            // behaviour) without a fixed sleep.
            assertStillBlocked(producer);

            assertThat(queue.dequeue().wordlistEntry()).isEqualTo("first");
            // Unblocked: producer should now finish promptly.
            producer.get(1, TimeUnit.SECONDS);
            assertThat(queue.size()).isEqualTo(1);
            assertThat(queue.dequeue().wordlistEntry()).isEqualTo("second");
        }

        @Test
        void dequeue_blocksWhenEmpty_unblocksAfterEnqueue() throws Exception {
            RequestQueue queue = new RequestQueue(1);
            executor = Executors.newVirtualThreadPerTaskExecutor();

            CountDownLatch started = new CountDownLatch(1);
            Future<ScanRequest> consumer = executor.submit(() -> {
                started.countDown();
                return queue.dequeue();
            });

            started.await();
            assertStillBlocked(consumer);

            ScanRequest request = sample("late");
            queue.enqueue(request);

            assertThat(consumer.get(1, TimeUnit.SECONDS)).isEqualTo(request);
        }

        @Test
        void enqueue_propagatesInterrupt() throws Exception {
            RequestQueue queue = new RequestQueue(1);
            queue.enqueue(sample("blocker"));
            executor = Executors.newVirtualThreadPerTaskExecutor();

            CountDownLatch started = new CountDownLatch(1);
            // Capture the running thread so we can interrupt it directly. Cancelling
            // the Future works too, but ExecutorService.cancel() reports the task
            // as cancelled regardless of whether the body actually observed the
            // interrupt — which means the previous version of this test couldn't
            // distinguish "InterruptedException was thrown" from "task was just
            // cancelled before it ran". Capturing the thread + assertions on the
            // actual exception fixes that.
            AtomicReference<Thread> producerThread = new AtomicReference<>();
            Future<Void> producer = executor.submit(() -> {
                producerThread.set(Thread.currentThread());
                started.countDown();
                queue.enqueue(sample("blocked")); // expected to throw InterruptedException
                return null;
            });

            started.await();
            // Give the producer a window to actually enter put() and block. We
            // confirm "still blocked" via assertStillBlocked rather than a bare
            // sleep — and crucially, the resulting bounded wait both (a) proves
            // the thread is parked in put() and (b) gives put() time to register
            // its waiter before we deliver the interrupt.
            assertStillBlocked(producer);

            Thread t = producerThread.get();
            assertThat(t).as("producer thread should be captured by now").isNotNull();
            t.interrupt();

            // The producer task body must surface InterruptedException to the
            // caller — verify it does, rather than being silently swallowed.
            assertThatThrownBy(() -> producer.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InterruptedException.class);

            // The blocker holder is still queued; the interrupted enqueue was
            // never delivered, so size remains at the pre-interrupt state.
            assertThat(queue.size()).isEqualTo(1);
            assertThat(queue.dequeue().wordlistEntry()).isEqualTo("blocker");
        }
    }

    @Nested
    class Drain {

        @Test
        void drainTo_movesAllItems() {
            RequestQueue queue = new RequestQueue(4);
            queue.tryEnqueue(sample("a"));
            queue.tryEnqueue(sample("b"));
            queue.tryEnqueue(sample("c"));

            List<ScanRequest> drained = new ArrayList<>();
            int count = queue.drainTo(drained);

            assertThat(count).isEqualTo(3);
            assertThat(drained).extracting(ScanRequest::wordlistEntry)
                    .containsExactly("a", "b", "c");
            assertThat(queue.isEmpty()).isTrue();
        }

        @Test
        void drainTo_emptyQueue_returnsZero() {
            RequestQueue queue = new RequestQueue(4);
            List<ScanRequest> drained = new ArrayList<>();

            assertThat(queue.drainTo(drained)).isZero();
            assertThat(drained).isEmpty();
        }

        @Test
        void drainTo_withNullTarget_throwsNullPointerException() {
            RequestQueue queue = new RequestQueue(1);

            assertThatThrownBy(() -> queue.drainTo(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void drainTo_onPartiallyConsumedQueue_drainsRemainingItemsInOrder() throws Exception {
            // After a partial dequeue, drainTo must transfer the *remaining*
            // items in FIFO order — not all originally enqueued items, and not
            // in some arbitrary order. This guards against a regression where
            // drainTo accidentally snapshotted state at enqueue time.
            RequestQueue queue = new RequestQueue(4);
            queue.enqueue(sample("a"));
            queue.enqueue(sample("b"));
            queue.enqueue(sample("c"));
            queue.enqueue(sample("d"));

            assertThat(queue.dequeue().wordlistEntry()).isEqualTo("a");
            assertThat(queue.dequeue().wordlistEntry()).isEqualTo("b");

            List<ScanRequest> drained = new ArrayList<>();
            int count = queue.drainTo(drained);

            assertThat(count).isEqualTo(2);
            assertThat(drained).extracting(ScanRequest::wordlistEntry)
                    .containsExactly("c", "d");
            assertThat(queue.isEmpty()).isTrue();
        }

        @Test
        void drainToBounded_movesAtMostMaxItems() {
            // Architect-added bounded variant: cap how many items the consumer
            // pulls at once. Verify the cap is honoured AND the remaining items
            // stay in the queue in their original order.
            RequestQueue queue = new RequestQueue(8);
            queue.tryEnqueue(sample("a"));
            queue.tryEnqueue(sample("b"));
            queue.tryEnqueue(sample("c"));
            queue.tryEnqueue(sample("d"));
            queue.tryEnqueue(sample("e"));

            List<ScanRequest> drained = new ArrayList<>();
            int count = queue.drainTo(drained, 3);

            assertThat(count).isEqualTo(3);
            assertThat(drained).extracting(ScanRequest::wordlistEntry)
                    .containsExactly("a", "b", "c");
            assertThat(queue.size()).isEqualTo(2);
        }

        @Test
        void drainToBounded_withMaxLargerThanQueue_drainsEverything() {
            RequestQueue queue = new RequestQueue(4);
            queue.tryEnqueue(sample("a"));
            queue.tryEnqueue(sample("b"));

            List<ScanRequest> drained = new ArrayList<>();
            int count = queue.drainTo(drained, 100);

            assertThat(count).isEqualTo(2);
            assertThat(drained).hasSize(2);
            assertThat(queue.isEmpty()).isTrue();
        }

        @Test
        void drainToBounded_withZeroMax_isNoop() {
            RequestQueue queue = new RequestQueue(4);
            queue.tryEnqueue(sample("a"));
            queue.tryEnqueue(sample("b"));

            List<ScanRequest> drained = new ArrayList<>();
            int count = queue.drainTo(drained, 0);

            assertThat(count).isZero();
            assertThat(drained).isEmpty();
            assertThat(queue.size()).isEqualTo(2);
        }

        @Test
        void drainToBounded_withNegativeMax_throwsIllegalArgumentException() {
            RequestQueue queue = new RequestQueue(4);
            List<ScanRequest> drained = new ArrayList<>();

            assertThatThrownBy(() -> queue.drainTo(drained, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        void drainToBounded_withNullTarget_throwsNullPointerException() {
            RequestQueue queue = new RequestQueue(4);

            assertThatThrownBy(() -> queue.drainTo(null, 5))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Concurrency {

        @Test
        void concurrentProducersAndConsumers_deliverEachRequestExactlyOnce() throws Exception {
            // "Exactly once" requires both: every enqueued request is delivered
            // (no losses) AND no request is delivered more than once (no
            // duplicates). The previous version of this test could only catch
            // duplicates if the duplicate happened to push consumedCount past
            // total — borderline. Switching the dedup key to ScanRequest.id()
            // (a UUID generated per-request, guaranteed unique by construction)
            // means the Set's final size precisely measures unique deliveries,
            // and any duplicate delivery shows up immediately as a Set size
            // smaller than the enqueued total.
            int producerCount = 8;
            int consumerCount = 8;
            int perProducer = 500;
            int total = producerCount * perProducer;

            RequestQueue queue = new RequestQueue(64);
            executor = Executors.newVirtualThreadPerTaskExecutor();

            Set<UUID> consumedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
            AtomicInteger consumedCount = new AtomicInteger();

            List<Future<?>> consumers = new ArrayList<>();
            for (int i = 0; i < consumerCount; i++) {
                consumers.add(executor.submit(() -> {
                    while (consumedCount.get() < total) {
                        ScanRequest req = queue.tryDequeue(Duration.ofMillis(100));
                        if (req != null) {
                            consumedIds.add(req.id());
                            consumedCount.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }

            List<Future<?>> producers = new ArrayList<>();
            for (int i = 0; i < producerCount; i++) {
                final int producerIndex = i;
                producers.add(executor.submit(() -> {
                    for (int j = 0; j < perProducer; j++) {
                        queue.enqueue(sample("p" + producerIndex + "-" + j));
                    }
                    return null;
                }));
            }

            for (Future<?> f : producers) {
                f.get(3, TimeUnit.SECONDS);
            }
            for (Future<?> f : consumers) {
                f.get(3, TimeUnit.SECONDS);
            }

            // No losses: every produced request was observed.
            assertThat(consumedCount.get()).isEqualTo(total);
            // No duplicates: every observed id was unique.
            assertThat(consumedIds).hasSize(total);
            assertThat(queue.isEmpty()).isTrue();
        }

        @Test
        void backpressure_producerBlocksWhenQueueFull() throws Exception {
            int capacity = 4;
            int totalToEnqueue = 100;
            RequestQueue queue = new RequestQueue(capacity);
            executor = Executors.newVirtualThreadPerTaskExecutor();

            Future<?> producer = executor.submit(() -> {
                for (int i = 0; i < totalToEnqueue; i++) {
                    queue.enqueue(sample("e" + i));
                }
                return null;
            });

            // Drain slowly; queue.size() should never exceed capacity.
            int observedMax = 0;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < totalToEnqueue; i++) {
                observedMax = Math.max(observedMax, queue.size());
                ScanRequest req = queue.dequeue();
                seen.add(req.wordlistEntry());
            }

            producer.get(2, TimeUnit.SECONDS);
            assertThat(observedMax).isLessThanOrEqualTo(capacity);
            assertThat(seen).hasSize(totalToEnqueue);
            assertThat(queue.isEmpty()).isTrue();
        }

        @Test
        void concurrentEnqueueAndDrainTo_yieldsConsistentTotal() throws Exception {
            // Interleave one producer with periodic drainTo() calls and verify
            // every produced request is accounted for — either drained out or
            // still resident in the queue at the end. This catches races where
            // drainTo might double-count or skip an item that's mid-handoff.
            int totalToEnqueue = 2_000;
            RequestQueue queue = new RequestQueue(64);
            executor = Executors.newVirtualThreadPerTaskExecutor();

            Future<?> producer = executor.submit(() -> {
                for (int i = 0; i < totalToEnqueue; i++) {
                    queue.enqueue(sample("e" + i));
                }
                return null;
            });

            List<ScanRequest> drained = new ArrayList<>();
            // Use bounded drain to exercise the (Collection, int) variant under
            // concurrency, alongside the producer.
            while (!producer.isDone() || !queue.isEmpty()) {
                queue.drainTo(drained, 32);
            }
            // Final drain to clean up anything enqueued between the last loop
            // iteration and the producer-done check.
            queue.drainTo(drained);

            producer.get(3, TimeUnit.SECONDS);
            assertThat(drained).hasSize(totalToEnqueue);
            assertThat(drained).extracting(ScanRequest::wordlistEntry).doesNotHaveDuplicates();
            assertThat(queue.isEmpty()).isTrue();
        }
    }
}
