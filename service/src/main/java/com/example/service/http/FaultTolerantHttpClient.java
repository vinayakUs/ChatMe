package com.example.service.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import com.google.common.annotations.VisibleForTesting;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.Nullable;

/**
 * FaultTolrentHttpClient
 */
public class FaultTolerantHttpClient {
    private final Duration defaultRequestTimeout;
    private final List<HttpClient> httpClients;
    private final CircuitBreaker breaker;
    @Nullable
    private final Retry retry;
    @Nullable
    private final ScheduledExecutorService retryExecutor;

    @VisibleForTesting
    private FaultTolerantHttpClient(final List<HttpClient> httpClients, final Duration defaultRequestTimeout,
            final CircuitBreaker breaker, @Nullable final Retry retry,
            @Nullable final ScheduledExecutorService retryExecutor) {
        this.httpClients = httpClients;
        this.defaultRequestTimeout = defaultRequestTimeout;
        this.breaker = breaker;
        this.retry = retry;
        this.retryExecutor = retryExecutor;
    }

    private HttpClient httpClient() {
        return this.httpClients.get(ThreadLocalRandom.current().nextInt(this.httpClients.size()));
    }

    private static HttpRequest requestWithTimeout(final HttpRequest request, final Duration defaultRequestTimeout) {

        return request.timeout().isPresent() ? request
                : HttpRequest.newBuilder(request, (headerName, headerValue) -> true).timeout(defaultRequestTimeout)
                        .build();

    }

    /**
     * Send request sync using FaultTolerantClient
     * @param <T>
     * @param httpRequest
     * @param bodyHandler
     * @return HttpResponse<T>
     * @throws IOException
     */
    public <T> HttpResponse<T> send(final HttpRequest httpRequest, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException {

        Callable<HttpResponse<T>> requestCallable = () -> httpClient()
                .send(requestWithTimeout(httpRequest, defaultRequestTimeout), bodyHandler);

        try {
            return retry != null ? breaker.executeCallable(retry.decorateCallable(requestCallable))
                    : breaker.executeCallable(requestCallable);
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }

    }

    public static Builder newBuilder(final String name, final Executor executor) {
        return new Builder(name, executor);
    }

    public static class Builder {
        private HttpClient.Version version = HttpClient.Version.HTTP_2;
        private HttpClient.Redirect redirect = HttpClient.Redirect.NEVER;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private int numClients = 1;
        private final String name;
        private final Executor executor;
        @Nullable
        private String circuitBreakerConfigName;
        @Nullable
        private String retryConfigName;
        @Nullable
        private ScheduledExecutorService retryExecutor;

        private Builder(final String name, final Executor executor) {
            this.name = getClass().getSimpleName() + "/" + Objects.requireNonNull(name);
            this.executor = Objects.requireNonNull(executor);

        }

        public Builder withVersion(HttpClient.Version version) {
            this.version = version;
            return this;
        }

        public Builder withRedirect(HttpClient.Redirect redirect) {
            this.redirect = redirect;
            return this;
        }

        public Builder withConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder withRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder withNumClients(final int numClients) {
            this.numClients = numClients;
            return this;
        }

        public Builder withCircuitBreaker(String configName) {
            this.circuitBreakerConfigName = configName;
            return this;
        }

        public Builder withRetry(@Nullable String retryConfigName,
                ScheduledExecutorService retryExecutor) {
            this.retryConfigName = retryConfigName;
            this.retryExecutor = retryExecutor;
            return this;
        }

        public FaultTolerantHttpClient build(CircuitBreakerRegistry circuitRegistry, RetryRegistry retryRegistry) {

            if (numClients > 1 && version != HttpClient.Version.HTTP_2) {
                throw new IllegalArgumentException("Should not use additional HTTP clients unless using HTTP/2");
            }

            final List<HttpClient> clients = IntStream.range(0, numClients).mapToObj(i -> {

                HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout)
                        .followRedirects(redirect).version(version).executor(executor);

                return builder.build();

            }).toList();

            // Resolve circuit breaker
            final CircuitBreaker breaker = circuitBreakerConfigName != null
                    ? circuitRegistry.circuitBreaker(name, circuitBreakerConfigName)
                    : circuitRegistry.circuitBreaker(name);

            @Nullable
            final Retry retry;

            if (retryExecutor != null) {
                retry = retryRegistry.retry(name, retryConfigName != null ? retryConfigName : "default");
            } else {
                retry = null;
            }

            return new FaultTolerantHttpClient(clients, requestTimeout, breaker, retry, retryExecutor);
        }

    }

}
