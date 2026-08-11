package com.valui.parser.bookmaker.fonbet;

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

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static com.valui.parser.http.BookmakerHttpClient.BLOCK_TIMEOUT;
import static com.valui.parser.util.ExceptionDescriptions.describe;

@Slf4j
@Component
public class FonbetParser implements BookmakerParser {

    private static final String DEFAULT_API =
            "https://line32w.bk6bba-resources.com/events/list?lang=ru&scopeMarket=1600";

    // Snapshot TTL: reuse the same JSON across fetchSports/fetchTournaments/fetchMatches
    // calls within one monitor cycle so we hit Fonbet API once per cycle, not three times.
    private static final long SNAP_TTL_MS = 30_000;

    private final BookmakerHttpClient http;
    private final FonbetEndpointPool pool;
    private final String fallbackUrl;
    private final AtomicReference<CachedSnap> snapCache = new AtomicReference<>();
    // Guards snapCache's refresh — ReentrantLock, not synchronized: the winner holds it
    // across a blocking HTTP call (up to BLOCK_TIMEOUT), and synchronized would pin the
    // carrier thread of every virtual thread queued behind it for that whole duration.
    // Without this lock, all ~338 Fonbet controllers whose poll lands right as the TTL
    // expires see the cache as stale at once and each independently fire their own HTTP
    // call — a stampede that floods the shared "fonbet-pool" connection pool far beyond
    // its pending-acquire queue and starves everyone behind it (PoolAcquirePendingLimitException).
    private final ReentrantLock snapLock = new ReentrantLock();

    private record CachedSnap(JsonNode data, long ts) {}

    @Autowired
    public FonbetParser(@Qualifier("fonbetHttpClient") BookmakerHttpClient http, FonbetEndpointPool pool) {
        this.http = http;
        this.pool = pool;
        this.fallbackUrl = DEFAULT_API;
    }

    FonbetParser(String apiUrl, BookmakerHttpClient http) {
        this.http = http;
        this.pool = null;
        this.fallbackUrl = apiUrl;
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
        JsonNode snap = fetchSnapshot();
        List<SportDto> sports = new ArrayList<>();
        for (JsonNode item : sportsArray(snap)) {
            if (!item.path("parentId").isNull() && !item.path("parentId").isMissingNode()) continue;
            String id = s(item, "id"), name = s(item, "name");
            if (id != null && name != null) sports.add(new SportDto(id, name, slugify(name, id)));
        }
        return ParseResult.ok(sports, ms() - start);
    }

    @CircuitBreaker(name = "fonbet-cb", fallbackMethod = "fetchTournamentsFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = ms();
        JsonNode snap = fetchSnapshot();
        List<TournamentDto> tournaments = new ArrayList<>();
        for (JsonNode item : sportsArray(snap)) {
            JsonNode parentId = item.path("parentId");
            if (parentId.isNull() || parentId.isMissingNode()) continue;
            if (!sportId.equals(parentId.asText())) continue;
            String id = s(item, "id"), name = s(item, "name");
            if (id == null || name == null) continue;
            tournaments.add(new TournamentDto(id, name, sportId, null,
                    "https://fon.bet/sports/" + sportId + "/tournament/" + id));
        }
        return ParseResult.ok(tournaments, ms() - start);
    }

    // Factor IDs for 1x2 outcomes
    private static final int F_WIN1 = 921, F_DRAW = 922, F_WIN2 = 923;
    // Factor IDs for main total (Over/Under)
    private static final int F_TOT_OVER = 930, F_TOT_UNDER = 931;
    // Known handicap pairs: Ф1 id → Ф2 id (ordered by typical precedence on site)
    private static final java.util.Map<Integer, Integer> HCAP_PAIRS = java.util.Map.of(910, 912, 927, 928);

    @CircuitBreaker(name = "fonbet-cb", fallbackMethod = "fetchMatchesFallback")
    @Retry(name = "parser-retry")
    @Override
    public ParseResult<List<ParsedMatchDto>> fetchMatches(String tournamentId) {
        long start = ms();
        JsonNode snap = fetchSnapshot();
        List<ParsedMatchDto> matches = new ArrayList<>();
        String parentSportId = findParentSportId(snap, tournamentId);

        // Build eventId → customFactors map for quick lookup
        java.util.Map<String, JsonNode> factorsById = new java.util.HashMap<>();
        for (JsonNode cf : snap.path("customFactors")) {
            String eid = cf.path("e").asText(null);
            if (eid != null) factorsById.put(eid, cf.path("factors"));
        }

        JsonNode events = snap.path("events");
        if (!events.isArray()) return ParseResult.ok(matches, ms() - start);
        for (JsonNode ev : events) {
            if (!tournamentId.equals(s(ev, "sportId"))) continue;
            JsonNode parentId = ev.path("parentId");
            if (!parentId.isNull() && !parentId.isMissingNode()) continue;
            String id = s(ev, "id"), t1 = s(ev, "team1"), t2 = s(ev, "team2");
            if (id == null || t1 == null || t2 == null) continue;
            Instant startsAt = parseInstant(s(ev, "startTime"), false);
            String matchUrl = parentSportId != null
                    ? "https://fon.bet/sports/" + parentSportId + "/" + tournamentId + "/" + id
                    : "https://fon.bet/sports/" + tournamentId + "/" + id;
            String extraData = buildExtraData(id, ev, factorsById);
            matches.add(new ParsedMatchDto(id, t1 + " - " + t2, tournamentId,
                    matchUrl, startsAt, ev.path("live").asBoolean(false), extraData));
        }
        return ParseResult.ok(matches, ms() - start);
    }

