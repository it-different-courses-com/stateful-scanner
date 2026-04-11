package com.statefulscanner.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientConfigTest {

    private HttpClient client;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        var config = new HttpClientConfig();
        client = config.httpClient(executor);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void httpClient_whenCreated_hasConnectTimeoutOfTenSeconds() {
        assertThat(client.connectTimeout())
                .as("HttpClient must have a 10-second connect timeout")
                .hasValue(Duration.ofSeconds(10));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void httpClient_whenCreated_followsNormalRedirects() {
        assertThat(client.followRedirects())
                .as("HttpClient must follow normal redirects")
                .isEqualTo(HttpClient.Redirect.NORMAL);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void httpClient_whenCreated_prefersHttp2() {
        assertThat(client.version())
                .as("HttpClient must prefer HTTP/2")
                .isEqualTo(HttpClient.Version.HTTP_2);
    }
}
