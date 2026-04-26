package com.valui.parser.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Thin reactive wrapper over a pre-configured WebClient.
 * Parsers call .block(BLOCK_TIMEOUT) on the returned Mono.
 */
public class BookmakerHttpClient {

    public static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(20);

    private final WebClient webClient;

    public BookmakerHttpClient(WebClient webClient) {
        this.webClient = webClient;
    }

    /** GET → deserialize JSON response to Class<T>. */
    public <T> Mono<T> getJson(String url, Class<T> type) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(type);
    }

    /** GET → deserialize JSON response to a generic type (e.g. List<Foo>). */
    public <T> Mono<T> getJson(String url, TypeReference<T> type) {
        ParameterizedTypeReference<T> ref = ParameterizedTypeReference.forType(type.getType());
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(ref);
    }

    /**
     * GET → raw bytes.
     * GZIP decompression is handled at the Reactor Netty layer (compress=true in HttpClientConfig).
     */
    public Mono<byte[]> getGzip(String url) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(byte[].class);
    }

    /** POST JSON body → deserialize response to Class<T>. */
    public <T> Mono<T> postJson(String url, Object body, Class<T> responseType) {
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType);
    }

    /** POST application/x-www-form-urlencoded → deserialize response to Class<T>. */
    public <T> Mono<T> postForm(String url, MultiValueMap<String, String> formData,
                                Class<T> responseType) {
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(formData)
                .retrieve()
                .bodyToMono(responseType);
    }

    /** Convenience — block with standard timeout; used by all parsers. */
    public <T> T blockGet(Mono<T> mono) {
        return mono.block(BLOCK_TIMEOUT);
    }

    WebClient webClient() { return webClient; }
}
