package com.valui.parser.bookmaker.olimp;

import com.fasterxml.jackson.databind.JsonNode;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.MatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class OlimpParser implements BookmakerParser {

    private static final String DEFAULT_BASE = "https://www.olimp.bet/api/v4/0/line";

    private final String sportsApi;
    private final String champsApi;
    private final String eventsApi;
    private final WebClient webClient;

    public OlimpParser() {
        this(DEFAULT_BASE, buildWebClient());
    }

    OlimpParser(String apiBase, WebClient webClient) {
        this.sportsApi  = apiBase + "/sports";
        this.champsApi  = apiBase + "/sports-with-competitions";
        this.eventsApi  = apiBase + "/planned-events";
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
        return BookmakerType.OLIMP;
    }

    @Override
    public ParseResult<List<SportDto>> fetchSports() {
        long start = System.currentTimeMillis();
        try {
            JsonNode arr = getArray(sportsApi);
            List<SportDto> sports = new ArrayList<>();
            for (JsonNode item : arr) {
                JsonNode payload = item.path("payload");
                String id   = s(payload, "id");
                String name = s(payload, "name");
                if (id == null || name == null) continue;
                sports.add(new SportDto(id, name, s(payload, "alias")));
            }
            return ParseResult.ok(sports, ms(start));
        } catch (Exception e) {
            log.warn("Olimp fetchSports failed", e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = System.currentTimeMillis();
        try {
            JsonNode arr = getArray(champsApi);
            List<TournamentDto> tournaments = new ArrayList<>();
            for (JsonNode item : arr) {
                JsonNode payload = item.path("payload");
                if (!sportId.equals(s(payload, "id"))) continue;
                JsonNode competitions = payload.path("competitions");
                if (!competitions.isArray()) continue;
                for (JsonNode comp : competitions) {
                    String id    = s(comp, "id");
                    String name  = s(comp, "name");
                    String sId   = s(comp, "sportId");
                    if (id == null || name == null) continue;
                    String url = "https://www.olimp.bet/line/" + (sId != null ? sId : sportId) + "/" + id;
                    tournaments.add(new TournamentDto(id, name, sId != null ? sId : sportId, null, url));
                }
            }
            return ParseResult.ok(tournaments, ms(start));
        } catch (Exception e) {
            log.warn("Olimp fetchTournaments failed for sportId={}", sportId, e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public ParseResult<List<MatchDto>> fetchMatches(String tournamentId) {
        long start = System.currentTimeMillis();
        try {
            JsonNode arr = getArray(eventsApi);
            List<MatchDto> matches = new ArrayList<>();
            for (JsonNode item : arr) {
                JsonNode payload = item.path("payload");
                if (!tournamentId.equals(s(payload, "competitionId"))) continue;
                String id   = s(payload, "id");
                String name = s(payload, "name");
                if (id == null || name == null) continue;
                String sportId = s(payload, "sportId");
                String url = "https://www.olimp.bet/line/" + sportId + "/" + tournamentId + "/" + id;
                Instant startsAt = parseInstant(s(payload, "startsAt"));
                boolean live = payload.path("isLive").asBoolean(false);
                matches.add(new MatchDto(id, name, tournamentId, url, startsAt, live));
            }
            return ParseResult.ok(matches, ms(start));
        } catch (Exception e) {
            log.warn("Olimp fetchMatches failed for tournamentId={}", tournamentId, e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            getArray(sportsApi);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private JsonNode getArray(String url) {
        return webClient.get().uri(url)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(12));
    }

    private static String s(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
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
