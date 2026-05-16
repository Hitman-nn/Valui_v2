package com.valui.parser.bookmaker.xbet;

import com.fasterxml.jackson.databind.JsonNode;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.cache.ParserCacheService;
import com.valui.parser.http.BookmakerHttpClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.valui.parser.http.BookmakerHttpClient.BLOCK_TIMEOUT;

@Slf4j
@Component
public class XBetParser implements BookmakerParser {

    private static final String DEFAULT_BASE  = "https://1xbet.kz/service-api/LineFeed";
    private static final String CHAMPS_KEY    = "xbet:champs";
    private static final Duration CHAMPS_TTL  = Duration.ofMinutes(1);

    private final String sportsApi;
    private final String champsApi;
    private final String matchesApi;
    private final BookmakerHttpClient http;
    @Nullable private final ParserCacheService cache;

    private final AtomicLong lastResetAt = new AtomicLong(0);

    @Autowired
    public XBetParser(@Qualifier("xbetHttpClient") BookmakerHttpClient http, ParserCacheService cache) {
        this(DEFAULT_BASE, http, cache);
    }

    XBetParser(String apiBase, BookmakerHttpClient http) {
        this(apiBase, http, null);
    }

    XBetParser(String apiBase, org.springframework.web.reactive.function.client.WebClient wc) {
        this(apiBase, new BookmakerHttpClient(wc), null);
    }

    private XBetParser(String apiBase, BookmakerHttpClient http, @Nullable ParserCacheService cache) {
        this.sportsApi  = apiBase + "/GetSportsShortZip";
        this.champsApi  = apiBase + "/GetChampsZip";
        this.matchesApi = apiBase + "/Get1x2_VZip?";
        this.http  = http;
        this.cache = cache;
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
        for (JsonNode v : valueArray(getChampsJson())) {
            // SI = numeric sport ID; SE = sport alias slug (e.g. "football").
            // UrlParser.extractXbet returns the URL path segment, which may be either form.
            String si = s(v, "SI"), se = s(v, "SE"), le = s(v, "LE");
            if (!sportId.equalsIgnoreCase(si) && !sportId.equalsIgnoreCase(se)) continue;
            String id = s(v, "LI"), title = s(v, "L");
            if (id == null || title == null) continue;
            String url = "https://1xstavka.ru/line/" + slug(se) + "/" + id
                    + (le != null ? "-" + slug(le) : "");
            tournaments.add(new TournamentDto(id, title, sportId, null, url));
        }
        return ParseResult.ok(tournaments, ms() - start);
    }

    // E entry group/type IDs for odds parsing
    private static final int E_G_1X2 = 1, E_T_W1 = 1, E_T_DRAW = 2, E_T_W2 = 3;
    private static final int E_G_HCAP = 2, E_T_H1 = 7, E_T_H2 = 8;
    private static final int E_G_TOT = 17, E_T_TB = 9, E_T_TM = 10;

    @CircuitBreaker(name = "xbet-cb", fallbackMethod = "fetchMatchesFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<ParsedMatchDto>> fetchMatches(String tournamentId) {
        long start = ms();
        String sportId = resolveSportId(tournamentId);
        JsonNode root = block(http.getJson(
                matchesApi + "sports=" + sportId + "&champs=" + tournamentId + "&count=1000&mode=4",
                JsonNode.class));
        List<ParsedMatchDto> matches = new ArrayList<>();
        for (JsonNode v : valueArray(root)) {
            if (!tournamentId.equalsIgnoreCase(s(v, "LI"))) continue;
            String ci = s(v, "CI"), o1 = s(v, "O1"), o2 = s(v, "O2");
            String o1e = s(v, "O1E"), o2e = s(v, "O2E");
            if (ci == null || o1 == null || o2 == null) continue;
            String url = "https://1xstavka.ru/line/" + slug(s(v, "SE"))
                    + "/" + tournamentId + "/" + ci + "-" + slug(o1e) + "-" + slug(o2e);
            // Fix: use S (Unix timestamp) for startTime, not T (which is a count field)
            Instant startsAt = parseInstant(s(v, "S"));
            String extraData = buildExtraData(v);
            matches.add(new ParsedMatchDto(ci, o1 + " - " + o2, tournamentId, url,
                    startsAt, "1".equals(s(v, "CL")), extraData));
        }
        return ParseResult.ok(matches, ms() - start);
    }

