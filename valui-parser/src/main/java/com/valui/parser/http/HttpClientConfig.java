package com.valui.parser.http;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.time.Duration;

@Configuration
@EnableScheduling
public class HttpClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Bean @Qualifier("xbetHttpClient")
    public BookmakerHttpClient xbetHttpClient(ProxyProperties proxy) {
        return new BookmakerHttpClient(buildWebClient(proxy.isEnabled() ? proxy : null));
    }

    @Bean @Qualifier("fonbetHttpClient")
    public BookmakerHttpClient fonbetHttpClient() {
        return new BookmakerHttpClient(buildWebClient(null));
    }

    @Bean @Qualifier("olimpHttpClient")
    public BookmakerHttpClient olimpHttpClient() {
        return new BookmakerHttpClient(buildWebClient(null));
    }

    @Bean @Qualifier("betcityHttpClient")
    public BookmakerHttpClient betcityHttpClient() {
        return new BookmakerHttpClient(buildWebClient(null));
    }

    @Bean @Qualifier("betboomHttpClient")
    public BookmakerHttpClient betboomHttpClient() {
        // BetBoom uses WS, but HTTP fallbacks use the same client
        return new BookmakerHttpClient(buildWebClient(null));
    }

    // ── builder ───────────────────────────────────────────────────────────────

    static WebClient buildWebClient(ProxyProperties proxy) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT)
                .compress(true)  // auto Accept-Encoding: gzip + transparent decompression
                .headers(h -> h.set(HttpHeaders.USER_AGENT, USER_AGENT));

        if (proxy != null && proxy.isEnabled()) {
            httpClient = httpClient.proxy(spec -> spec
                    .type(ProxyProvider.Proxy.HTTP)
                    .host(proxy.getHost())
                    .port(proxy.getPort())
                    .username(proxy.getUsername())
                    .password(p -> proxy.getPassword()));
        }

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
