package com.valui.parser.bookmaker.olimp;

import com.fasterxml.jackson.databind.JsonNode;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.http.BookmakerHttpClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static com.valui.parser.http.BookmakerHttpClient.BLOCK_TIMEOUT;
import static com.valui.parser.util.ExceptionDescriptions.describe;

@Slf4j
@Component
public class OlimpParser implements BookmakerParser {

    private static final String DEFAULT_BASE = "https://www.olimp.bet/api/v4/0/line";

    // Snapshot TTL: each endpoint returns the FULL catalog (planned-events alone is
    // ~2600 events / ~20MB decoded), filtered client-side per sportId/tournamentId.
    // Without this cache, every one of the ~30 Olimp controllers re-fetches and
    // re-parses the whole catalog into a fresh Jackson tree on every single poll;
    // concurrent overlapping polls (Olimp is the slowest bookmaker — see fetch-budget
    // timeouts) pile up many such multi-hundred-MB trees at once and exhaust the
    // heap. Same pattern as FonbetParser's snapCache, one cache per endpoint since
    // Olimp exposes three separate endpoints instead of Fonbet's single combined one.
    private static final long SNAP_TTL_MS = 20_000;

    // How long a failed refresh "poisons" its lock for every other waiter — see fetchCached()'s
    // fail-fast check. Same convoy risk as FonbetParser (see its FAILURE_COOLDOWN_MS javadoc for
    // the production incident that motivated this): every one of the ~30 Olimp controllers
    // queued behind a dead endpoint would otherwise make its own doomed ~20MB HTTP attempt in
    // turn (up to BLOCK_TIMEOUT each) instead of failing immediately once the first one already has.
    private static final long FAILURE_COOLDOWN_MS = 2_000;

    // How long an expired snapshot may still be served while a background refresh replaces it.
    // 01.10 prod logs: planned-events refreshes ran in the CALLER's thread, so a slow ~20MB
    // fetch hit that caller's 8s fetch budget, got interrupted, tripped the failure cooldown —
    // and every Olimp controller queued on the lock meanwhile (all 13 DRR slots) then failed
    // fast at once: 5 bursts of 36–52 errors in 8h. Serving the previous snapshot during the
    // refresh keeps callers off the lock entirely; past this bound a stale snapshot is no longer
    // trusted and callers fall back to the blocking path, so a real outage still surfaces.
    private static final long MAX_STALE_MS = 90_000;

    private final String sportsApi;
    private final String champsApi;
    private final String eventsApi;
    private final BookmakerHttpClient http;
    private final long snapTtlMs;

    private final SnapSlot sportsSlot = new SnapSlot();
    private final SnapSlot champsSlot = new SnapSlot();
    private final SnapSlot eventsSlot = new SnapSlot();

    private record CachedSnap(JsonNode data, long ts) {}

    /** Per-endpoint cache state — see {@link #fetchCached}. */
    private static final class SnapSlot {
        final AtomicReference<CachedSnap> cache = new AtomicReference<>();
        // Guards the refresh. ReentrantLock, not synchronized — the refresh holds the lock across
        // a blocking HTTP call, and synchronized pins the carrier thread of every virtual thread
        // queued on it for that whole duration (up to BLOCK_TIMEOUT); ReentrantLock lets waiters unmount.
        final ReentrantLock lock = new ReentrantLock();
        // Last-failure timestamp (ms) — 0 = none / cleared on success.
        final AtomicLong lastFailureAtMs = new AtomicLong(0L);
        // Single-flight guard for the background (stale-while-revalidate) refresh.
        final AtomicBoolean refreshing = new AtomicBoolean(false);
    }

    @Autowired
    public OlimpParser(@Qualifier("olimpHttpClient") BookmakerHttpClient http) {
        this(DEFAULT_BASE, http);
    }

    OlimpParser(String apiBase, BookmakerHttpClient http) {
        this(apiBase, http, SNAP_TTL_MS);
    }

    OlimpParser(String apiBase, BookmakerHttpClient http, long snapTtlMs) {
        this.sportsApi = apiBase + "/sports";
        this.champsApi = apiBase + "/sports-with-competitions";
        this.eventsApi = apiBase + "/planned-events";
        this.http = http;
        this.snapTtlMs = snapTtlMs;
    }

    OlimpParser(String apiBase, org.springframework.web.reactive.function.client.WebClient wc) {
        this(apiBase, new BookmakerHttpClient(wc));
    }

