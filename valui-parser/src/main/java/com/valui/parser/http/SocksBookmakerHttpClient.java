package com.valui.parser.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * BookmakerHttpClient backed by JDK's java.net.http.HttpClient + SOCKS5 proxy.
 *
 * Reactor Netty's SSL pipeline produces a JA3 fingerprint that 1xbet.kz rejects
 * even when HTTP/1.1 is forced. JDK's built-in HTTP client uses the same JSSE
 * TLS stack as HttpURLConnection, whose JA3 fingerprint 1xbet accepts.
 *
 * SOCKS5 tunnels the raw TCP connection — the TLS handshake happens end-to-end
 * between JDK and 1xbet, so the JA3 fingerprint is unaffected by the tunnel type.
 * SOCKS5 also forwards the hostname to the proxy for resolution, avoiding local
 * DNS failures for geo-restricted domains.
 */
public class SocksBookmakerHttpClient extends BookmakerHttpClient {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final HttpClient jdkClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SocksBookmakerHttpClient(ProxyProperties proxy) {
        // Pass a no-op WebClient to the parent — it won't be used for getJson calls
        super(HttpClientConfig.buildWebClient(null));

        // JDK consults the default Authenticator for SOCKS5 username/password auth.
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        proxy.getUsername(),
                        proxy.getPassword().toCharArray());
            }
        });

        InetSocketAddress proxyAddr = new InetSocketAddress(proxy.getHost(), proxy.getPort());
        jdkClient = HttpClient.newBuilder()
                .proxy(socksProxySelector(proxyAddr))
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    private static ProxySelector socksProxySelector(InetSocketAddress addr) {
        Proxy socks5Proxy = new Proxy(Proxy.Type.SOCKS, addr);
        return new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { return List.of(socks5Proxy); }
            @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
        };
    }

    @Override
    public <T> Mono<T> getJson(String url, Class<T> type) {
        return Mono.fromCallable(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Encoding", "gzip, deflate")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = jdkClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            checkStatus(response);
            byte[] body = decompress(response);
            return objectMapper.readValue(body, type);
        });
    }

    @Override
    public Mono<byte[]> getGzip(String url) {
        return Mono.fromCallable(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = jdkClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            checkStatus(response);
            return decompress(response);
        });
    }

    private static void checkStatus(HttpResponse<?> response) throws java.io.IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new java.io.IOException("HTTP " + status + " from " + response.uri());
        }
    }

    private static byte[] decompress(HttpResponse<byte[]> response) throws java.io.IOException {
        byte[] body = response.body();
        String encoding = response.headers().firstValue("Content-Encoding").orElse("");
        if ("gzip".equalsIgnoreCase(encoding)) {
            try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
                return in.readAllBytes();
            }
        }
        return body;
    }
}
