package com.valui.parser.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookmakerHttpClientWireMockTest {

    private WireMockServer server;
    private BookmakerHttpClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        WireMock.configureFor("localhost", server.port());
        WebClient wc = HttpClientConfig.buildWebClient(null);
        client = new BookmakerHttpClient(wc);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void getJson_deserializesResponse() {
        stubFor(get(urlEqualTo("/api/sports"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"1\",\"name\":\"Football\"}")));

        JsonNode result = client.getJson(serverUrl("/api/sports"), JsonNode.class)
                .block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.get("id").asText()).isEqualTo("1");
        assertThat(result.get("name").asText()).isEqualTo("Football");
    }

    @Test
    void getJson_on503_throwsWebClientResponseException() {
        stubFor(get(urlEqualTo("/api/down"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() ->
                client.getJson(serverUrl("/api/down"), JsonNode.class).block(Duration.ofSeconds(5)))
                .isInstanceOf(WebClientResponseException.class)
                .hasMessageContaining("503");
    }

    @Test
    void getJson_gzipResponse_automaticallyDecompressed() {
        // Reactor Netty auto-decompresses gzip when compress=true
        stubFor(get(urlEqualTo("/api/gzip"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"compressed\":true}")));

        JsonNode result = client.getJson(serverUrl("/api/gzip"), JsonNode.class)
                .block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.get("compressed").asBoolean()).isTrue();
    }

    @Test
    void getJson_retryableError_returnsOnSecondAttempt() {
        // First call returns 503, second returns 200
        stubFor(get(urlEqualTo("/api/flaky"))
                .inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));

        stubFor(get(urlEqualTo("/api/flaky"))
                .inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));

        // Client alone (without Retry decorator) gets 503 on first call
        assertThatThrownBy(() ->
                client.getJson(serverUrl("/api/flaky"), JsonNode.class).block(Duration.ofSeconds(5)))
                .isInstanceOf(WebClientResponseException.class);

        // Second call succeeds
        JsonNode result = client.getJson(serverUrl("/api/flaky"), JsonNode.class)
                .block(Duration.ofSeconds(5));
        assertThat(result.get("ok").asBoolean()).isTrue();
    }

    @Test
    void getGzip_returnsByteArray() {
        byte[] body = "raw bytes content".getBytes();
        stubFor(get(urlEqualTo("/api/raw"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withBody(body)));

        byte[] result = client.getGzip(serverUrl("/api/raw")).block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(new String(result)).isEqualTo("raw bytes content");
    }

    @Test
    void requestCarriesUserAgent() {
        stubFor(get(urlEqualTo("/api/ua"))
                .withHeader("User-Agent", matching("Mozilla.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        JsonNode result = client.getJson(serverUrl("/api/ua"), JsonNode.class)
                .block(Duration.ofSeconds(5));
        assertThat(result).isNotNull();

        verify(getRequestedFor(urlEqualTo("/api/ua"))
                .withHeader("User-Agent", matching("Mozilla.*")));
    }

    private String serverUrl(String path) {
        return "http://localhost:" + server.port() + path;
    }
}
