package com.statefulscanner.health;

import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class VirtualThreadHealthIndicator {

    private static final long PROBE_TIMEOUT_MS = 2000;

    private final ExecutorService executor;

    public VirtualThreadHealthIndicator(ExecutorService executor) {
        this.executor = executor;
    }

    public boolean isHealthy() {
        if (executor.isShutdown() || executor.isTerminated()) {
            return false;
        }

        try {
            Future<Boolean> probe = executor.submit(() -> true);
            return probe.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