    private record HcapPair(JsonNode f1, JsonNode f2, int absP) {}

    private String buildExtraData(String eventId, JsonNode ev, java.util.Map<String, JsonNode> factorsById) {
        JsonNode factors = factorsById.get(eventId);

        Double win1 = null, draw = null, win2 = null;
        Double hcap1v = null, hcap2v = null;
        String hcap1pt = null, hcap2pt = null;
        Double tbv = null, tmv = null;
        String tbpt = null;

        if (factors != null && factors.isArray()) {
            // Collect 1x2 and total odds
            for (JsonNode fac : factors) {
                int fid = fac.path("f").asInt(0);
                double v = fac.path("v").asDouble(0);
                if (fid == F_WIN1) win1 = v;
                else if (fid == F_DRAW) draw = v;
                else if (fid == F_WIN2) win2 = v;
                else if (fid == F_TOT_OVER) { tbv = v; tbpt = fac.path("pt").asText(null); }
                else if (fid == F_TOT_UNDER) tmv = v;
            }

            // Build p → node map to find handicap pairs
            java.util.Map<Integer, JsonNode> byP = new java.util.LinkedHashMap<>();
            for (JsonNode fac : factors) {
                JsonNode pNode = fac.path("p");
                if (!pNode.isMissingNode()) {
                    int p = pNode.asInt(Integer.MIN_VALUE);
                    if (p != Integer.MIN_VALUE) byP.putIfAbsent(p, fac);
                }
            }

            // Find all Ф1/Ф2 pairs: negative-p factor + matching positive-p factor
            java.util.List<HcapPair> pairs = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Integer, JsonNode> entry : byP.entrySet()) {
                int p = entry.getKey();
                if (p >= 0) continue; // process only negative side as Ф1
                JsonNode f2Node = byP.get(-p);
                if (f2Node != null) pairs.add(new HcapPair(entry.getValue(), f2Node, -p));
            }
            // p=0 special case: use known pair IDs
            if (byP.containsKey(0)) {
                JsonNode f1zero = null, f2zero = null;
                for (JsonNode fac : factors) {
                    int fid = fac.path("f").asInt(0);
                    int p = fac.path("p").asInt(Integer.MIN_VALUE);
                    if (p != 0) continue;
                    if (HCAP_PAIRS.containsKey(fid) && f1zero == null) f1zero = fac;
                    else if (HCAP_PAIRS.containsValue(fid) && f2zero == null) f2zero = fac;
                }
                if (f1zero != null && f2zero != null) pairs.add(new HcapPair(f1zero, f2zero, 0));
            }

            // Find the pair where sum of |v1−2| + |v2−2| is minimised — the most "balanced" line
            if (!pairs.isEmpty()) {
                HcapPair main = pairs.stream()
                        .min(java.util.Comparator.comparingDouble(p ->
                                Math.abs(p.f1().path("v").asDouble(0) - 2.0)
                              + Math.abs(p.f2().path("v").asDouble(0) - 2.0)))
                        .orElse(pairs.get(0));
                hcap1v  = main.f1().path("v").asDouble(0);
                hcap1pt = main.f1().path("pt").asText(null);
                hcap2v  = main.f2().path("v").asDouble(0);
                hcap2pt = main.f2().path("pt").asText(null);
            }
        }

