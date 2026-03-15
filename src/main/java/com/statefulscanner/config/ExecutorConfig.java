package com.statefulscanner.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Configuration
public class ExecutorConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorConfig.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    private ExecutorService executor;

    @Bean(destroyMethod = "")
    public ExecutorService virtualThreadExecutor() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        LOG.info("Virtual thread executor created: {}", executor);
        return executor;
    }

    @PreDestroy
    public void shutdownExecutor() {
        if (executor == null || executor.isShutdown()) {
            return;
        }

        LOG.info("Shutting down virtual thread executor...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("Executor did not terminate within {} seconds, forcing shutdown", SHUTDOWN_TIMEOUT_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOG.warn("Shutdown interrupted, forcing shutdown");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("Virtual thread executor shut down");
    }
}
