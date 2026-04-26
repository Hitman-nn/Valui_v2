package com.valui.parser.bookmaker.betcity;

import com.fasterxml.jackson.databind.JsonNode;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.MatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BetCityParser implements BookmakerParser {

    private static final String DEFAULT_BASE = "https://ad.betcity.ru/d/off";

    private final String sportsApi;
    private final String champsApi;
    private final String eventsApi;
    private final WebClient webClient;

    public BetCityParser() {
        this(DEFAULT_BASE, buildWebClient());
    }

    BetCityParser(String apiBase, WebClient webClient) {
        this.sportsApi  = apiBase + "/sports";
        this.champsApi  = apiBase + "/champs?rev=4";
        this.eventsApi  = apiBase + "/events?rev=6";
        this.webClient  = webClient;
    }

    private static WebClient buildWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(10));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public BookmakerType getBookmaker() {
        return BookmakerType.BETCITY;
    }

    @Override
    public ParseResult<List<SportDto>> fetchSports() {
        long start = System.currentTimeMillis();
        try {
            JsonNode root  = getJson(sportsApi);
            JsonNode reply = root.path("reply");
            JsonNode arr   = reply.path("sports");
            List<SportDto> sports = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    String id   = s(item, "id_sp");
                    String name = s(item, "name_sp");
                    if (id == null || name == null) continue;
                    String alias = BetcitySportsMap.getSport(safeInt(id)).orElse(name.toLowerCase());
                    sports.add(new SportDto(id, name, alias));
                }
            }
            return ParseResult.ok(sports, ms(start));
        } catch (Exception e) {
            log.warn("BetCity fetchSports failed", e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = System.currentTimeMillis();
        try {
            JsonNode root   = getJson(champsApi + "&ids_sp=" + sportId);
            JsonNode sports = root.path("reply").path("sports");
            JsonNode chmps  = sports.path(sportId).path("chmps");
            List<TournamentDto> tournaments = new ArrayList<>();
            chmps.fields().forEachRemaining(e -> {
                String id    = e.getKey();
                String title = s(e.getValue(), "name_ch");
                if (title == null) return;
                String alias = BetcitySportsMap.getSport(safeInt(sportId)).orElse("sport");
                String url   = "https://betcity.ru/ru/line/" + alias + "/" + id;
                tournaments.add(new TournamentDto(id, title, sportId, null, url));
            });
            return ParseResult.ok(tournaments, ms(start));
        } catch (Exception e) {
            log.warn("BetCity fetchTournaments failed for sportId={}", sportId, e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public ParseResult<List<MatchDto>> fetchMatches(String tournamentId) {
        long start = System.currentTimeMillis();
        try {
            // BetCity events endpoint requires POST with form data
            JsonNode root = webClient.post()
                    .uri(eventsApi)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("ids=" + tournamentId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(12));

            List<MatchDto> matches = new ArrayList<>();
            if (root == null) return ParseResult.ok(matches, ms(start));

            JsonNode allSports = root.path("reply").path("sports");
            allSports.fields().forEachRemaining(sportEntry -> {
                String sportId = sportEntry.getKey();
                JsonNode chmps = sportEntry.getValue().path("chmps");
                JsonNode evts  = chmps.path(tournamentId).path("evts");
                if (evts.isMissingNode()) return;
                String alias = BetcitySportsMap.getSport(safeInt(sportId)).orElse("sport");
                evts.fields().forEachRemaining(evtEntry -> {
                    String id    = evtEntry.getKey();
                    String team1 = s(evtEntry.getValue(), "name_ht");
                    String team2 = s(evtEntry.getValue(), "name_at");
                    if (team1 == null || team2 == null) return;
                    String url = "https://betcity.ru/ru/line/" + alias + "/" + tournamentId + "/" + id;
                    Instant startsAt = parseInstant(s(evtEntry.getValue(), "date_dt"));
                    matches.add(new MatchDto(id, team1 + " - " + team2, tournamentId, url, startsAt, false));
                });
            });
            return ParseResult.ok(matches, ms(start));
        } catch (Exception e) {
            log.warn("BetCity fetchMatches failed for tournamentId={}", tournamentId, e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            getJson(sportsApi);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private JsonNode getJson(String url) {
        return webClient.get().uri(url)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(12));
    }

    private static String s(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
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

    private static long ms(long start) {
        return System.currentTimeMillis() - start;
    }
}
