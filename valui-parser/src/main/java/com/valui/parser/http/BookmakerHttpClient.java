package com.valui.parser.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Thin reactive wrapper over a pre-configured WebClient.
 * Parsers call .block(BLOCK_TIMEOUT) on the returned Mono.
 *
 * <p>Every method logs its own failure at DEBUG with the URL — this is the one layer that sees
 * the raw HTTP outcome (status code, connection error) before it gets wrapped and re-surfaced
 * several layers up in each parser's {@code @CircuitBreaker} fallback, where only a generic
 * {@code Throwable} is visible with no URL attached. DEBUG (not WARN): a single request failure
 * here is expected/routine (retried by Resilience4j, or the parser's own fallback already logs
 * the aggregated outcome at WARN) — this is for correlating *which* URL/status a CB flap traces
 * back to when actually investigating, not for routine alerting.
 */
public class BookmakerHttpClient {

    public static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(16);

    private static final Logger log = LoggerFactory.getLogger(BookmakerHttpClient.class);

    private final WebClient webClient;

    public BookmakerHttpClient(WebClient webClient) {
        this.webClient = webClient;
    }

    /** GET → deserialize JSON response to Class<T>. */
    public <T> Mono<T> getJson(String url, Class<T> type) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(type)
                .doOnError(e -> log.debug("[HTTP] GET {} failed: {}", url, e.toString()));
    }

    /** GET → deserialize JSON response to a generic type (e.g. List<Foo>). */
    public <T> Mono<T> getJson(String url, TypeReference<T> type) {
        ParameterizedTypeReference<T> ref = ParameterizedTypeReference.forType(type.getType());
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(ref)
                .doOnError(e -> log.debug("[HTTP] GET {} failed: {}", url, e.toString()));
    }

    /**
     * GET → raw bytes.
     * GZIP decompression is handled at the Reactor Netty layer (compress=true in HttpClientConfig).
     */
    public Mono<byte[]> getGzip(String url) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(byte[].class)
                .doOnError(e -> log.debug("[HTTP] GET(gzip) {} failed: {}", url, e.toString()));
    }

    /** POST JSON body → deserialize response to Class<T>. */
    public <T> Mono<T> postJson(String url, Object body, Class<T> responseType) {
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType)
                .doOnError(e -> log.debug("[HTTP] POST {} failed: {}", url, e.toString()));
    }

    /** POST application/x-www-form-urlencoded → deserialize response to Class<T>. */
    public <T> Mono<T> postForm(String url, MultiValueMap<String, String> formData,
                                Class<T> responseType) {
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(formData)
                .retrieve()
                .bodyToMono(responseType)
                .doOnError(e -> log.debug("[HTTP] POST(form) {} failed: {}", url, e.toString()));
    }

    /** POST multipart/form-data → deserialize response to Class<T>. */
    public <T> Mono<T> postMultipart(String url, MultiValueMap<String, String> formData,
                                     Class<T> responseType) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        formData.forEach((key, values) -> values.forEach(v -> builder.part(key, v)));
        return webClient.post()
                .uri(url)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(responseType)
                .doOnError(e -> log.debug("[HTTP] POST(multipart) {} failed: {}", url, e.toString()));
    }

    /** Convenience — block with standard timeout; used by all parsers. */
    public <T> T blockGet(Mono<T> mono) {
        return mono.block(BLOCK_TIMEOUT);
    }

    WebClient webClient() { return webClient; }
}
