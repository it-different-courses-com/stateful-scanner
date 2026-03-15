package com.statefulscanner.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutorConfigTest {

    private static final int HIGH_CONCURRENCY_TASK_COUNT = 10_000;

    private ExecutorConfig config;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        config = new ExecutorConfig();
        executor = config.virtualThreadExecutor();
    }

    @Test
    void virtualThreadExecutorBeanShouldBeNonNull() {
        assertThat(executor)
                .as("ExecutorConfig must produce a non-null ExecutorService")
                .isNotNull();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void executorShouldCreateVirtualThreads() throws InterruptedException {
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
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void executorShouldHandleHighConcurrencyWithBlockingTasks() throws InterruptedException {
        var latch = new CountDownLatch(HIGH_CONCURRENCY_TASK_COUNT);
        var virtualCount = new AtomicInteger(0);

        for (int i = 0; i < HIGH_CONCURRENCY_TASK_COUNT; i++) {
            executor.submit(() -> {
                try {
                    assertThat(Thread.currentThread().isVirtual())
                            .as("Task must execute on a virtual thread")
                            .isTrue();
                    virtualCount.incrementAndGet();
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(15, TimeUnit.SECONDS);

        assertThat(completed)
                .as("All %d tasks should complete within the timeout", HIGH_CONCURRENCY_TASK_COUNT)
                .isTrue();
        assertThat(virtualCount.get())
                .as("All tasks must have executed on virtual threads")
                .isEqualTo(HIGH_CONCURRENCY_TASK_COUNT);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shutdownShouldStopAcceptingNewTasks() {
        config.shutdownExecutor();

        assertThat(executor.isShutdown()).isTrue();
        assertThatThrownBy(() -> executor.submit(() -> {}))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shutdownShouldWaitForRunningTasksToComplete() throws InterruptedException {
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
    void shutdownShouldBeIdempotent() {
        config.shutdownExecutor();
        config.shutdownExecutor();

        assertThat(executor.isShutdown()).isTrue();
    }
}