package com.valui.parser.bookmaker.betboom;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.bookmaker.betboom.ws.WsRequestService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import proto.betboom.Current;
import proto.betboom.Envelope;
import proto.betboom.MatchesBody;
import proto.betboom.MatchesFrame;
import proto.betboom.ServerFrame;
import proto.betboom.SportAllBody;
import proto.betboom.TournamentListFrame;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

@Slf4j
@Component
@RequiredArgsConstructor
public class BetBoomParser implements BookmakerParser {

    private static final long WS_TIMEOUT_MS = 3_000L;
    // First N consecutive timeouts for the same tournament are logged WARN; after that → DEBUG.
    // Counter resets when the tournament returns data successfully.
    private static final int TIMEOUT_WARN_THRESHOLD = 3;
    private static final Current.TypeLine LINE = Current.TypeLine.LINE;

    private final WsRequestService ws;
    private final ConcurrentHashMap<String, Integer> tournamentTimeoutCount = new ConcurrentHashMap<>();
    final AtomicLong wsTimeoutTotal = new AtomicLong();

    @Override
    public BookmakerType getBookmaker() { return BookmakerType.BETBOOM; }

    @CircuitBreaker(name = "betboom-cb", fallbackMethod = "fetchSportsFallback")
    @Override
    public ParseResult<List<SportDto>> fetchSports() {
        long start = ms();
        byte[] req  = BetBoomSubscribeBuilder.sportAllBytes(LINE, 1);
        byte[] resp = filtered("fetchSports", req, Envelope::hasResponseSportAll);
        if (resp == null) throw new IllegalStateException("WS timeout");

        SportAllBody body = parseSportAll(resp).orElseThrow(() ->
                new IllegalStateException("Cannot parse SportAllBody"));
        List<SportDto> sports = new ArrayList<>();
        body.getRowsList().forEach(row -> {
            var s = row.getSport();
            if (s.getId() != 0 && !s.getName().isBlank())
                sports.add(new SportDto(String.valueOf(s.getId()), s.getName(), s.getAlias()));
        });
        return ParseResult.ok(sports, ms() - start);
    }

    @CircuitBreaker(name = "betboom-cb", fallbackMethod = "fetchTournamentsFallback")
    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = ms();
        int sid = parseInt(sportId, "sportId");
        byte[] req  = BetBoomSubscribeBuilder.sportTournamentsBytes(LINE, sid);
        byte[] resp = filtered("fetchTournaments[" + sid + "]", req,
                Envelope::hasResponseSportTournaments);
        if (resp == null) throw new IllegalStateException("WS timeout");

