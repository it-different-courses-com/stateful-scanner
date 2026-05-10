package com.statefulscanner.service;

import com.google.common.util.concurrent.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    @SuppressWarnings("UnstableApiUsage")
    private RateLimiter mockLimiter;

    private RateLimiterService service;

    @BeforeEach
    void setUp() {
        service = new RateLimiterService(mockLimiter);
    }

    // --- delegation: acquire() ---

    @Test
    void acquire_delegatesToUnderlyingLimiter() {
        when(mockLimiter.acquire()).thenReturn(0.5);

        double waited = service.acquire();

        assertThat(waited).isEqualTo(0.5);
        verify(mockLimiter).acquire();
    }

    // --- delegation: acquire(int) ---

    @Test
    void acquireWithPermits_delegatesToUnderlyingLimiter() {
        when(mockLimiter.acquire(3)).thenReturn(0.25);

        double waited = service.acquire(3);

        assertThat(waited).isEqualTo(0.25);
        verify(mockLimiter).acquire(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void acquireWithPermits_whenNonPositive_throwsAndDoesNotDelegate(int permits) {
        assertThatThrownBy(() -> service.acquire(permits))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permits must be positive")
                .hasMessageContaining(String.valueOf(permits));

        verify(mockLimiter, never()).acquire(anyInt());
    }

    // --- delegation: tryAcquire() ---

    @Test
    void tryAcquire_delegatesToUnderlyingLimiter() {
        when(mockLimiter.tryAcquire()).thenReturn(true);

        boolean result = service.tryAcquire();

        assertThat(result).isTrue();
        verify(mockLimiter).tryAcquire();
    }

    @Test
    void tryAcquire_whenLimiterReturnsFalse_propagatesFalse() {
        when(mockLimiter.tryAcquire()).thenReturn(false);

        assertThat(service.tryAcquire()).isFalse();
    }

    // --- delegation: tryAcquire(Duration) ---

    @Test
    void tryAcquireDuration_delegatesToUnderlyingLimiter() {
        Duration timeout = Duration.ofMillis(250);
        when(mockLimiter.tryAcquire(timeout)).thenReturn(true);

        assertThat(service.tryAcquire(timeout)).isTrue();
        verify(mockLimiter).tryAcquire(timeout);
    }

    @Test
    void tryAcquireDuration_whenNull_throwsNpeAndDoesNotDelegate() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.tryAcquire((Duration) null))
                .withMessageContaining("timeout");

        verify(mockLimiter, never()).tryAcquire(any(Duration.class));
    }

    // --- delegation: tryAcquire(long, TimeUnit) ---

    @Test
    void tryAcquireTimeoutUnit_delegatesToUnderlyingLimiter() {
        when(mockLimiter.tryAcquire(100L, TimeUnit.MILLISECONDS)).thenReturn(true);

        assertThat(service.tryAcquire(100L, TimeUnit.MILLISECONDS)).isTrue();
        verify(mockLimiter).tryAcquire(100L, TimeUnit.MILLISECONDS);
    }

    @Test
    void tryAcquireTimeoutUnit_whenUnitNull_throwsNpeAndDoesNotDelegate() {
        assertThatNullPointerException()
                .isThrownBy(() -> service.tryAcquire(100L, null))
                .withMessageContaining("unit");

        verify(mockLimiter, never()).tryAcquire(anyLong(), any(TimeUnit.class));
    }

    @Test
    void tryAcquireTimeoutUnit_withNegativeTimeout_returnsImmediatelyWithoutWaiting() {
        // Pass-through to Guava, which treats negative as "no wait" — wrapper does NOT reject.
        when(mockLimiter.tryAcquire(eq(-1L), eq(TimeUnit.MILLISECONDS))).thenReturn(false);

        assertThat(service.tryAcquire(-1L, TimeUnit.MILLISECONDS)).isFalse();
    }

    // --- setRate validation + delegation ---

    @Test
    void setRate_withPositiveFiniteValue_delegatesToUnderlyingLimiter() {
        service.setRate(250.0);

        verify(mockLimiter).setRate(250.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.0, -1.0, -100.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN})
    void setRate_withInvalidValue_throwsAndDoesNotDelegate(double rate) {
        assertThatThrownBy(() -> service.setRate(rate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permitsPerSecond");

        verifyNoInteractions(mockLimiter);
    }

    // --- getRate ---

    @Test
    void getRate_delegatesToUnderlyingLimiter() {
        when(mockLimiter.getRate()).thenReturn(123.0);

        assertThat(service.getRate()).isEqualTo(123.0);
    }

    // --- real-limiter behavior ---
    //
    // Guava's SmoothBursty stores up to maxBurstSeconds*rate permits between create-time
    // and the first acquire, so every timed test below drains the burst budget BEFORE
    // sampling the start clock. The lower-bound assertions then exercise actual pacing,
    // not accumulated free credit.

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @SuppressWarnings("UnstableApiUsage")
    void acquire_withRealLimiter_pacesSequentialCallsAtConfiguredRate() {
        RateLimiter realLimiter = RateLimiter.create(20.0); // 50ms per permit
        var realService = new RateLimiterService(realLimiter);
        int permitCount = 5;

        // Drain any stored permits (and the free first permit) before the timed window.
        while (realService.tryAcquire()) {
            // burn it
        }

        long startNanos = System.nanoTime();
        for (int i = 0; i < permitCount; i++) {
            realService.acquire();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        long expectedMinMs = permitCount * 1_000L / 20L; // 250ms — every permit is paced
        assertThat(elapsedMs)
                .as("Sequential acquires should pace at ~rate; expected >= %d ms", expectedMinMs)
                .isGreaterThanOrEqualTo(expectedMinMs - 20L);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @SuppressWarnings("UnstableApiUsage")
    void setRate_withRealLimiter_changesEffectiveRate() {
        RateLimiter realLimiter = RateLimiter.create(1.0);
        var realService = new RateLimiterService(realLimiter);

        assertThat(realService.getRate()).isEqualTo(1.0);

        realService.setRate(50.0);

        assertThat(realService.getRate()).isEqualTo(50.0);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @SuppressWarnings("UnstableApiUsage")
    void acquire_underConcurrentVirtualThreadFanOut_paceIsSharedAcrossThreads() throws InterruptedException {
        RateLimiter realLimiter = RateLimiter.create(50.0); // 50 permits/sec
        var realService = new RateLimiterService(realLimiter);
        int threadCount = 20;
        int permitsPerThread = 5;
        int totalPermits = threadCount * permitsPerThread; // 100

        try (ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(threadCount);
            var go = new CountDownLatch(1);
            var acquired = new AtomicInteger();

            for (int i = 0; i < threadCount; i++) {
                vtPool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int p = 0; p < permitsPerThread; p++) {
                        realService.acquire();
                        acquired.incrementAndGet();
                    }
                });
            }

            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();

            // Drain accumulated burst credit immediately before the timed window opens.
            while (realService.tryAcquire()) {
                // burn it
            }

            long startNanos = System.nanoTime();
            go.countDown();
            vtPool.shutdown();
            assertThat(vtPool.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertThat(acquired.get()).isEqualTo(totalPermits);

            long expectedMinMs = totalPermits * 1_000L / 50L; // 2000ms — every permit is paced
            assertThat(elapsedMs)
                    .as("Concurrent acquires must respect the shared rate budget (>= %d ms)", expectedMinMs)
                    .isGreaterThanOrEqualTo(expectedMinMs - 100L);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @SuppressWarnings("UnstableApiUsage")
    void tryAcquireDuration_withRealLimiter_andTinyTimeout_returnsFalseWhenStarved() {
        RateLimiter realLimiter = RateLimiter.create(2.0); // 500ms per permit
        var realService = new RateLimiterService(realLimiter);

        // Drain *all* stored permits — bursty limiters can hold up to maxBurstSeconds*rate.
        while (realService.tryAcquire()) {
            // burn it
        }

        // 10ms is far less than the 500ms it takes to mint the next permit.
        boolean acquired = realService.tryAcquire(Duration.ofMillis(10));

        assertThat(acquired).isFalse();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @SuppressWarnings("UnstableApiUsage")
    void tryAcquireDuration_withRealLimiter_andSufficientTimeout_returnsTrueAfterRealWait() {
        RateLimiter realLimiter = RateLimiter.create(100.0); // 10ms per permit
        var realService = new RateLimiterService(realLimiter);

        // Drain stored permits so the timed call must actually wait for a fresh permit.
        while (realService.tryAcquire()) {
            // burn it
        }

        long startNanos = System.nanoTime();
        boolean acquired = realService.tryAcquire(Duration.ofMillis(500));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(acquired).isTrue();
        assertThat(waitedMs)
                .as("Should have actually paused for ~1 period (10ms) before acquiring")
                .isGreaterThanOrEqualTo(5L); // half of 10ms — generous slack for clock resolution
    }
}