    @Override
    public BookmakerType getBookmaker() { return BookmakerType.OLIMP; }

    @CircuitBreaker(name = "olimp-cb", fallbackMethod = "fetchSportsFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<SportDto>> fetchSports() {
        long start = ms();
        JsonNode arr = fetchCached(sportsSlot, sportsApi);
        List<SportDto> sports = new ArrayList<>();
        for (JsonNode item : iter(arr)) {
            JsonNode p = item.path("payload");
            String id = s(p, "id"), name = s(p, "name");
            if (id != null && name != null) sports.add(new SportDto(id, name, s(p, "alias")));
        }
        return ParseResult.ok(sports, ms() - start);
    }

    @CircuitBreaker(name = "olimp-cb", fallbackMethod = "fetchTournamentsFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = ms();
        JsonNode arr = fetchCached(champsSlot, champsApi);
        List<TournamentDto> tournaments = new ArrayList<>();
        for (JsonNode item : iter(arr)) {
            JsonNode p = item.path("payload");
            if (!sportId.equals(s(p, "id"))) continue;
            JsonNode competitions = p.path("competitions");
            if (!competitions.isArray()) continue;
            for (JsonNode comp : competitions) {
                String id = s(comp, "id"), name = s(comp, "name"), sId = s(comp, "sportId");
                if (id == null || name == null) continue;
                String eff = sId != null ? sId : sportId;
                tournaments.add(new TournamentDto(id, name, eff, null,
                        "https://www.olimp.bet/line/" + eff + "/" + id));
            }
        }
        return ParseResult.ok(tournaments, ms() - start);
    }

    @CircuitBreaker(name = "olimp-cb", fallbackMethod = "fetchMatchesFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<ParsedMatchDto>> fetchMatches(String tournamentId) {
        long start = ms();
        JsonNode arr = fetchCached(eventsSlot, eventsApi);
        List<ParsedMatchDto> matches = new ArrayList<>();
        for (JsonNode item : iter(arr)) {
            JsonNode p = item.path("payload");
            if (!tournamentId.equals(s(p, "competitionId"))) continue;
            String id = s(p, "id"), name = s(p, "name"), sId = s(p, "sportId");
            if (id == null || name == null) continue;
            String url = "https://www.olimp.bet/line/" + sId + "/" + tournamentId + "/" + id;
            Instant startsAt = Instant.ofEpochSecond(p.path("startDateTime").asLong(0));
            matches.add(new ParsedMatchDto(id, name, tournamentId, url,
                    startsAt, false, buildExtraData(p)));
        }
        return ParseResult.ok(matches, ms() - start);
    }

    @Override
    public boolean isAvailable() {
        try { block(http.getJson(sportsApi, JsonNode.class)); return true; }
        catch (Exception e) { log.debug("Olimp isAvailable failed: {}", describe(e)); return false; }
    }

    // ── extraData ─────────────────────────────────────────────────────────────