        // Build compact JSON manually to avoid Jackson dependency in this module's config
        StringBuilder sb = new StringBuilder("{");
        long startTime = ev.path("startTime").asLong(0);
        if (startTime > 0) sb.append("\"st\":").append(startTime).append(",");
        if (win1 != null)  sb.append("\"w1\":").append(fmt(win1)).append(",");
        if (draw != null)  sb.append("\"wX\":").append(fmt(draw)).append(",");
        if (win2 != null)  sb.append("\"w2\":").append(fmt(win2)).append(",");
        if (hcap1v != null && hcap2v != null) {
            sb.append("\"h1\":{\"v\":").append(fmt(hcap1v))
              .append(",\"pt\":\"").append(hcap1pt != null ? hcap1pt : "0").append("\"},");
            sb.append("\"h2\":{\"v\":").append(fmt(hcap2v))
              .append(",\"pt\":\"").append(hcap2pt != null ? hcap2pt : "0").append("\"},");
        }
        if (tbv != null && tmv != null) {
            sb.append("\"tb\":{\"v\":").append(fmt(tbv))
              .append(",\"pt\":\"").append(tbpt != null ? tbpt : "0").append("\"},");
            sb.append("\"tm\":{\"v\":").append(fmt(tmv))
              .append(",\"pt\":\"").append(tbpt != null ? tbpt : "0").append("\"},");
        }
        // Remove trailing comma if present
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    private static String fmt(double v) {
        // Format as "1.9" not "1.900000" — trim trailing zeros after dot
        String s = String.format("%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private String findParentSportId(JsonNode snap, String sportId) {
        for (JsonNode item : sportsArray(snap)) {
            if (sportId.equals(s(item, "id"))) {
                JsonNode parentId = item.path("parentId");
                if (!parentId.isNull() && !parentId.isMissingNode()) return parentId.asText();
            }
        }
        return null;
    }

    @Override
    public boolean isAvailable() {
        // Calls fetchSnapshot() directly (private method) — intentionally bypasses the
        // @CircuitBreaker AOP proxy, which only intercepts public calls from outside the bean.
        // Health checks should probe the real connection, not short-circuit via the breaker.
        try { fetchSnapshot(); return true; }
        catch (Exception e) {
            log.debug("Fonbet isAvailable failed: {}", describe(e));
            return false;
        }
    }

    // ── fallbacks ─────────────────────────────────────────────────────────────

    private ParseResult<List<SportDto>> fetchSportsFallback(Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("fonbet fetchSports skipped — CB open/half-open");
        } else if (Thread.currentThread().isInterrupted()) {
            // Our own fetch-budget timeout interrupted this call — ControllerTask already
            // logs "Fetch budget exceeded" with full context, so this would just double it.
            log.debug("fonbet fetchSports fallback [{}]: {} (budget interrupt)", t.getClass().getSimpleName(), describe(t));
        } else {
            log.warn("fonbet fetchSports fallback [{}]: {}", t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("fonbet-cb: " + t.getMessage());
    }

    private ParseResult<List<TournamentDto>> fetchTournamentsFallback(String sportId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("fonbet fetchTournaments skipped — CB open/half-open sportId={}", sportId);
        } else if (Thread.currentThread().isInterrupted()) {
            log.debug("fonbet fetchTournaments fallback sportId={} [{}]: {} (budget interrupt)",
                    sportId, t.getClass().getSimpleName(), describe(t));
        } else {
            log.warn("fonbet fetchTournaments fallback sportId={} [{}]: {}", sportId, t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("fonbet-cb: " + t.getMessage());
    }

    private ParseResult<List<ParsedMatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.debug("fonbet fetchMatches skipped — CB open/half-open tournamentId={}", tournamentId);
        } else if (Thread.currentThread().isInterrupted()) {
            log.debug("fonbet fetchMatches fallback tournamentId={} [{}]: {} (budget interrupt)",
                    tournamentId, t.getClass().getSimpleName(), describe(t));
        } else {
            log.warn("fonbet fetchMatches fallback tournamentId={} [{}]: {}", tournamentId, t.getClass().getSimpleName(), describe(t));
        }
        return ParseResult.error("fonbet-cb: " + t.getMessage());
    }

    // ── snapshot ──────────────────────────────────────────────────────────────

    private JsonNode fetchSnapshot() {
        CachedSnap cached = snapCache.get();
        if (cached != null && System.currentTimeMillis() - cached.ts < SNAP_TTL_MS) {
            return cached.data;
        }
        snapLock.lock();
        try {
            cached = snapCache.get();
            if (cached != null && System.currentTimeMillis() - cached.ts < SNAP_TTL_MS) {
                return cached.data;
            }
            String url = (pool != null) ? pool.getBestEndpoint() : fallbackUrl;
            if (pool != null && pool.aliveCount() == 0) {
                log.warn("[Fonbet] no alive mirrors in pool — using endpoint {} anyway", url);
            }
            try {
                JsonNode snap = http.getJson(url, JsonNode.class).block(BLOCK_TIMEOUT);
                if (snap == null) throw new IllegalStateException("Fonbet API returned null");
                if (pool != null) pool.markSuccess(url);
                snapCache.set(new CachedSnap(snap, System.currentTimeMillis()));
                return snap;
            } catch (Exception e) {
                log.debug("[Fonbet] endpoint {} failed: {}", url, describe(e));
                if (pool != null) pool.markFailure(url);
                throw e;
            }
        } finally {
            snapLock.unlock();
        }
    }

    // ── utils ─────────────────────────────────────────────────────────────────

    private static Iterable<JsonNode> sportsArray(JsonNode snap) {
        JsonNode arr = snap.path("sports");
        return arr.isArray() ? arr : List.of();
    }

    private static String s(JsonNode n, String f) {
        JsonNode v = n.path(f); return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /**
     * Converts a sport name to an ASCII alias. For Cyrillic / non-Latin names where
     * NFD decomposition yields no ASCII characters, falls back to the numeric sport ID
     * so the alias is never an empty string.
     */
    private static String slugify(String name, String fallbackId) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? fallbackId : slug;
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
