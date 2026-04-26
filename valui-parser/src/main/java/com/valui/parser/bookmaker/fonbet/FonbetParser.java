package com.valui.parser.bookmaker.fonbet;

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
public class FonbetParser implements BookmakerParser {

    private static final String DEFAULT_API =
            "https://line32w.bk6bba-resources.com/events/list?lang=ru&scopeMarket=1600";
    private static final long CACHE_TTL_MS = 60_000;

    private final String apiUrl;
    private final WebClient webClient;

    // simple volatile cache — worst-case two threads refresh simultaneously, which is idempotent
    private volatile JsonNode cachedSnapshot;
    private volatile long cacheExpiresAt = 0;

    public FonbetParser() {
        this(DEFAULT_API, buildWebClient());
    }

    FonbetParser(String apiUrl, WebClient webClient) {
        this.apiUrl = apiUrl;
        this.webClient = webClient;
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
        return BookmakerType.FONBET;
    }

    @Override
    public ParseResult<List<SportDto>> fetchSports() {
        long start = System.currentTimeMillis();
        try {
            JsonNode snap = getSnapshot();
            List<SportDto> sports = new ArrayList<>();
            for (JsonNode item : sports(snap)) {
                if (!item.path("parentId").isNull() && !item.path("parentId").isMissingNode()) continue;
                String id   = s(item, "id");
                String name = s(item, "name");
                if (id == null || name == null) continue;
                sports.add(new SportDto(id, name, slugify(name)));
            }
            return ParseResult.ok(sports, ms(start));
        } catch (Exception e) {
            log.warn("Fonbet fetchSports failed", e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = System.currentTimeMillis();
        try {
            JsonNode snap = getSnapshot();
            List<TournamentDto> tournaments = new ArrayList<>();
            for (JsonNode item : sports(snap)) {
                JsonNode parentId = item.path("parentId");
                if (parentId.isNull() || parentId.isMissingNode()) continue;
                if (!sportId.equals(parentId.asText())) continue;
                String id   = s(item, "id");
                String name = s(item, "name");
                if (id == null || name == null) continue;
                String url = "https://www.fon.bet/sports/" + sportId + "/" + id;
                tournaments.add(new TournamentDto(id, name, sportId, null, url));
            }
            return ParseResult.ok(tournaments, ms(start));
        } catch (Exception e) {
            log.warn("Fonbet fetchTournaments failed for sportId={}", sportId, e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public ParseResult<List<MatchDto>> fetchMatches(String tournamentId) {
        long start = System.currentTimeMillis();
        try {
            JsonNode snap = getSnapshot();
            List<MatchDto> matches = new ArrayList<>();
            JsonNode events = snap.path("events");
            if (!events.isArray()) return ParseResult.ok(matches, ms(start));
            for (JsonNode ev : events) {
                if (!tournamentId.equals(s(ev, "sportId"))) continue;
                JsonNode parentId = ev.path("parentId");
                if (!parentId.isNull() && !parentId.isMissingNode()) continue;
                String id    = s(ev, "id");
                String team1 = s(ev, "team1");
                String team2 = s(ev, "team2");
                if (id == null || team1 == null || team2 == null) continue;
                String url = "https://www.fon.bet/sports/" + tournamentId + "/" + id;
                Instant startsAt = parseInstant(s(ev, "startTime"), true);
                boolean live = "1".equals(s(ev, "kind")) || ev.path("live").asBoolean(false);
                matches.add(new MatchDto(id, team1 + " - " + team2, tournamentId, url, startsAt, live));
            }
            return ParseResult.ok(matches, ms(start));
        } catch (Exception e) {
            log.warn("Fonbet fetchMatches failed for tournamentId={}", tournamentId, e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            getSnapshot();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private JsonNode getSnapshot() {
        long now = System.currentTimeMillis();
        if (cachedSnapshot != null && cacheExpiresAt > now) return cachedSnapshot;
        JsonNode fresh = webClient.get().uri(apiUrl)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(12));
        if (fresh != null) {
            cachedSnapshot = fresh;
            cacheExpiresAt = now + CACHE_TTL_MS;
        }
        return fresh;
    }

    private static Iterable<JsonNode> sports(JsonNode snap) {
        JsonNode arr = snap.path("sports");
        return arr.isArray() ? arr : List.of();
    }

    private static String s(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String slugify(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    private static Instant parseInstant(String s, boolean millis) {
        if (s == null || s.isBlank()) return Instant.EPOCH;
        try {
            long t = Long.parseLong(s);
            return millis ? Instant.ofEpochMilli(t) : Instant.ofEpochSecond(t);
        } catch (NumberFormatException e) {
            try { return Instant.parse(s); } catch (Exception e2) { return Instant.EPOCH; }
        }
    }

    private static long ms(long start) {
        return System.currentTimeMillis() - start;
    }
}
