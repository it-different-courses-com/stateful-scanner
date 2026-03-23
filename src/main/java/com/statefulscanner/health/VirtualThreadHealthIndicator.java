package com.statefulscanner.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class VirtualThreadHealthIndicator implements HealthIndicator {

    private static final long PROBE_TIMEOUT_MS = 2000;

    private final ExecutorService executor;

    public VirtualThreadHealthIndicator(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public Health health() {
        if (executor.isShutdown() || executor.isTerminated()) {
            return Health.down().withDetail("reason", "executor shut down").build();
        }

        try {
            Future<Boolean> probe = executor.submit(() -> Thread.currentThread().isVirtual());
            boolean isVirtual = probe.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!isVirtual) {
                return Health.down().withDetail("reason", "executor is not using virtual threads").build();
            }
            return Health.up().build();
        } catch (TimeoutException e) {
            return Health.down().withDetail("reason", "probe timed out").build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("reason", "health check interrupted").build();
        } catch (Exception e) {
            return Health.down().withDetail("reason", e.getMessage()).build();
        }
    }
}