    private String buildExtraData(JsonNode match) {
        JsonNode eArr = match.path("E");
        if (!eArr.isArray() || eArr.isEmpty()) return null;

        Double win1 = null, draw = null, win2 = null;
        Double hcap1v = null, hcap2v = null;
        String hcap1pt = null, hcap2pt = null;
        Double tbv = null, tmv = null;
        String tbpt = null;

        for (JsonNode e : eArr) {
            int g = e.path("G").asInt(-1);
            int t = e.path("T").asInt(-1);
            int ce = e.path("CE").asInt(0);
            double c = e.path("C").asDouble(0);

            if (g == E_G_1X2) {
                if (t == E_T_W1)   win1 = c;
                else if (t == E_T_DRAW) draw = c;
                else if (t == E_T_W2)  win2 = c;
            } else if (g == E_G_HCAP && ce == 1) {
                JsonNode pNode = e.path("P");
                String pt = pNode.isNull() || pNode.isMissingNode()
                        ? "0"
                        : formatPt(pNode.asDouble(0));
                if (t == E_T_H1) { hcap1v = c; hcap1pt = pt; }
                else if (t == E_T_H2) { hcap2v = c; hcap2pt = pt; }
            } else if (g == E_G_TOT && ce == 1) {
                JsonNode pNode = e.path("P");
                String pt = pNode.isNull() || pNode.isMissingNode() ? "0" : fmt(pNode.asDouble(0));
                if (t == E_T_TB) { tbv = c; tbpt = pt; }
                else if (t == E_T_TM) tmv = c;
            }
        }

        StringBuilder sb = new StringBuilder("{");
        long st = match.path("S").asLong(0);
        if (st > 0)   sb.append("\"st\":").append(st).append(",");
        if (win1 != null) sb.append("\"w1\":").append(fmt(win1)).append(",");
        if (draw != null) sb.append("\"wX\":").append(fmt(draw)).append(",");
        if (win2 != null) sb.append("\"w2\":").append(fmt(win2)).append(",");
        if (hcap1v != null && hcap2v != null) {
            sb.append("\"h1\":{\"v\":").append(fmt(hcap1v))
              .append(",\"pt\":\"").append(hcap1pt).append("\"},");
            sb.append("\"h2\":{\"v\":").append(fmt(hcap2v))
              .append(",\"pt\":\"").append(hcap2pt).append("\"},");
        }
        if (tbv != null && tmv != null) {
            sb.append("\"tb\":{\"v\":").append(fmt(tbv))
              .append(",\"pt\":\"").append(tbpt != null ? tbpt : "0").append("\"},");
            sb.append("\"tm\":{\"v\":").append(fmt(tmv))
              .append(",\"pt\":\"").append(tbpt != null ? tbpt : "0").append("\"},");
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    private static String formatPt(double p) {
        if (p == 0.0) return "0";
        String s = p % 1 == 0 ? String.valueOf((long) p) : String.valueOf(p);
        return p > 0 ? "+" + s : s;
    }

    private static String fmt(double v) {
        String s = String.format("%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    @Override
    public boolean isAvailable() {
        try { block(http.getJson(sportsApi, JsonNode.class)); return true; }
        catch (Exception e) { return false; }
    }

    // ── fallbacks ─────────────────────────────────────────────────────────────

    private ParseResult<List<SportDto>> fetchSportsFallback(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("xbet fetchSports skipped — CB open/half-open");
        } else if (isConnectionReset(t)) {
            logBurstReset("xbet fetchSports: connection reset — proxy rotation?");
        } else {
            log.warn("xbet fetchSports fallback [{}]: {}", t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("xbet-cb: " + t.getMessage());
    }

    private ParseResult<List<TournamentDto>> fetchTournamentsFallback(String sportId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("xbet fetchTournaments skipped — CB open/half-open sportId={}", sportId);
        } else if (isConnectionReset(t)) {
            logBurstReset("xbet fetchTournaments: connection reset — proxy rotation?");
        } else {
            log.warn("xbet fetchTournaments fallback sportId={} [{}]: {}", sportId, t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("xbet-cb: " + t.getMessage());
    }

    private ParseResult<List<ParsedMatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("xbet fetchMatches skipped — CB open/half-open tournamentId={}", tournamentId);
        } else if (isConnectionReset(t)) {
            logBurstReset("xbet fetchMatches: connection reset tournamentId=" + tournamentId + " — proxy rotation?");
        } else {
            log.warn("xbet fetchMatches fallback tournamentId={} [{}]: {}", tournamentId, t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("xbet-cb: " + t.getMessage());
    }

    private void logBurstReset(String msg) {
        long now = System.currentTimeMillis();
        long prev = lastResetAt.getAndUpdate(p -> now - p > 2_000 ? now : p);
        if (now - prev > 2_000) {
            log.warn(msg);
        } else {
            log.debug("{} (burst)", msg);
        }
    }

    private static boolean isConnectionReset(Throwable t) {
        Throwable cause = t.getCause();
        if (cause == null) cause = t;
        return cause instanceof IOException && "Connection reset".equals(cause.getMessage());
    }

    private static String describe(Throwable t) {
        if (t.getMessage() != null) return t.getMessage();
        Throwable cause = t.getCause();
        return cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "(no message)";
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private JsonNode getChampsJson() {
        if (cache != null) {
            return cache.getJson(CHAMPS_KEY).orElseGet(() -> {
                JsonNode fresh = block(http.getJson(champsApi, JsonNode.class));
                cache.setJson(CHAMPS_KEY, fresh, CHAMPS_TTL);
                return fresh;
            });
        }
        return block(http.getJson(champsApi, JsonNode.class));
    }

    private String resolveSportId(String tournamentId) {
        for (JsonNode v : valueArray(getChampsJson())) {
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
