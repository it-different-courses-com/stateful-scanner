package com.statefulscanner.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class HttpRequestService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT = "StatefulScanner/1.0 (Security Testing Tool)";
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 100L;

    private final HttpClient httpClient;

    public HttpRequestService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public CompletableFuture<HttpResponse<String>> sendGetRequest(String url) {
        return sendGetRequest(url, Map.of());
    }

    public CompletableFuture<HttpResponse<String>> sendGetRequest(String url, Map<String, String> customHeaders) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT)
                .GET();

        customHeaders.forEach(requestBuilder::header);

        return httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public CompletableFuture<HttpResponse<String>> sendGetRequestWithRetry(String url) {
        return sendGetRequestWithRetry(url, 0);
    }

    private CompletableFuture<HttpResponse<String>> sendGetRequestWithRetry(String url, int attemptNumber) {
        return sendGetRequest(url)
                .handle((response, throwable) -> {
                    if (throwable == null) {
                        return CompletableFuture.completedFuture(response);
                    }
                    if (attemptNumber < MAX_RETRIES && isRetriable(throwable)) {
                        return retryAfterBackoff(url, attemptNumber);
                    }
                    return CompletableFuture.<HttpResponse<String>>failedFuture(
                            new RuntimeException(
                                    "Request failed after " + (attemptNumber + 1) + " attempts",
                                    throwable));
                })
                .thenCompose(future -> future);
    }

    private CompletableFuture<HttpResponse<String>> retryAfterBackoff(String url, int attemptNumber) {
        long backoffMs = INITIAL_BACKOFF_MS << attemptNumber;
        Executor delayed = CompletableFuture.delayedExecutor(backoffMs, TimeUnit.MILLISECONDS);
        return CompletableFuture.supplyAsync(() -> null, delayed)
                .thenCompose(_ -> sendGetRequestWithRetry(url, attemptNumber + 1));
    }

    private boolean isRetriable(Throwable throwable) {
        Throwable cause = switch (throwable) {
            case CompletionException ce -> ce.getCause();
            default -> throwable;
        };
        return cause instanceof IOException || cause instanceof TimeoutException;
    }
}
