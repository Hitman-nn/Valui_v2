package com.valui.parser.bookmaker.fonbet;

import com.fasterxml.jackson.databind.JsonNode;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.http.BookmakerHttpClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.valui.parser.http.BookmakerHttpClient.BLOCK_TIMEOUT;

@Slf4j
@Component
public class FonbetParser implements BookmakerParser {

    private static final String DEFAULT_API =
            "https://line32w.bk6bba-resources.com/events/list?lang=ru&scopeMarket=1600";

    // Snapshot TTL: reuse the same JSON across fetchSports/fetchTournaments/fetchMatches
    // calls within one monitor cycle so we hit Fonbet API once per cycle, not three times.
    private static final long SNAP_TTL_MS = 30_000;

    private final BookmakerHttpClient http;
    private final FonbetEndpointPool pool;
    private final String fallbackUrl;
    private final AtomicReference<CachedSnap> snapCache = new AtomicReference<>();

    private record CachedSnap(JsonNode data, long ts) {}

    @Autowired
    public FonbetParser(@Qualifier("fonbetHttpClient") BookmakerHttpClient http, FonbetEndpointPool pool) {
        this.http = http;
        this.pool = pool;
        this.fallbackUrl = DEFAULT_API;
    }

    FonbetParser(String apiUrl, BookmakerHttpClient http) {
        this.http = http;
        this.pool = null;
        this.fallbackUrl = apiUrl;
    }

    FonbetParser(String apiUrl, org.springframework.web.reactive.function.client.WebClient wc) {
        this(apiUrl, new BookmakerHttpClient(wc));
    }

    @Override
    public BookmakerType getBookmaker() { return BookmakerType.FONBET; }

    @CircuitBreaker(name = "fonbet-cb", fallbackMethod = "fetchSportsFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<SportDto>> fetchSports() {
        long start = ms();
        JsonNode snap = fetchSnapshot();
        List<SportDto> sports = new ArrayList<>();
        for (JsonNode item : sportsArray(snap)) {
            if (!item.path("parentId").isNull() && !item.path("parentId").isMissingNode()) continue;
            String id = s(item, "id"), name = s(item, "name");
            if (id != null && name != null) sports.add(new SportDto(id, name, slugify(name, id)));
        }
        return ParseResult.ok(sports, ms() - start);
    }

    @CircuitBreaker(name = "fonbet-cb", fallbackMethod = "fetchTournamentsFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = ms();
        JsonNode snap = fetchSnapshot();
        List<TournamentDto> tournaments = new ArrayList<>();
        for (JsonNode item : sportsArray(snap)) {
            JsonNode parentId = item.path("parentId");
            if (parentId.isNull() || parentId.isMissingNode()) continue;
            if (!sportId.equals(parentId.asText())) continue;
            String id = s(item, "id"), name = s(item, "name");
            if (id == null || name == null) continue;
            tournaments.add(new TournamentDto(id, name, sportId, null,
                    "https://www.fon.bet/sports/" + sportId + "/" + id));
        }
        return ParseResult.ok(tournaments, ms() - start);
    }

    @CircuitBreaker(name = "fonbet-cb", fallbackMethod = "fetchMatchesFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<ParsedMatchDto>> fetchMatches(String tournamentId) {
        long start = ms();
        JsonNode snap = fetchSnapshot();
        List<ParsedMatchDto> matches = new ArrayList<>();
        JsonNode events = snap.path("events");
        if (!events.isArray()) return ParseResult.ok(matches, ms() - start);
        for (JsonNode ev : events) {
            if (!tournamentId.equals(s(ev, "sportId"))) continue;
            JsonNode parentId = ev.path("parentId");
            if (!parentId.isNull() && !parentId.isMissingNode()) continue;
            String id = s(ev, "id"), t1 = s(ev, "team1"), t2 = s(ev, "team2");
            if (id == null || t1 == null || t2 == null) continue;
            Instant startsAt = parseInstant(s(ev, "startTime"), false);
            matches.add(new ParsedMatchDto(id, t1 + " - " + t2, tournamentId,
                    "https://www.fon.bet/sports/" + tournamentId + "/" + id,
                    startsAt, ev.path("live").asBoolean(false)));
        }
        return ParseResult.ok(matches, ms() - start);
    }

    @Override
    public boolean isAvailable() {
        // Calls fetchSnapshot() directly (private method) — intentionally bypasses the
        // @CircuitBreaker AOP proxy, which only intercepts public calls from outside the bean.
        // Health checks should probe the real connection, not short-circuit via the breaker.
        try { fetchSnapshot(); return true; }
        catch (Exception e) {
            log.debug("Fonbet isAvailable failed: {}", e.getMessage());
            return false;
        }
    }

    // ── fallbacks ─────────────────────────────────────────────────────────────

    private ParseResult<List<SportDto>> fetchSportsFallback(Throwable t) {
        log.warn("fonbet fetchSports fallback: {}", t.getMessage());
        return ParseResult.error("fonbet-cb: " + t.getMessage());
    }

    private ParseResult<List<TournamentDto>> fetchTournamentsFallback(String sportId, Throwable t) {
        log.warn("fonbet fetchTournaments fallback: {}", t.getMessage());
        return ParseResult.error("fonbet-cb: " + t.getMessage());
    }

    private ParseResult<List<ParsedMatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        log.warn("fonbet fetchMatches fallback: {}", t.getMessage());
        return ParseResult.error("fonbet-cb: " + t.getMessage());
    }

    // ── snapshot ──────────────────────────────────────────────────────────────

    private JsonNode fetchSnapshot() {
        CachedSnap cached = snapCache.get();
        if (cached != null && System.currentTimeMillis() - cached.ts < SNAP_TTL_MS) {
            return cached.data;
        }
        String url = (pool != null) ? pool.getBestEndpoint() : fallbackUrl;
        try {
            JsonNode snap = http.getJson(url, JsonNode.class).block(BLOCK_TIMEOUT);
            if (snap == null) throw new IllegalStateException("Fonbet API returned null");
            if (pool != null) pool.markSuccess(url);
            snapCache.set(new CachedSnap(snap, System.currentTimeMillis()));
            return snap;
        } catch (Exception e) {
            if (pool != null) pool.markFailure(url);
            throw e;
        }
    }

    // ── utils ─────────────────────────────────────────────────────────────────

    private static Iterable<JsonNode> sportsArray(JsonNode snap) {
        JsonNode arr = snap.path("sports");
        return arr.isArray() ? arr : List.of();
    }

    private static String s(JsonNode n, String f) {
        JsonNode v = n.path(f); return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /**
     * Converts a sport name to an ASCII alias. For Cyrillic / non-Latin names where
     * NFD decomposition yields no ASCII characters, falls back to the numeric sport ID
     * so the alias is never an empty string.
     */
    private static String slugify(String name, String fallbackId) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? fallbackId : slug;
    }

    private static Instant parseInstant(String s, boolean millis) {
        if (s == null || s.isBlank()) return Instant.EPOCH;
        try { long t = Long.parseLong(s); return millis ? Instant.ofEpochMilli(t) : Instant.ofEpochSecond(t); }
        catch (NumberFormatException e) {
            try { return Instant.parse(s); } catch (Exception e2) { return Instant.EPOCH; }
        }
    }

    private static long ms() { return System.currentTimeMillis(); }
}