        TournamentListFrame tlf = parseTournamentList(resp).orElseThrow(() ->
                new IllegalStateException("Cannot parse TournamentListFrame"));
        String sportAlias = BetBoomSportsMap.getSport(sid).orElse(
                tlf.getList().hasSport() ? tlf.getList().getSport().getSport().getAlias() : "");
        List<TournamentDto> tournaments = new ArrayList<>();
        tlf.getList().getEntriesList().forEach(entry -> {
            var t = entry.getTournament();
            if (t.getTitle().isBlank()) return;
            int regionId = entry.hasCategory() && entry.getCategory().hasCategory()
                    ? entry.getCategory().getCategory().getRegionId() : t.getCountryId();
            String url = "https://betboom.ru/sport/" + sportAlias + "/" + regionId
                    + "/" + t.getId() + "?period=all";
            tournaments.add(new TournamentDto(String.valueOf(t.getId()), t.getTitle(),
                    sportId, String.valueOf(regionId), url));
        });
        return ParseResult.ok(tournaments, ms() - start);
    }

    @CircuitBreaker(name = "betboom-cb", fallbackMethod = "fetchMatchesFallback")
    @Override
    public ParseResult<List<ParsedMatchDto>> fetchMatches(String tournamentId) {
        long start = ms();
        int tid = parseInt(tournamentId, "tournamentId");
        byte[] req  = BetBoomSubscribeBuilder.tournamentMatchesBytes(LINE, tid);
        byte[] resp = filtered("fetchMatches[" + tid + "]", req,
                env -> hasExpectedTournamentMatches(env, tid));
        if (resp == null) throw new IllegalStateException("WS timeout");

        MatchesFrame mf = parseMatches(resp).orElseThrow(() ->
                new IllegalStateException("Cannot parse MatchesFrame"));
        if (!mf.hasSection()) {
            tournamentConsecutiveTimeouts(tournamentId, 0); // WS responded — reset timeout counter
            return ParseResult.ok(List.of(), ms() - start);
        }

        String sportAlias = mf.hasSport() && mf.getSport().hasSport()
                ? mf.getSport().getSport().getAlias() : "";
        int countryId = mf.hasCountry() && mf.getCountry().hasCountry()
                ? mf.getCountry().getCountry().getId() : 0;
        int sectionTid = mf.getSection().hasTournament()
                ? mf.getSection().getTournament().getId() : 0;

        List<ParsedMatchDto> matches = new ArrayList<>();
        for (MatchesBody.Match match : mf.getSection().getMatchesList()) {
            if (!match.hasHeader()) continue;
            int eid = match.getHeader().getId();
            int hTid = match.getHeader().getTournamentId();
            if (hTid != 0 && hTid != tid) continue;
            String home = match.getHeader().hasTeams() && match.getHeader().getTeams().hasHome()
                    ? match.getHeader().getTeams().getHome().getName() : "";
            String away = match.getHeader().hasTeams() && match.getHeader().getTeams().hasAway()
                    ? match.getHeader().getTeams().getAway().getName() : "";
            String title = (!home.isBlank() || !away.isBlank()) ? home + " - " + away : "match#" + eid;
            String url = "https://betboom.ru/sport/" + sportAlias + "/" + countryId
                    + "/" + sectionTid + "/" + eid + "?period=all";
            matches.add(new ParsedMatchDto(String.valueOf(eid), title, tournamentId, url,
                    parseInstant(match.getHeader().getStartsAt()), match.getHeader().getLive() == 1, null));
        }
        tournamentConsecutiveTimeouts(tournamentId, 0); // WS responded — reset timeout counter
        return ParseResult.ok(matches, ms() - start);
    }

    @Override
    public boolean isAvailable() { return ws.getPool().available() > 0; }

    @Override
    public boolean isConnectionReady() { return ws.getPool().available() > 0; }

    // ── fallbacks ─────────────────────────────────────────────────────────────

    private ParseResult<List<SportDto>> fetchSportsFallback(Throwable t) {
        log.warn("betboom fetchSports: {} — no data returned", fallbackReason(t));
        return ParseResult.error("betboom-cb: " + t.getMessage());
    }

    private ParseResult<List<TournamentDto>> fetchTournamentsFallback(String sportId, Throwable t) {
        log.warn("betboom fetchTournaments sportId={}: {} — no data returned", sportId, fallbackReason(t));
        return ParseResult.error("betboom-cb: " + t.getMessage());
    }

    private ParseResult<List<ParsedMatchDto>> fetchMatchesFallback(String tournamentId, Throwable t) {
        String reason = fallbackReason(t);
        if (t instanceof CallNotPermittedException) {
            // CB is OPEN — not a WS problem, don't pollute the per-tournament timeout counter
            log.debug("betboom fetchMatches tournamentId={}: circuit breaker OPEN — skipped", tournamentId);
        } else {
            long total = wsTimeoutTotal.incrementAndGet();
            int consecutive = tournamentConsecutiveTimeouts(tournamentId, 1);
            if (consecutive <= TIMEOUT_WARN_THRESHOLD) {
                log.warn("betboom fetchMatches tournamentId={}: {} — no data returned (consecutive={}, total={})",
                        tournamentId, reason, consecutive, total);
            } else {
                log.debug("betboom fetchMatches tournamentId={}: {} — no data (consecutive={}, total={})",
                        tournamentId, reason, consecutive, total);
            }
        }
        return ParseResult.error("betboom-cb: " + t.getMessage());
    }

    // delta=1 to increment, delta=0 to reset
    private int tournamentConsecutiveTimeouts(String tournamentId, int delta) {
        if (delta == 0) {
            tournamentTimeoutCount.remove(tournamentId);
            return 0;
        }
        int count = tournamentTimeoutCount.merge(tournamentId, delta, Integer::sum);
        if (tournamentTimeoutCount.size() > 5000) tournamentTimeoutCount.clear();
        return count;
    }

    private static String fallbackReason(Throwable t) {
        return (t instanceof CallNotPermittedException) ? "circuit breaker OPEN" : t.getMessage();
    }

    // ── WS helpers ────────────────────────────────────────────────────────────

    private byte[] filtered(String op, byte[] req, Predicate<Envelope> ok) {
        try { return ws.sendAndAwaitFiltered(req, WS_TIMEOUT_MS, ok); }
        catch (Exception e) { log.debug("{}: WS error", op, e); throw new RuntimeException(op + " WS error", e); }
    }

    private static boolean hasExpectedTournamentMatches(Envelope env, int expectedTid) {
        if (!env.hasResponseTournamentMatches()) return false;
        try {
            ServerFrame sf = ServerFrame.parseFrom(env.getResponseTournamentMatches().toByteArray());
            byte[] body = firstBody(sf);
            if (body == null) return false;
            MatchesFrame mf = MatchesFrame.parseFrom(body);
            // No section = empty response (no active LINE events). This is a valid reply
            // from the server; fetchMatches handles it by returning an empty list.
            if (!mf.hasSection()) return true;
            return mf.getSection().hasTournament()
                    && mf.getSection().getTournament().getId() == expectedTid;
        } catch (Exception e) { return false; }
    }

    // ── proto parsing ─────────────────────────────────────────────────────────

    private static Optional<SportAllBody> parseSportAll(byte[] raw) {
        try {
            Envelope env = Envelope.parseFrom(raw);
            ServerFrame sf = ServerFrame.parseFrom(env.getResponseSportAll().toByteArray());
            byte[] body = firstBody(sf); if (body == null) return Optional.empty();
            return Optional.of(SportAllBody.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            log.error("parseSportAll failed b64={}", b64(raw), e); return Optional.empty();
        }
    }

    private static Optional<TournamentListFrame> parseTournamentList(byte[] raw) {
        try {
            Envelope env = Envelope.parseFrom(raw);
            ServerFrame sf = ServerFrame.parseFrom(env.getResponseSportTournaments().toByteArray());
            byte[] body = firstBody(sf); if (body == null) return Optional.empty();
            return Optional.of(TournamentListFrame.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            log.error("parseTournamentList failed b64={}", b64(raw), e); return Optional.empty();
        }
    }

    private static Optional<MatchesFrame> parseMatches(byte[] raw) {
        try {
            Envelope env = Envelope.parseFrom(raw);
            ServerFrame sf = ServerFrame.parseFrom(env.getResponseTournamentMatches().toByteArray());
            byte[] body = firstBody(sf); if (body == null) return Optional.empty();
            return Optional.of(MatchesFrame.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            log.error("parseMatches failed b64={}", b64(raw), e); return Optional.empty();
        }
    }

    private static byte[] firstBody(ServerFrame sf) {
        return sf.getBodyList().stream()
                .filter(bs -> bs != null && !bs.isEmpty())
                .findFirst().map(ByteString::toByteArray).orElse(null);
    }

    // ── utils ─────────────────────────────────────────────────────────────────

    private static int parseInt(String value, String fieldName) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid " + fieldName + ": " + value); }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return Instant.EPOCH;
        try { return Instant.ofEpochSecond(Long.parseLong(s)); }
        catch (NumberFormatException e) {
            try { return Instant.parse(s); } catch (Exception e2) { return Instant.EPOCH; }
        }
    }

    private static String b64(byte[] d) {
        if (d == null) return "<null>";
        int max = Math.min(d.length, 512);
        return Base64.getEncoder().encodeToString(d.length == max ? d : Arrays.copyOf(d, max));
    }

    private static long ms() { return System.currentTimeMillis(); }
}
