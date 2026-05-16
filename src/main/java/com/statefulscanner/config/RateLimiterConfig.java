package com.statefulscanner.config;

import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires Guava's {@link RateLimiter} as a Spring bean using settings from
 * {@link RateLimiterProperties}.
 *
 * <p>When {@code warmupPeriodSeconds == 0} a smooth-bursty limiter is built.
 * Otherwise a smooth-warming-up limiter is built that ramps from a slower cold rate
 * to the configured rate over the warmup window — useful for not flooding a target
 * server the moment a scan begins.
 *
 * <p><strong>Note on {@code @Beta}:</strong> Guava's {@code RateLimiter} is annotated
 * {@code @Beta}. The wrapping {@link com.statefulscanner.service.RateLimiterService}
 * exists precisely to keep that type out of the rest of the codebase so it can be
 * swapped for a stable alternative (e.g. Resilience4j) without rippling through callers.
 */
@Configuration
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimiterConfig.class);

    @Bean
    @SuppressWarnings("UnstableApiUsage")
    public RateLimiter rateLimiter(RateLimiterProperties properties) {
        double rps = properties.permitsPerSecond();
        double warmupSeconds = properties.warmupPeriodSeconds();

        if (warmupSeconds > 0.0) {
            Duration warmup = Duration.ofNanos((long) (warmupSeconds * 1_000_000_000.0));
            RateLimiter limiter = RateLimiter.create(rps, warmup);
            LOG.info("RateLimiter created: {} permits/sec with {}s warmup (smooth-warming-up)",
                    rps, warmupSeconds);
            return limiter;
        }

        RateLimiter limiter = RateLimiter.create(rps);
        LOG.info("RateLimiter created: {} permits/sec (smooth-bursty, no warmup)", rps);
        return limiter;
    }
}
