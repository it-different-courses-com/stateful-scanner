package com.statefulscanner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class HttpRequestServiceTest {

    private HttpClient httpClient;
    private HttpRequestService service;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        service = new HttpRequestService(httpClient);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendGetRequest_whenCalled_setsUserAgentHeader() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        service.sendGetRequest("https://example.com").get(2, TimeUnit.SECONDS);

        verify(httpClient).sendAsync(
                requestMatching(request -> request.headers().firstValue("User-Agent")
                        .orElse("").equals("StatefulScanner/1.0 (Security Testing Tool)")),
                eq(HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendGetRequest_whenCalled_usesGetMethod() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        service.sendGetRequest("https://example.com").get(2, TimeUnit.SECONDS);

        verify(httpClient).sendAsync(
                requestMatching(request -> "GET".equals(request.method())),
                eq(HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendGetRequest_whenCalled_setsRequestTimeout() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        service.sendGetRequest("https://example.com").get(2, TimeUnit.SECONDS);

        verify(httpClient).sendAsync(
                requestMatching(request -> request.timeout()
                        .filter(t -> t.equals(java.time.Duration.ofSeconds(10)))
                        .isPresent()),
                eq(HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendGetRequest_withCustomHeaders_addsThemToRequest() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        Map<String, String> headers = Map.of("Authorization", "Bearer token123");
        service.sendGetRequest("https://example.com", headers).get(2, TimeUnit.SECONDS);

        verify(httpClient).sendAsync(
                requestMatching(request -> request.headers().firstValue("Authorization")
                        .orElse("").equals("Bearer token123")),
                eq(HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendGetRequest_withEmptyHeaders_sendsRequestWithOnlyUserAgent() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        service.sendGetRequest("https://example.com").get(2, TimeUnit.SECONDS);

        verify(httpClient).sendAsync(
                requestMatching(request -> request.headers().firstValue("User-Agent").isPresent()
                        && request.headers().firstValue("Authorization").isEmpty()),
                eq(HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendGetRequest_returnsResponseFromHttpClient() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(404);
        when(mockResponse.body()).thenReturn("Not Found");
        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        HttpResponse<String> response = service.sendGetRequest("https://example.com/missing")
                .get(2, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("Not Found");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendGetRequestWithRetry_onSuccess_returnsResponse() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        HttpResponse<String> response = service.sendGetRequestWithRetry("https://example.com")
                .get(2, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);
        verify(httpClient, times(1)).sendAsync(any(), any());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sendGetRequestWithRetry_onRetriableFailure_retriesThenSucceeds() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);

        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.failedFuture(new IOException("Connection reset")))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        HttpResponse<String> response = service.sendGetRequestWithRetry("https://example.com")
                .get(5, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);
        verify(httpClient, times(2)).sendAsync(any(), any());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sendGetRequestWithRetry_onNonRetriableFailure_failsImmediately() {
        assertThatThrownBy(() -> service.sendGetRequestWithRetry("not-a-url")
                .join())
                .isInstanceOf(IllegalArgumentException.class);

        verify(httpClient, times(0)).sendAsync(any(), any());
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void sendGetRequestWithRetry_afterMaxRetries_throwsException() {
        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.failedFuture(new IOException("Connection refused")));

        assertThatThrownBy(() -> service.sendGetRequestWithRetry("https://example.com")
                .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(com.statefulscanner.exception.HttpRetryExhaustedException.class)
                .hasMessageContaining("attempts");

        verify(httpClient, times(4)).sendAsync(any(), any());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendGetRequestWithRetry_on500Response_returnsItWithoutRetry() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(500);
        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        HttpResponse<String> response = service.sendGetRequestWithRetry("https://example.com")
                .get(2, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(500);
        verify(httpClient, times(1)).sendAsync(any(), any());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sendGetRequestWithRetry_on400Response_returnsItWithoutRetry() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(400);
        when(httpClient.sendAsync(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        HttpResponse<String> response = service.sendGetRequestWithRetry("https://example.com")
                .get(2, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(400);
        verify(httpClient, times(1)).sendAsync(any(), any());
    }

    private static HttpRequest requestMatching(java.util.function.Predicate<HttpRequest> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }
}
