package com.valui.parser.bookmaker.betboom.ws;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real incident (24.09): BetBoom's WS gateway started rejecting every connection with
 * "Access rejected" — turned out to have nothing to do with headers, TLS fingerprint, IP, or
 * rate — it validates the {@code uuid} query param against a value tied to their own widget
 * build, not a per-user/per-session token. Confirmed by pulling BetBoom's own public widget
 * config and comparing: a captured real browser handshake's uuid worked 100% reproducibly;
 * any locally-generated random UUID was rejected 100% reproducibly.
 *
 * <p>That value is published in plain sight, unauthenticated, in BetBoom's own widget runtime
 * config:
 * <pre>
 * GET https://sportbook.sporthub.bet/widgets/sportbook/v1/modern/runtime-env.js
 * ...
 * "FEED_WS_URL_TEMPLATE": "wss://{{sporthubPartnerName}}-ws2.sporthub.bet:443/api/tree_ws/v1?uuid=&lt;THIS&gt;"
 * </pre>
 * tied to their {@code APP_BUILD} — so it changes whenever they redeploy the widget, hence the
 * periodic refresh here rather than a one-time fetch or a hardcoded constant.
 *
 * <p>Fetched on a background thread (like {@code FonbetEndpointPool}'s bootstrap probe) so a slow
 * or failing fetch never blocks Spring context startup or the WS pool's own connect path — slots
 * just read whatever {@link #getUuid()} currently holds, which is safe to call from the
 * connection-hot-path (in-memory read, no I/O).
 */
@Slf4j
@Component
public class BetBoomFeedUuidProvider {

    private static final String RUNTIME_ENV_URL =
            "https://sportbook.sporthub.bet/widgets/sportbook/v1/modern/runtime-env.js";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "FEED_WS_URL_TEMPLATE\"\\s*:\\s*\"[^\"]*[?&]uuid=([0-9a-fA-F-]{36})");
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 5_000;

    // null until the first successful fetch. Deliberately does NOT fall back to a locally
    // generated UUID.randomUUID() while null: we know from the incident that a random uuid is
    // reliably rejected, so a caller connecting before the first fetch completes would just
    // waste an attempt either way — falling back to null (see WsClientBorrowingPool) makes that
    // "we don't actually have a working uuid yet" state visible instead of silently trying a
    // value we already know fails.
    private final AtomicReference<String> currentUuid = new AtomicReference<>();

    @PostConstruct
    public void init() {
        Thread t = new Thread(this::refresh, "betboom-uuid-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    /** Their widget build (and therefore this uuid) doesn't change often, but re-fetching hourly
     *  is cheap insurance against another silent rejection if/when they do redeploy it. */
    @Scheduled(fixedRate = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    void scheduledRefresh() {
        refresh();
    }

    /** @return the last successfully fetched uuid, or null if no fetch has ever succeeded. */
    public String getUuid() {
        return currentUuid.get();
    }

    private void refresh() {
        try {
            applyBody(fetchBody());
        } catch (Exception e) {
            log.warn("[BetBoom-UUID] Failed to refresh from {}: {}", RUNTIME_ENV_URL, e.toString());
        }
    }

    /** Split out from {@link #refresh} so the regex-extraction logic is testable against a
     *  captured real response body without a network call. Package-private for that test. */
    void applyBody(String body) {
        Matcher m = UUID_PATTERN.matcher(body);
        if (!m.find()) {
            log.warn("[BetBoom-UUID] runtime-env.js fetched but FEED_WS_URL_TEMPLATE/uuid not found in it — keeping previous value");
            return;
        }
        String fresh = m.group(1);
        String previous = currentUuid.getAndSet(fresh);
        if (!fresh.equals(previous)) {
            log.info("[BetBoom-UUID] {} uuid={}", previous == null ? "Resolved" : "Rotated", fresh);
        }
    }

    private String fetchBody() throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(RUNTIME_ENV_URL).toURL().openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("Origin", "https://betboom.ru");
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
