package com.statefulscanner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

@Configuration
public class HttpClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean(destroyMethod = "close")
    public HttpClient httpClient(ExecutorService virtualThreadExecutor) {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)
                .executor(virtualThreadExecutor)
                .build();
    }
}
