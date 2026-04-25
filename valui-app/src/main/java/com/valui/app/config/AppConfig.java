package com.valui.app.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class AppConfig {

    /**
     * Synchronous HTTP client for bookmaker REST calls that don't need reactive backpressure.
     * Virtual-thread-friendly: blocking on the calling VT is cheap.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Reactive HTTP client for non-blocking bookmaker feeds (WebSocket upgrades, SSE).
     * Timeouts set at transport level via Reactor Netty.
     */
    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            .responseTimeout(Duration.ofSeconds(10));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }

    /**
     * ApplicationEventPublisher is auto-registered by Spring context.
     * Expose as @Bean so services can inject it without knowing the ApplicationContext.
     * Use for domain events: publisher.publishEvent(new MatchDiscoveredEvent(...))
     */
    @Bean
    public ApplicationEventPublisher applicationEventPublisher(ApplicationEventPublisher publisher) {
        return publisher;
    }
}
