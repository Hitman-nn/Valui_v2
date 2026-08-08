package com.valui.parser.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import org.springframework.scheduling.annotation.Scheduled;

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
import java.util.concurrent.TimeUnit;
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
        // Hard-cap JDK HttpClient connection pool to match httpSlots semaphore.
        // Without this, stale keep-alive connections being replaced by new ones can
        // briefly push the visible TCP connection count above the semaphore limit.
        System.setProperty("jdk.httpclient.connectionPoolSize", "15");
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

    // Hard limit on concurrent HTTP calls through this proxy-backed client.
    // xbet blocks at ≥25 simultaneous TCP connections (empirically confirmed).
    // 15 slots gives 10 connections of headroom; matches connectionPoolSize above.
    private static final int HTTP_SLOTS = 15;
    private final java.util.concurrent.Semaphore httpSlots =
            new java.util.concurrent.Semaphore(HTTP_SLOTS, true);

    // Limits concurrent active challenge-solving to 2 threads.
    // With 79 xbet controllers starting simultaneously, unconstrained parallel solving floods
    // the proxy with ~160 concurrent HTTP calls and causes 502/timeout errors.
    // Threads waiting for a slot benefit from the result cached by whoever solves first.
    private final java.util.concurrent.Semaphore challengeSlots =
            new java.util.concurrent.Semaphore(2, true);

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

    @Scheduled(fixedDelay = 60_000)
    public void logConnectionMetrics() {
        int available = httpSlots.availablePermits();
        int used = HTTP_SLOTS - available;
        // Promoted to WARN near saturation: this pool is sized against xbet's documented
        // ≥25-connection block threshold — sustained high usage here is a direct precursor to
        // that block (and the CB flapping it causes), previously visible only at DEBUG.
        if (used >= HTTP_SLOTS * 0.8) {
            log.warn("[XBET] http-slots: {}/{} in use ({} available) — approaching saturation " +
                    "(block threshold ~25)", used, HTTP_SLOTS, available);
        } else {
            log.debug("[XBET] http-slots: {}/{} in use, {} available (threshold ~25)",
                    used, HTTP_SLOTS, available);
        }
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
     * Fetches {@code url} solving up to {@code MAX_ROUNDS} rounds of xbet's {@code __js_p_}
     * JS-challenge.  Cached cookies from a prior solved challenge are sent on the first
     * request so that sibling calls (e.g. Get1x2_VZip after GetChampsZip) skip the challenge.
     *
     * Flow per round:
     *   1. Send request (with cookies if available)
     *   2. Follow any 302 redirect, re-solving if the redirect carries a new __js_p__
     *   3. If body is JSON → return it
     *   4. If body is HTML challenge → compute jhash, store cookies, loop
     */
    private byte[] fetchWithChallengeRetry(String url) throws IOException, InterruptedException {
        final int MAX_ROUNDS = 3;

        ChallengeResult prev = lastSolvedChallenge;
        String currentUrl = url;
        String cookies = (prev != null && prev.isValid()) ? prev.cookies() : null;

        for (int round = 0; round <= MAX_ROUNDS; round++) {
            HttpResponse<byte[]> resp = sendWith(currentUrl, cookies);

            // Follow 302 redirect (checkStatus below would throw on 302 otherwise).
            // The redirect may carry a new __js_p__ — solve it before following.
            if (resp.statusCode() == 302) {
                final String snap = currentUrl;
                currentUrl = resp.headers().firstValue("location")
                        .map(loc -> loc.startsWith("http") ? loc : snap).orElse(snap);
                String newJsP = extractSetCookieValue(resp.headers().allValues("set-cookie"), "__js_p_");
                cookies = newJsP != null
                        ? challengeResponse(newJsP)
                        : merge(cookies, buildCookieString(resp.headers().allValues("set-cookie")));
                resp = sendWith(currentUrl, cookies);
                log.debug("[XBET-CHALLENGE] Followed redirect → {}", currentUrl);
            }

            checkStatus(resp);
            byte[] body = decompress(resp);
            if (body.length == 0 || body[0] != '<') return body;

            // HTML challenge — out of rounds?
            if (round >= MAX_ROUNDS) break;

            String jsPValue = extractSetCookieValue(resp.headers().allValues("set-cookie"), "__js_p_");
            if (jsPValue == null) {
                // Another concurrent thread may have just consumed our challenge N and cached its result.
                // Try their cookies before giving up.
                ChallengeResult concurrent = lastSolvedChallenge;
                if (concurrent != null && concurrent.isValid()) {
                    log.debug("[XBET-CHALLENGE] No __js_p_ (round {}), using concurrent solver cookies", round + 1);
                    cookies = concurrent.cookies();
                } else {
                    // No __js_p_ cookie at all on the first round is the strongest available
                    // signal that this isn't a solvable JS challenge but an actual geo-block/ban
                    // page — worth calling out distinctly from a generic "captcha/block" message.
                    log.warn("[XBET-CHALLENGE] No __js_p_ cookie in response (round {}) from {} — " +
                            "possible geo-block/ban page rather than a solvable challenge",
                            round + 1, currentUrl);
                    break;
                }
            } else {
                // Throttle concurrent challenge-solving to avoid proxy overload.
                // Wait up to 3s for a slot; if we get one we solve ourselves,
                // otherwise check if another thread already cached a fresh result.
                boolean acquired = challengeSlots.tryAcquire(3, TimeUnit.SECONDS);
                try {
                    ChallengeResult concurrent = lastSolvedChallenge;
                    if (concurrent != null && concurrent.isValid()) {
                        log.debug("[XBET-CHALLENGE] Using concurrent solver cookies (round {})", round + 1);
                        cookies = concurrent.cookies();
                    } else {
                        if (!acquired) {
                            // Safety-valve bypass of the documented "max 2 concurrent
                            // solvers" invariant — worth knowing about since it means we're
                            // actively contributing to whatever proxy load the throttle
                            // exists to prevent.
                            log.warn("[XBET-CHALLENGE] Could not acquire challenge slot within 3s " +
                                    "for {} — solving without throttle", currentUrl);
                        }
                        cookies = challengeResponse(jsPValue);
                        log.debug("[XBET-CHALLENGE] Round {}: code={}", round + 1, parseField(jsPValue, 0));
                    }
                } finally {
                    if (acquired) challengeSlots.release();
                }
            }
        }

        log.warn("[XBET-CHALLENGE] Exhausted {} rounds solving challenge for {} — giving up",
                MAX_ROUNDS, currentUrl);
        throw new IOException("HTML response (captcha/block) from " + currentUrl);
    }

    /** Sends one request, including the Cookie header when cookies is non-null. */
    private HttpResponse<byte[]> sendWith(String url, String cookies)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = buildRequest(url);
        if (cookies != null && !cookies.isEmpty()) b = b.header("Cookie", cookies);
        httpSlots.acquire();
        try {
            HttpResponse<byte[]> resp = jdkClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            // The single per-request status/size trace for xbet's whole HTTP path — previously
            // absent even at DEBUG, so diagnosing this bookmaker's connectivity meant either the
            // 60s-aggregated slot metrics above or the eventual wrapped IOException at the
            // parser's CB fallback layer, with nothing in between.
            log.debug("[XBET-HTTP] {} → {} ({} bytes)", url, resp.statusCode(), resp.body().length);
            return resp;
        } finally {
            httpSlots.release();
        }
    }

    /** Builds challenge-response cookies, caching them for subsequent requests. */
    private String challengeResponse(String jsPValue) {
        int code  = parseField(jsPValue, 0);
        int jhash = computeJhash(code);
        String c  = "__js_p_=" + jsPValue + "; __jhash_=" + jhash + "; __jua_=" + ENCODED_USER_AGENT;
        lastSolvedChallenge = new ChallengeResult(c, System.currentTimeMillis() + COOKIE_TTL_MS);
        log.debug("[XBET-CHALLENGE] code={} jhash={}", code, jhash);
        return c;
    }

    /** Merges two cookie strings; returns null only if both are empty. */
    private static String merge(String base, String extra) {
        if (base == null || base.isEmpty()) return (extra == null || extra.isEmpty()) ? null : extra;
        if (extra == null || extra.isEmpty()) return base;
        return base + "; " + extra;
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
