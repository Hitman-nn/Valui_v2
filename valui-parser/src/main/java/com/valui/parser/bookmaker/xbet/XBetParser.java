package com.valui.parser.bookmaker.xbet;

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
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class XBetParser implements BookmakerParser {

    private static final String DEFAULT_BASE = "https://1xbet.kz/service-api/LineFeed";
    private static final long CACHE_TTL_MS = 60_000;

    private final String sportsApi;
    private final String champsApi;
    private final String matchesApi;
    private final WebClient webClient;
    private final AtomicReference<CacheEntry> champsCache = new AtomicReference<>(new CacheEntry(null, 0));

    private record CacheEntry(JsonNode data, long expiresAt) {}

    public XBetParser() {
        this(DEFAULT_BASE, buildWebClient());
    }

    /** For testing — allows injecting a custom base URL and WebClient. */
    XBetParser(String apiBase, WebClient webClient) {
        this.sportsApi  = apiBase + "/GetSportsShortZip";
        this.champsApi  = apiBase + "/GetChampsZip";
        this.matchesApi = apiBase + "/Get1x2_VZip?";
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
        return BookmakerType.XBET;
    }

    @Override
    public ParseResult<List<SportDto>> fetchSports() {
        long start = System.currentTimeMillis();
        try {
            JsonNode root = get(sportsApi);
            List<SportDto> sports = new ArrayList<>();
            for (JsonNode v : valueArray(root)) {
                String id    = s(v, "I");
                String name  = s(v, "N");
                String alias = s(v, "E");
                if (id != null && name != null) sports.add(new SportDto(id, name, alias));
            }
            return ParseResult.ok(sports, ms(start));
        } catch (Exception e) {
            log.warn("XBet fetchSports failed", e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = System.currentTimeMillis();
        try {
            JsonNode champsJson = getChampsJsonCached(); // uses champsApi internally
            List<TournamentDto> tournaments = new ArrayList<>();
            for (JsonNode v : valueArray(champsJson)) {
                if (!sportId.equalsIgnoreCase(s(v, "SI"))) continue;
                String id    = s(v, "LI");
                String title = s(v, "L");
                String se    = s(v, "SE");
                String le    = s(v, "LE");
                if (id == null || title == null) continue;
                String url = "https://1xstavka.ru/line/" + slug(se) + "/" + id
                        + (le != null ? "-" + slug(le) : "");
                tournaments.add(new TournamentDto(id, title, sportId, null, url));
            }
            return ParseResult.ok(tournaments, ms(start));
        } catch (Exception e) {
            log.warn("XBet fetchTournaments failed for sportId={}", sportId, e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public ParseResult<List<MatchDto>> fetchMatches(String tournamentId) {
        long start = System.currentTimeMillis();
        try {
            String sportId = resolveSportId(tournamentId);
            String apiUrl  = matchesApi + "sports=" + sportId + "&champs=" + tournamentId
                    + "&count=1000&mode=4";
            JsonNode root  = get(apiUrl);
            List<MatchDto> matches = new ArrayList<>();
            for (JsonNode v : valueArray(root)) {
                if (!tournamentId.equalsIgnoreCase(s(v, "LI"))) continue;
                String ci  = s(v, "CI");
                String o1  = s(v, "O1");
                String o2  = s(v, "O2");
                String o1e = s(v, "O1E");
                String o2e = s(v, "O2E");
                if (ci == null || o1 == null || o2 == null) continue;
                String url = "https://1xstavka.ru/line/" + slug(s(v, "SE")) + "/"
                        + tournamentId + "/" + ci + "-" + slug(o1e) + "-" + slug(o2e);
                Instant startsAt = parseInstant(s(v, "T"));
                boolean live = "1".equals(s(v, "CL"));
                matches.add(new MatchDto(ci, o1 + " - " + o2, tournamentId, url, startsAt, live));
            }
            return ParseResult.ok(matches, ms(start));
        } catch (Exception e) {
            log.warn("XBet fetchMatches failed for tournamentId={}", tournamentId, e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            get(sportsApi);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private JsonNode getChampsJsonCached() {
        long now = System.currentTimeMillis();
        CacheEntry c = champsCache.get();
        if (c.data() != null && c.expiresAt() > now) return c.data();
        JsonNode fresh = get(champsApi);
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

    private JsonNode get(String url) {
        return webClient.get().uri(url)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(12));
    }

    private static Iterable<JsonNode> valueArray(JsonNode root) {
        if (root == null) return List.of();
        JsonNode arr = root.path("Value");
        return arr.isArray() ? arr : List.of();
    }

    private static String s(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String slug(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\.", "").replace(" ", "-");
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
