package com.statefulscanner.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutorConfigTest {

    private ExecutorConfig config;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        config = new ExecutorConfig();
        executor = config.virtualThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void virtualThreadExecutor_whenCalled_returnsVirtualThreadCapableExecutor() throws Exception {
        Future<Boolean> probe = executor.submit(() -> Thread.currentThread().isVirtual());

        assertThat(probe.get(2, TimeUnit.SECONDS))
                .as("Executor must spawn virtual threads")
                .isTrue();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void virtualThreadExecutor_withConcurrentTasks_runsAllOnVirtualThreads() throws InterruptedException {
        int sampleSize = 100;
        var virtualCount = new AtomicInteger(0);
        var latch = new CountDownLatch(sampleSize);

        for (int i = 0; i < sampleSize; i++) {
            executor.submit(() -> {
                try {
                    if (Thread.currentThread().isVirtual()) {
                        virtualCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(5, TimeUnit.SECONDS);

        assertThat(completed)
                .as("All sample tasks should complete within the timeout")
                .isTrue();
        assertThat(virtualCount.get())
                .as("Every task must run on a virtual thread")
                .isEqualTo(sampleSize);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void virtualThreadExecutor_withHighConcurrencyBlockingTasks_completesAll() throws InterruptedException {
        int taskCount = 1_000;
        var latch = new CountDownLatch(taskCount);
        var virtualCount = new AtomicInteger(0);

        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                try {
                    if (Thread.currentThread().isVirtual()) {
                        virtualCount.incrementAndGet();
                    }
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);

        assertThat(completed)
                .as("All %d tasks should complete within the timeout", taskCount)
                .isTrue();
        assertThat(virtualCount.get())
                .as("All tasks must have executed on virtual threads")
                .isEqualTo(taskCount);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shutdownExecutor_whenCalled_stopsAcceptingNewTasks() {
        config.shutdownExecutor();

        assertThat(executor.isShutdown()).isTrue();
        assertThatThrownBy(() -> executor.submit(() -> {}))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shutdownExecutor_withRunningTask_waitsForCompletion() throws InterruptedException {
        var taskCompleted = new CountDownLatch(1);

        executor.submit(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskCompleted.countDown();
            }
        });

        config.shutdownExecutor();

        assertThat(taskCompleted.await(1, TimeUnit.SECONDS))
                .as("Running task should complete before shutdown finishes")
                .isTrue();
        assertThat(executor.isTerminated()).isTrue();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shutdownExecutor_whenCalledTwice_isIdempotent() {
        config.shutdownExecutor();
        config.shutdownExecutor();

        assertThat(executor.isShutdown()).isTrue();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shutdownExecutor_whenInterruptedDuringAwait_forcesShutdownAndRestoresInterrupt()
            throws InterruptedException {
        var taskStarted = new CountDownLatch(1);
        executor.submit(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                // expected — shutdownNow will interrupt this
            }
        });
        taskStarted.await(2, TimeUnit.SECONDS);

        Thread.currentThread().interrupt();
        config.shutdownExecutor();

        assertThat(executor.isShutdown())
                .as("Executor should be shut down after interrupt path")
                .isTrue();
        assertThat(Thread.currentThread().isInterrupted())
                .as("Interrupt flag should be restored")
                .isTrue();

        // Clean up the interrupt flag so @AfterEach and JUnit don't have issues
        Thread.interrupted();
    }
}