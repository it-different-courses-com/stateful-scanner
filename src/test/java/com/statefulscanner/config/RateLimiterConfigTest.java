package com.statefulscanner.config;

import com.google.common.util.concurrent.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("UnstableApiUsage")
class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();

    @Test
    void rateLimiter_withNoWarmup_returnsLimiterAtConfiguredRate() {
        var props = new RateLimiterProperties(250.0, 0.0);

        RateLimiter limiter = config.rateLimiter(props);

        assertThat(limiter).isNotNull();
        assertThat(limiter.getRate()).isEqualTo(250.0);
    }

    @Test
    void rateLimiter_withWarmup_returnsLimiterAtConfiguredSteadyRate() {
        var props = new RateLimiterProperties(500.0, 1.5);

        RateLimiter limiter = config.rateLimiter(props);

        assertThat(limiter).isNotNull();
        assertThat(limiter.getRate())
                .as("Steady-state rate should match config regardless of warmup mode")
                .isEqualTo(500.0);
    }

    @Test
    void rateLimiter_atMinimumRate_isCreated() {
        var props = new RateLimiterProperties(1.0, 0.0);

        RateLimiter limiter = config.rateLimiter(props);

        assertThat(limiter.getRate()).isEqualTo(1.0);
    }

    @Test
    void rateLimiter_atMaximumRate_isCreated() {
        var props = new RateLimiterProperties(10_000.0, 0.0);

        RateLimiter limiter = config.rateLimiter(props);

        assertThat(limiter.getRate()).isEqualTo(10_000.0);
    }

    @Test
    void rateLimiter_eachInvocation_returnsDistinctInstance() {
        var props = new RateLimiterProperties(100.0, 0.0);

        RateLimiter first = config.rateLimiter(props);
        RateLimiter second = config.rateLimiter(props);

        assertThat(first).isNotSameAs(second);
    }

    // The next two tests assert on Guava's internal limiter class names
    // (SmoothBursty / SmoothWarmingUp). The coupling is deliberate: both modes
    // report the same getRate(), so the class name is the only observable signal
    // that RateLimiterConfig selected the correct branch for the warmup setting.
    @Test
    void rateLimiter_withZeroWarmup_returnsBurstyLimiterImplementation() {
        var props = new RateLimiterProperties(500.0, 0.0);

        RateLimiter limiter = config.rateLimiter(props);

        assertThat(limiter.getClass().getSimpleName())
                .as("warmup=0 must select Guava's smooth-bursty limiter")
                .isEqualTo("SmoothBursty");
    }

    @Test
    void rateLimiter_withPositiveWarmup_returnsWarmingUpLimiterImplementation() {
        var props = new RateLimiterProperties(500.0, 1.5);

        RateLimiter limiter = config.rateLimiter(props);

        assertThat(limiter.getClass().getSimpleName())
                .as("warmup>0 must select Guava's smooth-warming-up limiter")
                .isEqualTo("SmoothWarmingUp");
    }
}

