package com.statefulscanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the request rate limiter.
 *
 * <p>Bound from the {@code scanner.rate-limiter.*} property namespace. The default
 * {@code application.yml} wires {@code permits-per-second} to the {@code --rate}
 * command-line shorthand via a placeholder, so both
 * {@code --rate=500} and {@code --scanner.rate-limiter.permits-per-second=500}
 * select the same setting.
 *
 * <p><strong>Bound asymmetry:</strong> the {@code [1, 10000]} range checked by
 * this record's canonical constructor constrains <em>startup</em> configuration
 * only. The runtime
 * {@link com.statefulscanner.service.RateLimiterService#setRate(double)} path
 * intentionally accepts any positive, finite rate so adaptive throttling can move
 * outside this range in response to target behaviour. That looser runtime bound
 * is by design — do not tighten it to match.
 *
 * @param permitsPerSecond     target steady-state request rate.
 *                             Must be in [1, 10000]; defaults to 100.
 * @param warmupPeriodSeconds  cold-start ramp-up window. {@code 0} disables warmup
 *                             (smooth-bursty mode); a positive value selects Guava's
 *                             smooth-warming-up mode where the first permit is slower
 *                             and the rate ramps up linearly until the steady rate is
 *                             reached after {@code warmupPeriodSeconds}.
 *                             Must be {@code >= 0}; defaults to 0.
 */
@ConfigurationProperties(prefix = "scanner.rate-limiter")
public record RateLimiterProperties(
        @DefaultValue("100.0") double permitsPerSecond,
        @DefaultValue("0.0") double warmupPeriodSeconds) {

    public static final double MIN_PERMITS_PER_SECOND = 1.0;
    public static final double MAX_PERMITS_PER_SECOND = 10_000.0;

    public RateLimiterProperties {
        if (!Double.isFinite(permitsPerSecond)
                || permitsPerSecond < MIN_PERMITS_PER_SECOND
                || permitsPerSecond > MAX_PERMITS_PER_SECOND) {
            throw new IllegalArgumentException(
                    "scanner.rate-limiter.permits-per-second must be finite and in [%s, %s], got: %s"
                            .formatted(MIN_PERMITS_PER_SECOND, MAX_PERMITS_PER_SECOND, permitsPerSecond));
        }
        if (!Double.isFinite(warmupPeriodSeconds) || warmupPeriodSeconds < 0.0) {
            throw new IllegalArgumentException(
                    "scanner.rate-limiter.warmup-period-seconds must be finite and >= 0, got: "
                            + warmupPeriodSeconds);
        }
    }
}
