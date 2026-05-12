package com.valui.parser.bookmaker.betcity;

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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.valui.parser.http.BookmakerHttpClient.BLOCK_TIMEOUT;

@Slf4j
@Component
public class BetCityParser implements BookmakerParser {

    private static final String DEFAULT_BASE = "https://ad.betcity.ru/d/off";

    private final String sportsApi;
    private final String champsApi;
    private final String eventsApi;
    private final BookmakerHttpClient http;

    @Autowired
    public BetCityParser(@Qualifier("betcityHttpClient") BookmakerHttpClient http) {
        this(DEFAULT_BASE, http);
    }

    BetCityParser(String apiBase, BookmakerHttpClient http) {
        this.sportsApi = apiBase + "/sports";
        this.champsApi = apiBase + "/champs?rev=4";
        this.eventsApi = apiBase + "/events?rev=6";
        this.http = http;
    }

    BetCityParser(String apiBase, org.springframework.web.reactive.function.client.WebClient wc) {
        this(apiBase, new BookmakerHttpClient(wc));
    }

    @Override
    public BookmakerType getBookmaker() { return BookmakerType.BETCITY; }

    @CircuitBreaker(name = "betcity-cb", fallbackMethod = "fetchSportsFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<SportDto>> fetchSports() {
        long start = ms();
        JsonNode root = block(http.getJson(sportsApi, JsonNode.class));
        JsonNode arr = root.path("reply").path("sports");
        List<SportDto> sports = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String id = s(item, "id_sp"), name = s(item, "name_sp");
                if (id == null || name == null) continue;
                String alias = BetcitySportsMap.getSport(safeInt(id)).orElse(name.toLowerCase());
                sports.add(new SportDto(id, name, alias));
            }
        }
        return ParseResult.ok(sports, ms() - start);
    }

    @CircuitBreaker(name = "betcity-cb", fallbackMethod = "fetchTournamentsFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = ms();
        JsonNode root = block(http.getJson(champsApi + "&ids_sp=" + sportId, JsonNode.class));
        JsonNode chmps = root.path("reply").path("sports").path(sportId).path("chmps");
        List<TournamentDto> tournaments = new ArrayList<>();
        chmps.fields().forEachRemaining(e -> {
            String id = e.getKey(), title = s(e.getValue(), "name_ch");
            if (title == null) return;
            String alias = BetcitySportsMap.getSport(safeInt(sportId)).orElse("sport");
            tournaments.add(new TournamentDto(id, title, sportId, null,
                    "https://betcity.ru/ru/line/" + alias + "/" + id));
        });
        return ParseResult.ok(tournaments, ms() - start);
    }

    @CircuitBreaker(name = "betcity-cb", fallbackMethod = "fetchMatchesFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<ParsedMatchDto>> fetchMatches(String tournamentId) {
        long start = ms();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("ids", tournamentId);
        JsonNode root = block(http.postMultipart(eventsApi, form, JsonNode.class));
        List<ParsedMatchDto> matches = new ArrayList<>();
        if (root == null) return ParseResult.ok(matches, ms() - start);
        root.path("reply").path("sports").fields().forEachRemaining(sportEntry -> {
            String sportId = sportEntry.getKey();
            JsonNode evts = sportEntry.getValue().path("chmps").path(tournamentId).path("evts");
            if (evts.isMissingNode()) return;
            String alias = BetcitySportsMap.getSport(safeInt(sportId)).orElse("sport");
            evts.fields().forEachRemaining(evtEntry -> {
                String id = evtEntry.getKey();
                String t1 = s(evtEntry.getValue(), "name_ht"), t2 = s(evtEntry.getValue(), "name_at");
                if (t1 == null || t2 == null) return;
                String url = "https://betcity.ru/ru/line/" + alias + "/" + tournamentId + "/" + id;
                matches.add(new ParsedMatchDto(id, t1 + " - " + t2, tournamentId, url,
                        parseInstant(s(evtEntry.getValue(), "date_dt")), false, null));
            });
        });
        return ParseResult.ok(matches, ms() - start);
    }

    @Override
    public boolean isAvailable() {
        try { block(http.getJson(sportsApi, JsonNode.class)); return true; }
        catch (Exception e) { return false; }
    }

    // ── fallbacks ─────────────────────────────────────────────────────────────

    private ParseResult<List<SportDto>> fetchSportsFallback(Throwable t) {
        log.warn("betcity fetchSports fallback: {}", t.getMessage());
        return ParseResult.error("betcity-cb: " + t.getMessage());
    }

    private ParseResult<List<TournamentDto>> fetchTournamentsFallback(String sportId, Throwable t) {
        log.warn("betcity fetchTournaments fallback: {}", t.getMessage());
        return ParseResult.error("betcity-cb: " + t.getMessage());
    }

    private ParseResult<List<ParsedMatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        log.warn("betcity fetchMatches fallback: {}", t.getMessage());
        return ParseResult.error("betcity-cb: " + t.getMessage());
    }

    // ── utils ─────────────────────────────────────────────────────────────────

    private <T> T block(reactor.core.publisher.Mono<T> mono) { return mono.block(BLOCK_TIMEOUT); }

    private static String s(JsonNode n, String f) {
        JsonNode v = n.path(f); return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
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
