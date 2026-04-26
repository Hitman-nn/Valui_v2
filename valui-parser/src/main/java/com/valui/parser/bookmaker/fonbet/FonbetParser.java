package com.valui.parser.bookmaker.fonbet;

import com.fasterxml.jackson.databind.JsonNode;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.MatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.http.BookmakerHttpClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.valui.parser.http.BookmakerHttpClient.BLOCK_TIMEOUT;

@Slf4j
@Component
public class FonbetParser implements BookmakerParser {

    private static final String DEFAULT_API =
            "https://line32w.bk6bba-resources.com/events/list?lang=ru&scopeMarket=1600";
    private static final long CACHE_TTL_MS = 60_000;

    private final String apiUrl;
    private final BookmakerHttpClient http;

    private volatile JsonNode cachedSnapshot;
    private volatile long cacheExpiresAt = 0;

    public FonbetParser(@Qualifier("fonbetHttpClient") BookmakerHttpClient http) {
        this(DEFAULT_API, http);
    }

    FonbetParser(String apiUrl, BookmakerHttpClient http) {
        this.apiUrl = apiUrl;
        this.http = http;
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
        JsonNode snap = getSnapshot();
        List<SportDto> sports = new ArrayList<>();
        for (JsonNode item : sportsArray(snap)) {
            if (!item.path("parentId").isNull() && !item.path("parentId").isMissingNode()) continue;
            String id = s(item, "id"), name = s(item, "name");
            if (id != null && name != null) sports.add(new SportDto(id, name, slugify(name)));
        }
        return ParseResult.ok(sports, ms() - start);
    }

    @CircuitBreaker(name = "fonbet-cb", fallbackMethod = "fetchTournamentsFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = ms();
        JsonNode snap = getSnapshot();
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
    public ParseResult<List<MatchDto>> fetchMatches(String tournamentId) {
        long start = ms();
        JsonNode snap = getSnapshot();
        List<MatchDto> matches = new ArrayList<>();
        JsonNode events = snap.path("events");
        if (!events.isArray()) return ParseResult.ok(matches, ms() - start);
        for (JsonNode ev : events) {
            if (!tournamentId.equals(s(ev, "sportId"))) continue;
            JsonNode parentId = ev.path("parentId");
            if (!parentId.isNull() && !parentId.isMissingNode()) continue;
            String id = s(ev, "id"), t1 = s(ev, "team1"), t2 = s(ev, "team2");
            if (id == null || t1 == null || t2 == null) continue;
            Instant startsAt = parseInstant(s(ev, "startTime"), true);
            matches.add(new MatchDto(id, t1 + " - " + t2, tournamentId,
                    "https://www.fon.bet/sports/" + tournamentId + "/" + id,
                    startsAt, ev.path("live").asBoolean(false)));
        }
        return ParseResult.ok(matches, ms() - start);
    }

    @Override
    public boolean isAvailable() {
        try { refreshSnapshot(); return true; }
        catch (Exception e) { return false; }
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

    private ParseResult<List<MatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        log.warn("fonbet fetchMatches fallback: {}", t.getMessage());
        return ParseResult.error("fonbet-cb: " + t.getMessage());
    }

    // ── cache ─────────────────────────────────────────────────────────────────

    private JsonNode getSnapshot() {
        long now = System.currentTimeMillis();
        if (cachedSnapshot != null && cacheExpiresAt > now) return cachedSnapshot;
        return refreshSnapshot();
    }

    private JsonNode refreshSnapshot() {
        JsonNode fresh = http.getJson(apiUrl, JsonNode.class).block(BLOCK_TIMEOUT);
        if (fresh == null) throw new IllegalStateException("Fonbet API returned null");
        cachedSnapshot = fresh;
        cacheExpiresAt = System.currentTimeMillis() + CACHE_TTL_MS;
        return fresh;
    }

    // ── utils ─────────────────────────────────────────────────────────────────

    private static Iterable<JsonNode> sportsArray(JsonNode snap) {
        JsonNode arr = snap.path("sports");
        return arr.isArray() ? arr : List.of();
    }

    private static String s(JsonNode n, String f) {
        JsonNode v = n.path(f); return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String slugify(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
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