    private static String buildExtraData(JsonNode payload) {
        long st = payload.path("startDateTime").asLong(0);
        JsonNode outcomes = payload.path("outcomes");

        double w1  = prob(outcomes, "RESULT",   "П1");
        double wX  = prob(outcomes, "RESULT",   "Х");
        double w2  = prob(outcomes, "RESULT",   "П2");

        double h1v = prob(outcomes, "HANDICAP", "Фора 1");
        double h1pt = param(outcomes, "HANDICAP", "Фора 1");
        double h2v = prob(outcomes, "HANDICAP", "Фора 2");
        double h2pt = param(outcomes, "HANDICAP", "Фора 2");

        double tbv = prob(outcomes, "TOTAL", "ТотБ");
        double totPt = param(outcomes, "TOTAL", "ТотБ");
        double tmv = prob(outcomes, "TOTAL", "ТотМ");

        StringBuilder sb = new StringBuilder("{");
        if (st > 0)    sb.append("\"st\":").append(st).append(",");
        if (w1  > 1.0) sb.append("\"w1\":").append(fmt(w1)).append(",");
        if (wX  > 1.0) sb.append("\"wX\":").append(fmt(wX)).append(",");
        if (w2  > 1.0) sb.append("\"w2\":").append(fmt(w2)).append(",");
        if (h1v > 1.0 && h2v > 1.0 && !Double.isNaN(h1pt) && !Double.isNaN(h2pt)) {
            sb.append("\"h1\":{\"v\":").append(fmt(h1v)).append(",\"pt\":\"").append(fmtPt(h1pt)).append("\"},");
            sb.append("\"h2\":{\"v\":").append(fmt(h2v)).append(",\"pt\":\"").append(fmtPt(h2pt)).append("\"},");
        }
        if (tbv > 1.0 && tmv > 1.0 && !Double.isNaN(totPt)) {
            sb.append("\"tb\":{\"v\":").append(fmt(tbv)).append(",\"pt\":\"").append(fmtPt(totPt)).append("\"},");
            sb.append("\"tm\":{\"v\":").append(fmt(tmv)).append(",\"pt\":\"").append(fmtPt(totPt)).append("\"},");
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    private static double prob(JsonNode outcomes, String tableType, String shortName) {
        for (JsonNode o : iter(outcomes)) {
            if (tableType.equals(o.path("tableType").asText())
                    && shortName.equals(o.path("shortName").asText())) {
                return o.path("probability").asDouble(0);
            }
        }
        return 0;
    }

    private static double param(JsonNode outcomes, String tableType, String shortName) {
        for (JsonNode o : iter(outcomes)) {
            if (tableType.equals(o.path("tableType").asText())
                    && shortName.equals(o.path("shortName").asText())) {
                JsonNode p = o.path("param");
                return p.isMissingNode() || p.isNull() ? Double.NaN : p.asDouble(Double.NaN);
            }
        }
        return Double.NaN;
    }

    private static String fmt(double v) {
        String s = String.format("%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private static String fmtPt(double v) {
        if (v == 0.0) return "0";
        String s = String.format("%.2f", Math.abs(v)).replaceAll("0+$", "").replaceAll("\\.$", "");
        return v > 0 ? "+" + s : "-" + s;
    }

    // ── fallbacks ─────────────────────────────────────────────────────────────

    private ParseResult<List<SportDto>> fetchSportsFallback(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("olimp fetchSports skipped — CB open/half-open");
        } else if (Thread.currentThread().isInterrupted()) {
            // Our own fetch-budget timeout interrupted this call — ControllerTask already
            // logs "Fetch budget exceeded" with full context, so this would just double it.
            log.debug("olimp fetchSports fallback [{}]: {} (budget interrupt)", t.getClass().getSimpleName(), describe(t));
        } else {
            log.warn("olimp fetchSports fallback [{}]: {}", t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("olimp-cb: " + t.getMessage());
    }

    private ParseResult<List<TournamentDto>> fetchTournamentsFallback(String sportId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("olimp fetchTournaments skipped — CB open/half-open sportId={}", sportId);
        } else if (Thread.currentThread().isInterrupted()) {
            log.debug("olimp fetchTournaments fallback sportId={} [{}]: {} (budget interrupt)",
                    sportId, t.getClass().getSimpleName(), describe(t));
        } else {
            log.warn("olimp fetchTournaments fallback sportId={} [{}]: {}", sportId, t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("olimp-cb: " + t.getMessage());
    }

    private ParseResult<List<ParsedMatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("olimp fetchMatches skipped — CB open/half-open tournamentId={}", tournamentId);
        } else if (Thread.currentThread().isInterrupted()) {
            log.debug("olimp fetchMatches fallback tournamentId={} [{}]: {} (budget interrupt)",
                    tournamentId, t.getClass().getSimpleName(), describe(t));
        } else {
            log.warn("olimp fetchMatches fallback tournamentId={} [{}]: {}", tournamentId, t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("olimp-cb: " + t.getMessage());
    }

    // ── utils ─────────────────────────────────────────────────────────────────

    private <T> T block(reactor.core.publisher.Mono<T> mono) { return mono.block(BLOCK_TIMEOUT); }

    /**
     * Refreshes are serialized per-endpoint via {@code lock} so that when the TTL expires
     * under concurrent polling, only one of the ~30 controllers actually performs the ~20MB
     * blocking fetch — the rest wait on the lock and then read the snapshot that thread just
     * installed, instead of each independently kicking off its own full-catalog parse (the
     * exact pile-up that caused the original OOM). A {@link ReentrantLock}, not
     * {@code synchronized}: the winner holds it across a blocking HTTP call (up to
     * {@link BookmakerHttpClient#BLOCK_TIMEOUT}), and {@code synchronized} would pin the
     * carrier thread of every virtual thread queued behind it for that whole duration.
     *
     * <p>A null/empty response is never cached: caching it would silently black out every
     * Olimp controller for the full TTL on a single transient API hiccup, with no exception
     * to trip the circuit breaker or surface an error.
     *
     * <p>{@code lastFailureAtMs} bounds how long a failed refresh can convoy every other waiter
     * into repeating the same doomed ~20MB HTTP attempt — see {@link #FAILURE_COOLDOWN_MS}'s
     * javadoc (same fix, same production incident, as {@code FonbetParser.fetchSnapshot()}).
     *
     * <p>Once a snapshot exists, an expired one (younger than {@link #MAX_STALE_MS}) is returned
     * immediately and refreshed by a single background thread — see {@code MAX_STALE_MS} for the
     * incident. Only a cold start or a snapshot older than that blocks the caller on the lock.
     */
    private JsonNode fetchCached(SnapSlot slot, String url) {
        CachedSnap cached = slot.cache.get();
        if (cached != null) {
            long age = ms() - cached.ts();
            if (age < snapTtlMs) return cached.data();
            if (age < MAX_STALE_MS) {
                refreshInBackground(slot, url);
                return cached.data();
            }
        }
        slot.lock.lock();
        try {
            cached = slot.cache.get();
            if (cached != null && ms() - cached.ts() < snapTtlMs) {
                return cached.data();
            }
            return refreshLocked(slot, url);
        } finally {
            slot.lock.unlock();
        }
    }

    /** Single-flight: a no-op while another background refresh of the same endpoint is running. */
    private void refreshInBackground(SnapSlot slot, String url) {
        if (!slot.refreshing.compareAndSet(false, true)) return;
        // Its own thread, so the caller's fetch budget can never interrupt the refresh — the HTTP
        // call is still capped by BLOCK_TIMEOUT.
        Thread.ofVirtual().name("olimp-snap-refresh").start(() -> {
            slot.lock.lock();
            try {
                CachedSnap cached = slot.cache.get();
                if (cached == null || ms() - cached.ts() >= snapTtlMs) {
                    refreshLocked(slot, url);
                }
            } catch (Exception ignored) {
                // Already logged/recorded by refreshLocked; callers keep the stale snapshot.
            } finally {
                slot.lock.unlock();
                slot.refreshing.set(false);
            }
        });
    }

    /** Caller must hold {@code slot.lock}. */
    private JsonNode refreshLocked(SnapSlot slot, String url) {
        long lastFailure = slot.lastFailureAtMs.get();
        if (lastFailure > 0 && ms() - lastFailure < FAILURE_COOLDOWN_MS) {
            throw new OlimpSnapshotUnavailableException(
                    "Olimp snapshot refresh failed recently — failing fast instead of retrying: " + url);
        }
        long t0 = ms();
        JsonNode data;
        try {
            data = block(http.getJson(url, JsonNode.class));
        } catch (Exception e) {
            // The @CircuitBreaker fallback (fetchXFallback) only sees the aggregated Throwable
            // with no idea which of the 3 Olimp endpoints actually failed, and the fail-fast
            // waiters only see OlimpSnapshotUnavailableException — this is the one place the
            // real cause is known. WARN for the first failure of a streak (was DEBUG, which hid
            // the cause of every burst in prod), DEBUG for repeats so an outage doesn't flood.
            String interrupted = Thread.currentThread().isInterrupted() ? " (interrupted by fetch budget)" : "";
            if (lastFailure == 0) {
                log.warn("[Olimp] snapshot refresh failed url={} after {}ms{}: {}", url, ms() - t0, interrupted, describe(e));
            } else {
                log.debug("[Olimp] snapshot refresh failed again url={} after {}ms{}: {}", url, ms() - t0, interrupted, describe(e));
            }
            slot.lastFailureAtMs.set(ms());
            throw e;
        }
        if (lastFailure > 0) {
            log.info("[Olimp] snapshot refresh recovered url={} ({}ms)", url, ms() - t0);
        }
        slot.lastFailureAtMs.set(0L);
        if (data != null) {
            slot.cache.set(new CachedSnap(data, ms()));
        }
        return data;
    }

    /** Marker for fetchCached()'s fail-fast cooldown path — see {@code FonbetParser}'s twin. */
    static final class OlimpSnapshotUnavailableException extends RuntimeException {
        OlimpSnapshotUnavailableException(String message) { super(message); }
    }

    private static Iterable<JsonNode> iter(JsonNode n) { return n != null && n.isArray() ? n : List.of(); }

    private static String s(JsonNode n, String f) {
        JsonNode v = n.path(f); return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static long ms() { return System.currentTimeMillis(); }
}
