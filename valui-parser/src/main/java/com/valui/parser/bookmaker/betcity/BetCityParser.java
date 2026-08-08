package com.valui.parser.bookmaker.betcity;

import com.fasterxml.jackson.databind.JsonNode;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.http.BookmakerHttpClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.valui.parser.http.BookmakerHttpClient.BLOCK_TIMEOUT;
import static com.valui.parser.util.ExceptionDescriptions.describe;

@Slf4j
@Component
public class BetCityParser implements BookmakerParser {

    private static final String DEFAULT_BASE = "https://ad.betcity.ru/d/off";

    private final String sportsApi;
    private final String champsApi;
    private final String eventsApi;
    private final BookmakerHttpClient http;

    // BetcitySportsMap is a small static table; an unmapped sportId falls back to a generic
    // "sport" URL segment that's silently wrong (matches parse fine, but the resulting
    // matchUrl/tournament link is broken). Warn once per sportId, not on every poll.
    private final Set<String> warnedUnmappedSport = ConcurrentHashMap.newKeySet();

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
        // Unlike fetchMatches (which already guards this), a null root here would NPE, and
        // an NPE's message is usually null too — the CB fallback would then log the unhelpful
        // "betcity fetchSports fallback: null" ExceptionDescriptions.describe() already
        // mitigates that, but avoiding the NPE outright is still cleaner.
        if (root == null) return ParseResult.ok(List.of(), ms() - start);
        JsonNode arr = root.path("reply").path("sports");
        List<SportDto> sports = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String id = s(item, "id_sp"), name = s(item, "name_sp");
                if (id == null || name == null) continue;
                String alias = BetcitySportsMap.getSport(safeInt(id)).orElseGet(() -> {
                    log.debug("[BetCity] sport id={} name={} not in BetcitySportsMap — using name-derived alias", id, name);
                    return name.toLowerCase();
                });
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
        if (root == null) return ParseResult.ok(List.of(), ms() - start);
        JsonNode chmps = root.path("reply").path("sports").path(sportId).path("chmps");
        List<TournamentDto> tournaments = new ArrayList<>();
        String alias = resolveAlias(sportId);
        chmps.fields().forEachRemaining(e -> {
            String id = e.getKey(), title = s(e.getValue(), "name_ch");
            if (title == null) return;
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
            String alias = resolveAlias(sportId);
            evts.fields().forEachRemaining(evtEntry -> {
                String id = evtEntry.getKey();
                JsonNode ev = evtEntry.getValue();
                String t1 = s(ev, "name_ht"), t2 = s(ev, "name_at");
                if (t1 == null || t2 == null) return;
                String url = "https://betcity.ru/ru/line/" + alias + "/" + tournamentId + "/" + id;
                Instant startsAt = Instant.ofEpochSecond(ev.path("date_ev").asLong(0));
                boolean live = ev.path("is_live").asInt(0) == 1;
                matches.add(new ParsedMatchDto(id, t1 + " - " + t2, tournamentId, url,
                        startsAt, live, buildExtraData(id, ev)));
            });
        });
        return ParseResult.ok(matches, ms() - start);
    }

    @Override
    public boolean isAvailable() {
        try { block(http.getJson(sportsApi, JsonNode.class)); return true; }
        catch (Exception e) { log.debug("BetCity isAvailable failed: {}", describe(e)); return false; }
    }

    /**
     * URL alias for a sportId, falling back to a generic (visibly broken) "sport" segment when
     * BetcitySportsMap doesn't have an entry — matches still parse correctly, but the generated
     * tournament/match URL is wrong with no other signal pointing at the unmapped sportId.
     */
    private String resolveAlias(String sportId) {
        return BetcitySportsMap.getSport(safeInt(sportId)).orElseGet(() -> {
            if (warnedUnmappedSport.add(sportId)) {
                log.warn("[BetCity] sportId={} not in BetcitySportsMap — URL will use generic 'sport' segment", sportId);
            }
            return "sport";
        });
    }

    // ── extraData ─────────────────────────────────────────────────────────────

    /**
     * Extracts start time, 1x2 odds, main handicap, and main total from the event node.
     *
     * Structure:
     *   main."69".data.{evtId}.blocks.Wm.{P1/X/P2}.kf  → П1/Х/П2
     *   main."71".data.{evtId}.blocks.F1m               → Ф1/Ф2 (value + kf)
     *   main."72".data.{evtId}.blocks.T1m               → Тотал (Tot + Tb.kf/Tm.kf)
     */
    private static String buildExtraData(String evtId, JsonNode ev) {
        long st = ev.path("date_ev").asLong(0);
        JsonNode main = ev.path("main");

        // 1x2
        JsonNode wm    = main.path("69").path("data").path(evtId).path("blocks").path("Wm");
        double w1      = wm.path("P1").path("kf").asDouble(0);
        double wX      = wm.path("X") .path("kf").asDouble(0);
        double w2      = wm.path("P2").path("kf").asDouble(0);

        // Handicap
        JsonNode f1m   = main.path("71").path("data").path(evtId).path("blocks").path("F1m");
        double h1v     = f1m.path("Kf_F1").path("kf").asDouble(0);
        double h1pt    = f1m.path("F1").asDouble(Double.NaN);
        double h2v     = f1m.path("Kf_F2").path("kf").asDouble(0);
        double h2pt    = f1m.path("F2").asDouble(Double.NaN);

        // Total
        JsonNode t1m   = main.path("72").path("data").path(evtId).path("blocks").path("T1m");
        double tbv     = t1m.path("Tb").path("kf").asDouble(0);
        double tmv     = t1m.path("Tm").path("kf").asDouble(0);
        double totPt   = t1m.path("Tot").asDouble(Double.NaN);

        StringBuilder sb = new StringBuilder("{");
        if (st > 0)    sb.append("\"st\":").append(st).append(",");
        if (w1  > 1.0) sb.append("\"w1\":").append(fmt(w1)).append(",");
        if (wX  > 1.0) sb.append("\"wX\":").append(fmt(wX)).append(",");
        if (w2  > 1.0) sb.append("\"w2\":").append(fmt(w2)).append(",");
        if (h1v > 1.0 && h2v > 1.0 && !Double.isNaN(h1pt) && !Double.isNaN(h2pt)) {
            sb.append("\"h1\":{\"v\":").append(fmt(h1v)).append(",\"pt\":\"").append(fmtPt(h1pt)).append("\"},");
            sb.append("\"h2\":{\"v\":").append(fmt(h2v)).append(",\"pt\":\"").append(fmtPt(h2pt)).append("\"},");
        }
        if (tbv > 1.0 && tmv > 1.0 && !Double.isNaN(totPt)) {
            sb.append("\"tb\":{\"v\":").append(fmt(tbv)).append(",\"pt\":\"").append(fmtPt(totPt)).append("\"},");
            sb.append("\"tm\":{\"v\":").append(fmt(tmv)).append(",\"pt\":\"").append(fmtPt(totPt)).append("\"},");
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    private static String fmt(double v) {
        String s = String.format("%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private static String fmtPt(double v) {
        if (v == 0.0) return "0";
        String s = String.format("%.2f", Math.abs(v)).replaceAll("0+$", "").replaceAll("\\.$", "");
        return v > 0 ? "+" + s : "-" + s;
    }

    // ── fallbacks ─────────────────────────────────────────────────────────────

    private ParseResult<List<SportDto>> fetchSportsFallback(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("betcity fetchSports skipped — CB open/half-open");
        } else {
            log.warn("betcity fetchSports fallback [{}]: {}", t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("betcity-cb: " + t.getMessage());
    }

    private ParseResult<List<TournamentDto>> fetchTournamentsFallback(String sportId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("betcity fetchTournaments skipped — CB open/half-open sportId={}", sportId);
        } else {
            log.warn("betcity fetchTournaments fallback sportId={} [{}]: {}", sportId, t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("betcity-cb: " + t.getMessage());
    }

    private ParseResult<List<ParsedMatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("betcity fetchMatches skipped — CB open/half-open tournamentId={}", tournamentId);
        } else {
            log.warn("betcity fetchMatches fallback tournamentId={} [{}]: {}", tournamentId, t.getClass().getSimpleName(), describe(t));
        }
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
