package com.valui.parser.bookmaker.xbet;

import com.fasterxml.jackson.databind.JsonNode;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.MatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.http.BookmakerHttpClient;
import com.valui.parser.http.HttpClientConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.valui.parser.http.BookmakerHttpClient.BLOCK_TIMEOUT;

@Slf4j
@Component
public class XBetParser implements BookmakerParser {

    private static final String DEFAULT_BASE = "https://1xbet.kz/service-api/LineFeed";
    private static final long CACHE_TTL_MS = 60_000;

    private final String sportsApi;
    private final String champsApi;
    private final String matchesApi;
    private final BookmakerHttpClient http;
    private final AtomicReference<CacheEntry> champsCache = new AtomicReference<>(new CacheEntry(null, 0));

    private record CacheEntry(JsonNode data, long expiresAt) {}

    public XBetParser(@Qualifier("xbetHttpClient") BookmakerHttpClient http) {
        this(DEFAULT_BASE, http);
    }

    XBetParser(String apiBase, BookmakerHttpClient http) {
        this.sportsApi  = apiBase + "/GetSportsShortZip";
        this.champsApi  = apiBase + "/GetChampsZip";
        this.matchesApi = apiBase + "/Get1x2_VZip?";
        this.http = http;
    }

    // For tests that still pass a plain WebClient
    XBetParser(String apiBase, org.springframework.web.reactive.function.client.WebClient wc) {
        this(apiBase, new BookmakerHttpClient(wc));
    }

    @Override
    public BookmakerType getBookmaker() { return BookmakerType.XBET; }

    @CircuitBreaker(name = "xbet-cb", fallbackMethod = "fetchSportsFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<SportDto>> fetchSports() {
        long start = ms();
        JsonNode root = block(http.getJson(sportsApi, JsonNode.class));
        List<SportDto> sports = new ArrayList<>();
        for (JsonNode v : valueArray(root)) {
            String id = s(v, "I"), name = s(v, "N"), alias = s(v, "E");
            if (id != null && name != null) sports.add(new SportDto(id, name, alias));
        }
        return ParseResult.ok(sports, ms() - start);
    }

    @CircuitBreaker(name = "xbet-cb", fallbackMethod = "fetchTournamentsFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = ms();
        List<TournamentDto> tournaments = new ArrayList<>();
        for (JsonNode v : valueArray(getChampsJsonCached())) {
            if (!sportId.equalsIgnoreCase(s(v, "SI"))) continue;
            String id = s(v, "LI"), title = s(v, "L"), se = s(v, "SE"), le = s(v, "LE");
            if (id == null || title == null) continue;
            String url = "https://1xstavka.ru/line/" + slug(se) + "/" + id
                    + (le != null ? "-" + slug(le) : "");
            tournaments.add(new TournamentDto(id, title, sportId, null, url));
        }
        return ParseResult.ok(tournaments, ms() - start);
    }

    @CircuitBreaker(name = "xbet-cb", fallbackMethod = "fetchMatchesFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<MatchDto>> fetchMatches(String tournamentId) {
        long start = ms();
        String sportId = resolveSportId(tournamentId);
        JsonNode root = block(http.getJson(
                matchesApi + "sports=" + sportId + "&champs=" + tournamentId + "&count=1000&mode=4",
                JsonNode.class));
        List<MatchDto> matches = new ArrayList<>();
        for (JsonNode v : valueArray(root)) {
            if (!tournamentId.equalsIgnoreCase(s(v, "LI"))) continue;
            String ci = s(v, "CI"), o1 = s(v, "O1"), o2 = s(v, "O2");
            String o1e = s(v, "O1E"), o2e = s(v, "O2E");
            if (ci == null || o1 == null || o2 == null) continue;
            String url = "https://1xstavka.ru/line/" + slug(s(v, "SE"))
                    + "/" + tournamentId + "/" + ci + "-" + slug(o1e) + "-" + slug(o2e);
            matches.add(new MatchDto(ci, o1 + " - " + o2, tournamentId, url,
                    parseInstant(s(v, "T")), "1".equals(s(v, "CL"))));
        }
        return ParseResult.ok(matches, ms() - start);
    }

    @Override
    public boolean isAvailable() {
        try { block(http.getJson(sportsApi, JsonNode.class)); return true; }
        catch (Exception e) { return false; }
    }

    // ── fallbacks ─────────────────────────────────────────────────────────────

    private ParseResult<List<SportDto>> fetchSportsFallback(Throwable t) {
        log.warn("xbet fetchSports fallback: {}", t.getMessage());
        return ParseResult.error("xbet-cb: " + t.getMessage());
    }

    private ParseResult<List<TournamentDto>> fetchTournamentsFallback(String sportId, Throwable t) {
        log.warn("xbet fetchTournaments fallback sportId={}: {}", sportId, t.getMessage());
        return ParseResult.error("xbet-cb: " + t.getMessage());
    }

    private ParseResult<List<MatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        log.warn("xbet fetchMatches fallback tournamentId={}: {}", tournamentId, t.getMessage());
        return ParseResult.error("xbet-cb: " + t.getMessage());
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private JsonNode getChampsJsonCached() {
        long now = System.currentTimeMillis();
        CacheEntry c = champsCache.get();
        if (c.data() != null && c.expiresAt() > now) return c.data();
        JsonNode fresh = block(http.getJson(champsApi, JsonNode.class));
        champsCache.set(new CacheEntry(fresh, now + CACHE_TTL_MS));
        return fresh;
    }

    private String resolveSportId(String tournamentId) {
        for (JsonNode v : valueArray(getChampsJsonCached())) {
            if (tournamentId.equalsIgnoreCase(s(v, "LI"))) {
                String si = s(v, "SI");
                if (si != null) return si;
            }
        }
        return "0";
    }

    private <T> T block(reactor.core.publisher.Mono<T> mono) { return mono.block(BLOCK_TIMEOUT); }

    private static Iterable<JsonNode> valueArray(JsonNode root) {
        if (root == null) return List.of();
        JsonNode arr = root.path("Value");
        return arr.isArray() ? arr : List.of();
    }

    private static String s(JsonNode n, String f) {
        JsonNode v = n.path(f); return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String slug(String raw) {
        return raw == null ? "" : raw.replaceAll("\\.", "").replace(" ", "-");
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return Instant.EPOCH;
        try { return Instant.ofEpochSecond(Long.parseLong(s)); }
        catch (NumberFormatException e) {
            try { return Instant.parse(s); } catch (Exception e2) { return Instant.EPOCH; }
        }
    }

    private static long ms() { return System.currentTimeMillis(); }
}
