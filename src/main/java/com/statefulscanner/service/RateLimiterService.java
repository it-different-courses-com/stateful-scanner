package com.statefulscanner.service;

import com.google.common.util.concurrent.RateLimiter;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around Guava's {@link RateLimiter} that hides the {@code @Beta} type
 * from callers and provides a stable surface for rate-limited request pacing.
 *
 * <p>This service is thread-safe: the underlying {@code RateLimiter} synchronises
 * internally, so multiple virtual threads may call {@link #acquire()} concurrently
 * and will be paced fairly across the shared rate budget.
 *
 * <p><strong>Virtual-thread note:</strong> Guava's {@code RateLimiter} synchronises
 * on a short internal monitor while computing the next permit. On Java 25 (JEP 491)
 * virtual threads no longer pin their carrier while holding monitors, so this
 * critical section is safe under heavy virtual-thread fan-out. The wait inside
 * {@link #acquire()} is implemented by Guava as an <em>uninterruptible</em> sleep
 * (interrupts are absorbed and the flag is restored on return), so callers cannot
 * cancel an in-flight {@code acquire()} via {@code Thread.interrupt()}; use
 * {@link #tryAcquire(Duration)} with a bounded timeout for cancellable flows.
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * rateLimiterService.acquire();   // blocks until a permit is available
 * httpClient.get(url);            // dispatch under the rate budget
 * }</pre>
 *
 * <p>For non-blocking flows (e.g. an event loop), prefer
 * {@link #tryAcquire(Duration)} with a short timeout and back off on failure.
 */
@Service
@SuppressWarnings("UnstableApiUsage")
public class RateLimiterService {

    private final RateLimiter rateLimiter;

    public RateLimiterService(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * Blocks until a single permit is available.
     *
     * @return the number of seconds the caller waited (0.0 if the permit was immediate)
     */
    public double acquire() {
        return rateLimiter.acquire();
    }

    /**
     * Blocks until {@code permits} permits are available.
     *
     * @param permits number of permits to acquire; must be positive
     * @return the number of seconds the caller waited (0.0 if granted immediately)
     * @throws IllegalArgumentException if {@code permits} is not positive
     */
    public double acquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive, got: " + permits);
        }
        return rateLimiter.acquire(permits);
    }

    /**
     * Attempts to acquire a single permit without blocking.
     *
     * @return {@code true} if a permit was available immediately, {@code false} otherwise
     */
    public boolean tryAcquire() {
        return rateLimiter.tryAcquire();
    }

    /**
     * Attempts to acquire a single permit, waiting up to {@code timeout}.
     *
     * <p>A negative or zero timeout is treated as a non-blocking probe.
     *
     * @param timeout the maximum time to wait
     * @return {@code true} if the permit was acquired within the timeout, {@code false} otherwise
     * @throws NullPointerException if {@code timeout} is {@code null}
     */
    public boolean tryAcquire(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        return rateLimiter.tryAcquire(timeout);
    }

    /**
     * Attempts to acquire a single permit, waiting up to the given timeout.
     *
     * @param timeout the maximum time to wait; negative values are treated as zero
     * @param unit    the time unit of {@code timeout}
     * @return {@code true} if the permit was acquired within the timeout, {@code false} otherwise
     * @throws NullPointerException if {@code unit} is {@code null}
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit must not be null");
        return rateLimiter.tryAcquire(timeout, unit);
    }

    /**
     * Adjusts the steady-state permit rate at runtime.
     *
     * <p>Permits already in flight are not affected. The new rate takes effect for
     * the next acquisition. Useful for adaptive scanning that slows down on
     * 429/503 responses or speeds up when a target proves resilient.
     *
     * @param permitsPerSecond new target rate; must be positive and finite
     * @throws IllegalArgumentException if {@code permitsPerSecond} is not positive or is not finite
     */
    public void setRate(double permitsPerSecond) {
        if (!(permitsPerSecond > 0.0) || Double.isInfinite(permitsPerSecond)) {
            throw new IllegalArgumentException(
                    "permitsPerSecond must be positive and finite, got: " + permitsPerSecond);
        }
        rateLimiter.setRate(permitsPerSecond);
    }

    /**
     * @return the currently configured permit rate (permits per second)
     */
    public double getRate() {
        return rateLimiter.getRate();
    }
}
