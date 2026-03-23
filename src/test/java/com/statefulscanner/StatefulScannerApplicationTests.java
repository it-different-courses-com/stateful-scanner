package com.statefulscanner;

import com.statefulscanner.health.VirtualThreadHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StatefulScannerApplicationTests {

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private VirtualThreadHealthIndicator healthIndicator;

    @Test
    void contextLoads_shouldWireAllCriticalBeans() {
        assertThat(executorService)
                .as("Virtual thread executor bean must be wired")
                .isNotNull();
        assertThat(healthIndicator)
                .as("Health indicator bean must be wired")
                .isNotNull();
    }
}