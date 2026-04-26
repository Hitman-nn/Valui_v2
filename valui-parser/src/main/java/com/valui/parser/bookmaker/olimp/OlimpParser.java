package com.valui.parser.bookmaker.olimp;

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
public class OlimpParser implements BookmakerParser {

    private static final String DEFAULT_BASE = "https://www.olimp.bet/api/v4/0/line";

    private final String sportsApi;
    private final String champsApi;
    private final String eventsApi;
    private final BookmakerHttpClient http;

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
        JsonNode arr = block(http.getJson(sportsApi, JsonNode.class));
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
        JsonNode arr = block(http.getJson(champsApi, JsonNode.class));
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
    public ParseResult<List<MatchDto>> fetchMatches(String tournamentId) {
        long start = ms();
        JsonNode arr = block(http.getJson(eventsApi, JsonNode.class));
        List<MatchDto> matches = new ArrayList<>();
        for (JsonNode item : iter(arr)) {
            JsonNode p = item.path("payload");
            if (!tournamentId.equals(s(p, "competitionId"))) continue;
            String id = s(p, "id"), name = s(p, "name"), sId = s(p, "sportId");
            if (id == null || name == null) continue;
            String url = "https://www.olimp.bet/line/" + sId + "/" + tournamentId + "/" + id;
            matches.add(new MatchDto(id, name, tournamentId, url,
                    parseInstant(s(p, "startsAt")), p.path("isLive").asBoolean(false)));
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
        log.warn("olimp fetchSports fallback: {}", t.getMessage());
        return ParseResult.error("olimp-cb: " + t.getMessage());
    }

    private ParseResult<List<TournamentDto>> fetchTournamentsFallback(String sportId, Throwable t) {
        log.warn("olimp fetchTournaments fallback: {}", t.getMessage());
        return ParseResult.error("olimp-cb: " + t.getMessage());
    }

    private ParseResult<List<MatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        log.warn("olimp fetchMatches fallback: {}", t.getMessage());
        return ParseResult.error("olimp-cb: " + t.getMessage());
    }

    // ── utils ─────────────────────────────────────────────────────────────────

    private <T> T block(reactor.core.publisher.Mono<T> mono) { return mono.block(BLOCK_TIMEOUT); }

    private static Iterable<JsonNode> iter(JsonNode n) { return n != null && n.isArray() ? n : List.of(); }

    private static String s(JsonNode n, String f) {
        JsonNode v = n.path(f); return v.isMissingNode() || v.isNull() ? null : v.asText();
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
