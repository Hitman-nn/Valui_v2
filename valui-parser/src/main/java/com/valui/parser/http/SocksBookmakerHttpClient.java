package com.valui.parser.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.nio.charset.StandardCharsets;
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
 *
 * JS-challenge: xbet returns HTML with Set-Cookie: __js_p_=N,TTL,sec,0,X.
 * The page JavaScript computes __jhash_ = get_jhash(N) and sets __jua_ = encoded UA,
 * then redirects to the same URL. This class replicates that computation in Java.
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

    // Precomputed once — encoding the UA is expensive for a constant string
    private static final String ENCODED_USER_AGENT = fixedEncodeURIComponent(USER_AGENT);

    // Challenge cookies are valid for 30 min (max-age=1800). Reusing them across requests
    // avoids repeated per-request challenges: once the domain "trusts" us, subsequent calls
    // (e.g. Get1x2_VZip after GetChampsZip) skip the challenge entirely.
    private static final long COOKIE_TTL_MS = 25L * 60 * 1000;

    private record ChallengeResult(String cookies, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() < expiresAt; }
    }

    private volatile ChallengeResult lastSolvedChallenge = null;

    private final HttpClient    jdkClient;
    private final ObjectMapper  objectMapper;

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
        return Mono.fromCallable(() -> objectMapper.readValue(fetchWithChallengeRetry(url), type));
    }

    @Override
    public <T> Mono<T> getJson(String url, TypeReference<T> type) {
        return Mono.fromCallable(() -> objectMapper.readValue(fetchWithChallengeRetry(url), type));
    }

    @Override
    public Mono<byte[]> getGzip(String url) {
        return Mono.fromCallable(() -> fetchWithChallengeRetry(url));
    }

    // ── JS-challenge resolution ───────────────────────────────────────────────

    /**
     * Fetches {@code url}, transparently solving xbet's {@code __js_p_} challenge when met.
     *
     * Flow:
     *   1. GET url → if HTML challenge: parse code from Set-Cookie __js_p_
     *   2. Compute __jhash_ = get_jhash(code)  (Java port of the JS function on the page)
     *   3. Retry with Cookie: __js_p_=…; __jhash_=…; __jua_=<encoded UA>
     *   4. Server validates and returns real JSON
     *
     * Each concurrent request gets its own unique challenge number, so cookies are
     * managed per-request in the Cookie header rather than in a shared CookieManager.
     */
    /**
     * Fetches {@code url}, transparently solving up to {@code MAX_CHALLENGE_ROUNDS} rounds of
     * xbet's {@code __js_p_} JS-challenge. Each round:
     *   1. Detect HTML body → extract code from Set-Cookie __js_p_
     *   2. Compute __jhash_ = get_jhash(code) — pure-Java port of the on-page JS function
     *   3. Retry with Cookie: __js_p_=…; __jhash_=…; __jua_=<encoded UA>
     *   4. If server replies 302: re-compute jhash if the redirect sets a NEW __js_p_, then follow
     *   5. If the follow-redirect body is still HTML, loop back to step 1
     */
    private byte[] fetchWithChallengeRetry(String url) throws IOException, InterruptedException {
        final int MAX_CHALLENGE_ROUNDS = 3;

        // Reuse cookies from a previously-solved challenge (valid 25 min).
        // Without this, each request starts fresh and the server challenges again,
        // even though GetChampsZip and Get1x2_VZip share the same domain session.
        ChallengeResult prev = lastSolvedChallenge;
        HttpRequest.Builder reqBuilder = buildRequest(url);
        if (prev != null && prev.isValid()) {
            reqBuilder = reqBuilder.header("Cookie", prev.cookies());
        }

        HttpRequest req = reqBuilder.build();
        HttpResponse<byte[]> resp = jdkClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        checkStatus(resp);
        byte[] body = decompress(resp);

        String currentUrl = url;

        for (int round = 0; round < MAX_CHALLENGE_ROUNDS && body.length > 0 && body[0] == '<'; round++) {
            String jsPValue = extractSetCookieValue(resp.headers().allValues("set-cookie"), "__js_p_");
            if (jsPValue == null) {
                log.warn("[XBET-CHALLENGE] No __js_p_ in challenge from {} (round {})", currentUrl, round + 1);
                break;
            }

            int code  = parseField(jsPValue, 0);
            int jhash = computeJhash(code);
            String cookies = "__js_p_=" + jsPValue + "; __jhash_=" + jhash + "; __jua_=" + ENCODED_USER_AGENT;

            log.debug("[XBET-CHALLENGE] Round {}: code={} jhash={} url={}", round + 1, code, jhash, currentUrl);
            lastSolvedChallenge = new ChallengeResult(cookies, System.currentTimeMillis() + COOKIE_TTL_MS);

            resp = jdkClient.send(buildRequest(currentUrl).header("Cookie", cookies).build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            // Server validates and replies with 302 redirect (same URL). Follow manually —
            // JDK would drop Cookie header on automatic redirect. If the 302 carries a NEW
            // __js_p_, solve it immediately so the follow-up request carries fresh cookies.
            if (resp.statusCode() == 302) {
                final String snapUrl = currentUrl;
                String location = resp.headers().firstValue("location")
                        .map(loc -> loc.startsWith("http") ? loc : snapUrl)
                        .orElse(snapUrl);

                String newJsP = extractSetCookieValue(resp.headers().allValues("set-cookie"), "__js_p_");
                if (newJsP != null && !newJsP.equals(jsPValue)) {
                    int nc  = parseField(newJsP, 0);
                    int nh  = computeJhash(nc);
                    cookies = "__js_p_=" + newJsP + "; __jhash_=" + nh + "; __jua_=" + ENCODED_USER_AGENT;
                    log.debug("[XBET-CHALLENGE] 302 new challenge: code={} jhash={}", nc, nh);
                } else {
                    String extra = buildCookieString(resp.headers().allValues("set-cookie"));
                    if (!extra.isEmpty()) cookies = cookies + "; " + extra;
                }

                currentUrl = location;
                resp = jdkClient.send(buildRequest(currentUrl).header("Cookie", cookies).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                log.debug("[XBET-CHALLENGE] Followed redirect → {}", currentUrl);
            }

            checkStatus(resp);
            body = decompress(resp);
        }

        checkNotHtml(body, resp.uri());
        return body;
    }

    /**
     * Java port of the {@code get_jhash(b)} function from xbet's JS challenge page:
     * <pre>
     * function get_jhash(b) {
     *   var x = 123456789; var i = 0; var k = 0;
     *   for (i = 0; i &lt; 1677696; i++) {
     *     x = ((x + b) ^ (x + (x%3) + (x%17) + b) ^ i) % 16776960;
     *     if (x % 117 == 0) { k = (k + 1) % 1111; }
     *   }
     *   return k;
     * }
     * </pre>
     * All intermediate values stay within Java {@code int} range (max ≈ 2^24),
     * so no long/BigInteger needed.
     */
    static int computeJhash(int b) {
        int x = 123456789;
        int k = 0;
        for (int i = 0; i < 1677696; i++) {
            x = ((x + b) ^ (x + (x % 3) + (x % 17) + b) ^ i) % 16776960;
            if (x % 117 == 0) {
                k = (k + 1) % 1111;
            }
        }
        return k;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static HttpRequest.Builder buildRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent",        USER_AGENT)
                .header("Accept",            "application/json, text/plain, */*")
                .header("Accept-Language",   "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding",   "gzip, deflate")
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

    /** Builds a semicolon-joined Cookie string from a list of Set-Cookie header values. */
    private static String buildCookieString(List<String> setCookieHeaders) {
        StringBuilder sb = new StringBuilder();
        for (String header : setCookieHeaders) {
            String trimmed = header.trim();
            int end = trimmed.indexOf(';');
            String nameValue = end > 0 ? trimmed.substring(0, end).trim() : trimmed;
            if (sb.length() > 0) sb.append("; ");
            sb.append(nameValue);
        }
        return sb.toString();
    }

    /** Finds the value of a named cookie in a list of Set-Cookie header strings. */
    private static String extractSetCookieValue(List<String> setCookieHeaders, String name) {
        String prefix = name + "=";
        for (String header : setCookieHeaders) {
            String trimmed = header.trim();
            if (trimmed.startsWith(prefix)) {
                int end = trimmed.indexOf(';');
                return end > 0
                        ? trimmed.substring(prefix.length(), end).trim()
                        : trimmed.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** Parses a comma-separated value at {@code index} from a cookie value string. */
    private static int parseField(String csv, int index) {
        String[] parts = csv.split(",");
        if (index >= parts.length) return 0;
        try { return Integer.parseInt(parts[index].trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * JavaScript's {@code fixedEncodeURIComponent}: encodes everything except
     * unreserved URI characters (A-Z a-z 0-9 - _ . ~).
     */
    private static String fixedEncodeURIComponent(String str) {
        StringBuilder sb = new StringBuilder(str.length() * 3);
        for (char c : str.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    sb.append(String.format("%%%02X", b & 0xFF));
                }
            }
        }
        return sb.toString();
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
