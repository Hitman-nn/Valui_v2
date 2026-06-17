package com.valui.parser.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieManager;
import java.net.CookiePolicy;
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
@Slf4j
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
                // Accept all cookies so xbet's __js_p_ JS-challenge cookie is stored
                // automatically and re-sent on subsequent requests. Without this the client
                // looks like a fresh bot on every request and xbet returns an HTML challenge
                // instead of JSON.
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
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
            byte[] body = fetchWithChallengeRetry(url);
            return objectMapper.readValue(body, type);
        });
    }

    @Override
    public <T> Mono<T> getJson(String url, TypeReference<T> type) {
        return Mono.fromCallable(() -> {
            byte[] body = fetchWithChallengeRetry(url);
            return objectMapper.readValue(body, type);
        });
    }

    @Override
    public Mono<byte[]> getGzip(String url) {
        return Mono.fromCallable(() -> {
            byte[] body = fetchWithChallengeRetry(url);
            return body;
        });
    }

    /**
     * Fetches {@code url} and handles xbet's {@code __js_p_} JS-challenge transparently:
     * if the first response is an HTML challenge page, the {@link CookieManager} already
     * stored the {@code Set-Cookie} header, so a single immediate retry sends the cookie
     * back and receives real JSON. This keeps circuit-breaker failure counts clean —
     * the challenge handshake is invisible to callers.
     */
    private byte[] fetchWithChallengeRetry(String url) throws IOException, InterruptedException {
        HttpRequest req = buildRequest(url).build();
        HttpResponse<byte[]> resp = jdkClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        checkStatus(resp);
        byte[] body = decompress(resp);
        if (body.length > 0 && body[0] == '<') {
            // Log the challenge HTML once so we can analyze the algorithm
            log.warn("[XBET-CHALLENGE] HTML challenge from {} — cookies={} body={}",
                    resp.uri(),
                    resp.headers().allValues("Set-Cookie"),
                    new String(body, 0, Math.min(body.length, 800), java.nio.charset.StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " "));
            // Challenge received — CookieManager stored __js_p_; retry sends it back
            resp = jdkClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            checkStatus(resp);
            body = decompress(resp);
        }
        checkNotHtml(body, resp.uri());
        return body;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static HttpRequest.Builder buildRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent",        USER_AGENT)
                .header("Accept",            "application/json, text/plain, */*")
                .header("Accept-Language",   "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding",   "gzip, deflate, br")
                .header("Referer",           "https://1xbet.kz/")
                .header("Origin",            "https://1xbet.kz")
                .header("sec-fetch-dest",    "empty")
                .header("sec-fetch-mode",    "cors")
                .header("sec-fetch-site",    "same-origin")
                .header("sec-ch-ua",         "\"Google Chrome\";v=\"120\", \"Chromium\";v=\"120\", \"Not-A.Brand\";v=\"99\"")
                .header("sec-ch-ua-mobile",  "?0")
                .header("sec-ch-ua-platform","\"Windows\"")
                .timeout(Duration.ofSeconds(20))
                .GET();
    }

    private static void checkStatus(HttpResponse<?> response) throws IOException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + response.uri());
        }
    }

    private static void checkNotHtml(byte[] body, java.net.URI uri) throws IOException {
        if (body.length > 0 && body[0] == '<') {
            throw new IOException("HTML response (captcha/block) from " + uri);
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
