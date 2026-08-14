package com.valui.parser.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

import java.time.Duration;

@Slf4j
@Configuration
public class HttpClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Bean @Qualifier("xbetHttpClient")
    public BookmakerHttpClient xbetHttpClient(ProxyProperties proxy, ObjectMapper objectMapper) {
        if (proxy.isEnabled()) {
            // JDK HttpClient + HTTP CONNECT proxy — avoids Reactor Netty's JA3 fingerprint
            // that 1xbet.kz rejects. JDK JSSE TLS matches the fingerprint 1xbet accepts.
            log.info("[HTTP-CONFIG] xbet: using SocksBookmakerHttpClient via proxy {}:{}",
                    proxy.getHost(), proxy.getPort());
            return new SocksBookmakerHttpClient(proxy, objectMapper);
        }
        log.info("[HTTP-CONFIG] xbet: using direct BookmakerHttpClient (no proxy)");
        return new BookmakerHttpClient(buildWebClient(null));
    }

    @Bean @Qualifier("fonbetHttpClient")
    public BookmakerHttpClient fonbetHttpClient() {
        // Fonbet CDN mirrors (bk6bba-resources.com) resolve correctly via JVM InetAddress.
        // Routing through a proxy adds a single point of failure — not used here.
        // 50 MB buffer — Fonbet CDN responses regularly exceed the default 10 MB limit.
        // maxIdleTime=45s: Fonbet CDN closes idle keep-alive connections after ~60s;
        // evicting before that prevents PrematureCloseException on connection reuse.
        //
        // maxConnections/pendingAcquireMaxCount set explicitly — left at Reactor Netty's
        // defaults (max(cores,8)*2 connections, 2x that pending queue) this pool is sized
        // for a handful of callers, not the ~338 Fonbet controllers that all share this one
        // singleton bean. FonbetParser.fetchSnapshot() now single-flights refreshes via a
        // lock so steady-state concurrency here is low, but this still gives headroom for
        // legitimate overlap (retries, first-hit races) instead of rejecting outright with
        // "Pending acquire queue has reached its maximum size" the moment a burst hits.
        ConnectionProvider provider = ConnectionProvider.builder("fonbet-pool")
                .maxConnections(32)
                .pendingAcquireMaxCount(200)
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .maxIdleTime(Duration.ofSeconds(45))
                .maxLifeTime(Duration.ofMinutes(4))
                .evictInBackground(Duration.ofSeconds(60))
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .protocol(HttpProtocol.HTTP11)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT)
                .compress(true)
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .headers(h -> h.set(HttpHeaders.USER_AGENT, USER_AGENT));
        return new BookmakerHttpClient(WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build());
    }

    @Bean @Qualifier("olimpHttpClient")
    public BookmakerHttpClient olimpHttpClient() {
        // planned-events returns Olimp's FULL event catalog in one response and has already
        // outgrown this limit twice (originally 10 MB, bumped to 32 MB, now exceeding that too —
        // see DataBufferLimitException bursts in prod on 2026-08-14, ~92-96% olimp-cb failure
        // rate for ~28min each time until the catalog happened to shrink back under the limit).
        // 64 MB for headroom, matching the same problem already solved for Fonbet below (50 MB).
        // If this keeps growing, the real fix is switching /planned-events to a streaming JSON
        // parse instead of buffering the whole body — not attempted here.
        return new BookmakerHttpClient(buildWebClient(null, 64 * 1024 * 1024));
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
        return buildWebClient(proxy, 10 * 1024 * 1024);
    }

    static WebClient buildWebClient(ProxyProperties proxy, int maxInMemorySize) {
        boolean usingProxy = proxy != null && proxy.isEnabled();

        HttpClient httpClient = HttpClient.create()
                // Force HTTP/1.1 — removes ALPN extension from TLS ClientHello.
                // Netty's default HTTP/2 ALPN negotiation changes the JA3 fingerprint vs
                // plain HttpURLConnection, causing servers like BetCity/XBet to reject the
                // TLS handshake with a TCP RST. HTTP/1.1 matches the fingerprint they expect.
                .protocol(HttpProtocol.HTTP11)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT)
                .compress(true)
                .headers(h -> h.set(HttpHeaders.USER_AGENT, USER_AGENT));

        if (!usingProxy) {
            // Without proxy: use JVM InetAddress so macOS system resolver handles CDN mirrors
            // (e.g. bk6bba-resources.com) that Netty's async DNS fails to resolve.
            httpClient = httpClient.resolver(DefaultAddressResolverGroup.INSTANCE);
        }
        // With HTTP proxy: keep Netty's default async DNS so it passes the hostname to the
        // proxy rather than resolving it locally first — avoids DNS failure for CDN mirrors.

        if (usingProxy) {
            httpClient = httpClient.proxy(spec -> spec
                    .type(ProxyProvider.Proxy.HTTP)
                    .host(proxy.getHost())
                    .port(proxy.getPort())
                    .username(proxy.getUsername())
                    .password(p -> proxy.getPassword()));
        }

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxInMemorySize))
                .build();
    }
}
