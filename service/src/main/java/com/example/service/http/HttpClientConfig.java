package com.example.service.http;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * HttpClientConfig
 */
@Configuration
public class HttpClientConfig {

    @Bean(name = "default")
    public FaultTolerantHttpClient faultTolerantHttpClient(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            Executor virtualExecutor) {
        return FaultTolerantHttpClient.newBuilder("carrier", virtualExecutor).withCircuitBreaker("default")
                .build(circuitBreakerRegistry, retryRegistry);

    }

}