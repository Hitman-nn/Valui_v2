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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static com.valui.parser.http.BookmakerHttpClient.BLOCK_TIMEOUT;

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

    private final String sportsApi;
    private final String champsApi;
    private final String eventsApi;
    private final BookmakerHttpClient http;

    private final AtomicReference<CachedSnap> sportsCache = new AtomicReference<>();
    private final AtomicReference<CachedSnap> champsCache = new AtomicReference<>();
    private final AtomicReference<CachedSnap> eventsCache = new AtomicReference<>();

    // Guards each cache's refresh (see fetchCached). ReentrantLock, not synchronized —
    // the refresh holds the lock across a blocking HTTP call, and synchronized pins the
    // carrier thread of every virtual thread queued on it for that whole duration (up to
    // BLOCK_TIMEOUT); ReentrantLock lets waiters unmount instead.
    private final ReentrantLock sportsLock = new ReentrantLock();
    private final ReentrantLock champsLock = new ReentrantLock();
    private final ReentrantLock eventsLock = new ReentrantLock();

    private record CachedSnap(JsonNode data, long ts) {}

    @Autowired
    public OlimpParser(@Qualifier("olimpHttpClient") BookmakerHttpClient http) {
        this(DEFAULT_BASE, http);
    }

    OlimpParser(String apiBase, BookmakerHttpClient http) {
        this.sportsApi = apiBase + "/sports";
        this.champsApi = apiBase + "/sports-with-competitions";
        this.eventsApi = apiBase + "/planned-events";
        this.http = http;
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
        JsonNode arr = fetchCached(sportsCache, sportsLock, sportsApi);
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
        JsonNode arr = fetchCached(champsCache, champsLock, champsApi);
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
        JsonNode arr = fetchCached(eventsCache, eventsLock, eventsApi);
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
        catch (Exception e) { return false; }
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
        } else {
            log.warn("olimp fetchSports fallback: {}", t.getMessage());
        }
        return ParseResult.error("olimp-cb: " + t.getMessage());
    }

    private ParseResult<List<TournamentDto>> fetchTournamentsFallback(String sportId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("olimp fetchTournaments skipped — CB open/half-open sportId={}", sportId);
        } else {
            log.warn("olimp fetchTournaments fallback: {}", t.getMessage());
        }
        return ParseResult.error("olimp-cb: " + t.getMessage());
    }

    private ParseResult<List<ParsedMatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("olimp fetchMatches skipped — CB open/half-open tournamentId={}", tournamentId);
        } else {
            log.warn("olimp fetchMatches fallback: {}", t.getMessage());
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
     */
    private JsonNode fetchCached(AtomicReference<CachedSnap> cacheRef, ReentrantLock lock, String url) {
        CachedSnap cached = cacheRef.get();
        if (cached != null && System.currentTimeMillis() - cached.ts() < SNAP_TTL_MS) {
            return cached.data();
        }
        lock.lock();
        try {
            cached = cacheRef.get();
            if (cached != null && System.currentTimeMillis() - cached.ts() < SNAP_TTL_MS) {
                return cached.data();
            }
            JsonNode data = block(http.getJson(url, JsonNode.class));
            if (data != null) {
                cacheRef.set(new CachedSnap(data, System.currentTimeMillis()));
            }
            return data;
        } finally {
            lock.unlock();
        }
    }

    private static Iterable<JsonNode> iter(JsonNode n) { return n != null && n.isArray() ? n : List.of(); }

    private static String s(JsonNode n, String f) {
        JsonNode v = n.path(f); return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static long ms() { return System.currentTimeMillis(); }
}
