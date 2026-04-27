package com.valui.parser.http;

import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.HttpProtocol;
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
        if (proxy.isEnabled()) {
            // JDK HttpClient + SOCKS5 — avoids Reactor Netty's JA3 fingerprint
            // that 1xbet.kz rejects. JDK's JSSE TLS matches HttpURLConnection's
            // fingerprint which 1xbet accepts.
            return new SocksBookmakerHttpClient(proxy);
        }
        return new BookmakerHttpClient(buildWebClient(null));
    }

    @Bean @Qualifier("fonbetHttpClient")
    public BookmakerHttpClient fonbetHttpClient(ProxyProperties proxy) {
        return new BookmakerHttpClient(buildWebClient(proxy.isEnabled() ? proxy : null));
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
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
}
