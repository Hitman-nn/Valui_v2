package com.valui.parser.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
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
import java.util.zip.GZIPInputStream;

/**
 * BookmakerHttpClient backed by JDK's java.net.http.HttpClient + HTTP CONNECT proxy.
 *
 * Reactor Netty's SSL pipeline produces a JA3 fingerprint that 1xbet.kz rejects
 * even when HTTP/1.1 is forced. JDK's java.net.http.HttpClient uses JSSE (the same
 * platform TLS stack as HttpURLConnection), whose JA3 fingerprint 1xbet accepts.
 *
 * HTTP CONNECT is used instead of SOCKS5 because SOCKS5 auth via the global
 * Authenticator has known reliability issues across JDK versions. HTTP CONNECT +
 * explicit Authenticator on the builder is the officially supported path.
 * The TLS handshake still happens end-to-end (JDK JSSE ↔ 1xbet), so the JA3
 * fingerprint is identical regardless of tunnel type (SOCKS5 vs HTTP CONNECT).
 *
 * Requires port 4232 (HTTP proxy), not 14232 (SOCKS5).
 */
public class SocksBookmakerHttpClient extends BookmakerHttpClient {

    static {
        // JDK 8u111+ disables Basic auth in proxy CONNECT tunnels by default.
        // Clearing both lists re-enables it so our HTTP proxy can authenticate.
        // Scoped to this JVM process; does not affect network security of the app itself.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes",  "");
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final HttpClient jdkClient;
    private final ObjectMapper objectMapper;

    public SocksBookmakerHttpClient(ProxyProperties proxy, ObjectMapper objectMapper) {
        super(HttpClientConfig.buildWebClient(null));
        this.objectMapper = objectMapper;

        InetSocketAddress proxyAddr = new InetSocketAddress(proxy.getHost(), proxy.getPort());
        jdkClient = HttpClient.newBuilder()
                .proxy(httpProxySelector(proxyAddr))
                .authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(
                                proxy.getUsername(),
                                proxy.getPassword().toCharArray());
                    }
                })
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    private static ProxySelector httpProxySelector(InetSocketAddress addr) {
        Proxy httpProxy = new Proxy(Proxy.Type.HTTP, addr);
        return new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { return List.of(httpProxy); }
            @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
        };
    }

    @Override
    public <T> Mono<T> getJson(String url, Class<T> type) {
        return Mono.fromCallable(() -> {
            HttpRequest req = buildRequest(url).build();
            HttpResponse<byte[]> resp = jdkClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            checkStatus(resp);
            return objectMapper.readValue(decompress(resp), type);
        });
    }

    @Override
    public <T> Mono<T> getJson(String url, TypeReference<T> type) {
        return Mono.fromCallable(() -> {
            HttpRequest req = buildRequest(url).build();
            HttpResponse<byte[]> resp = jdkClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            checkStatus(resp);
            return objectMapper.readValue(decompress(resp), type);
        });
    }

    @Override
    public Mono<byte[]> getGzip(String url) {
        return Mono.fromCallable(() -> {
            HttpRequest req = buildRequest(url).build();
            HttpResponse<byte[]> resp = jdkClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            checkStatus(resp);
            return decompress(resp);
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static HttpRequest.Builder buildRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "gzip, deflate")
                .timeout(Duration.ofSeconds(20))
                .GET();
    }

    private static void checkStatus(HttpResponse<?> response) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + response.uri());
        }
    }

    private static byte[] decompress(HttpResponse<byte[]> response) throws IOException {
        byte[] body = response.body();
        String encoding = response.headers().firstValue("Content-Encoding").orElse("");
        if ("gzip".equalsIgnoreCase(encoding)) {
            try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return in.readAllBytes();
            }
        }
        return body;
    }
}
