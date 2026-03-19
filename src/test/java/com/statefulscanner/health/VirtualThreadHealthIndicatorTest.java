package com.statefulscanner.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VirtualThreadHealthIndicatorTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void health_whenExecutorIsRunning_returnsUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();

        var indicator = new VirtualThreadHealthIndicator(executor);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .as("UP health should not carry error details")
                .isEmpty();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void health_whenExecutorIsShutdown_returnsDown() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.shutdown();

        var indicator = new VirtualThreadHealthIndicator(executor);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("executor shut down");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void health_whenExecutorIsTerminated_returnsDown() throws InterruptedException {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        var indicator = new VirtualThreadHealthIndicator(executor);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("executor shut down");
    }

    @SuppressWarnings("unchecked")
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void health_whenExecutorUsesNonVirtualThreads_returnsDown() throws Exception {
        Future<Boolean> platformFuture = mock(Future.class);
        when(platformFuture.get(anyLong(), any(TimeUnit.class))).thenReturn(false);

        executor = mock(ExecutorService.class);
        when(executor.isShutdown()).thenReturn(false);
        when(executor.isTerminated()).thenReturn(false);
        when(executor.submit(any(java.util.concurrent.Callable.class))).thenReturn(platformFuture);

        var indicator = new VirtualThreadHealthIndicator(executor);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("executor is not using virtual threads");
    }

    @SuppressWarnings("unchecked")
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void health_whenProbeTimesOut_returnsDown() throws Exception {
        Future<Boolean> hangingFuture = mock(Future.class);
        when(hangingFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException());

        executor = mock(ExecutorService.class);
        when(executor.isShutdown()).thenReturn(false);
        when(executor.isTerminated()).thenReturn(false);
        when(executor.submit(any(java.util.concurrent.Callable.class))).thenReturn(hangingFuture);

        var indicator = new VirtualThreadHealthIndicator(executor);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("probe timed out");
    }

    @SuppressWarnings("unchecked")
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void health_whenProbeThrowsUnexpectedException_returnsDown() {
        executor = mock(ExecutorService.class);
        when(executor.isShutdown()).thenReturn(false);
        when(executor.isTerminated()).thenReturn(false);
        when(executor.submit(any(java.util.concurrent.Callable.class)))
                .thenThrow(new RejectedExecutionException("executor overloaded"));

        var indicator = new VirtualThreadHealthIndicator(executor);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("executor overloaded");
    }

    @SuppressWarnings("unchecked")
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void health_whenProbeIsInterrupted_returnsDown() throws Exception {
        Future<Boolean> interruptedFuture = mock(Future.class);
        when(interruptedFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("thread interrupted"));

        executor = mock(ExecutorService.class);
        when(executor.isShutdown()).thenReturn(false);
        when(executor.isTerminated()).thenReturn(false);
        when(executor.submit(any(java.util.concurrent.Callable.class))).thenReturn(interruptedFuture);

        var indicator = new VirtualThreadHealthIndicator(executor);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("health check interrupted");
        assertThat(Thread.currentThread().isInterrupted())
                .as("Interrupt flag must be restored")
                .isTrue();

        // Clean up so @AfterEach and JUnit don't have issues
        Thread.interrupted();
    }

    @SuppressWarnings("unchecked")
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void health_whenProbeTaskFails_returnsDown() throws Exception {
        Future<Boolean> failedFuture = mock(Future.class);
        when(failedFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new ExecutionException(new RuntimeException("task failed")));

        executor = mock(ExecutorService.class);
        when(executor.isShutdown()).thenReturn(false);
        when(executor.isTerminated()).thenReturn(false);
        when(executor.submit(any(java.util.concurrent.Callable.class))).thenReturn(failedFuture);

        var indicator = new VirtualThreadHealthIndicator(executor);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason"))
                .asString()
                .contains("task failed");
    }
}