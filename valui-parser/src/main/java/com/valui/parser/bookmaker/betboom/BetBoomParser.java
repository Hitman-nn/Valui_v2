package com.valui.parser.bookmaker.betboom;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.MatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.bookmaker.betboom.ws.WsRequestService;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

@Slf4j
@Component
@RequiredArgsConstructor
public class BetBoomParser implements BookmakerParser {

    private static final long WS_TIMEOUT_MS = 3_000L;
    private static final Current.TypeLine LINE = Current.TypeLine.LINE;

    private final WsRequestService ws;

    @Override
    public BookmakerType getBookmaker() {
        return BookmakerType.BETBOOM;
    }

    @Override
    public ParseResult<List<SportDto>> fetchSports() {
        long start = System.currentTimeMillis();
        try {
            byte[] req = BetBoomSubscribeBuilder.sportAllBytes(LINE, 1);
            byte[] resp = filtered("fetchSports", req, Envelope::hasResponseSportAll);
            if (resp == null) return ParseResult.error("WS timeout");

            SportAllBody body = parseSportAll(resp).orElse(null);
            if (body == null) return ParseResult.error("Cannot parse SportAllBody");

            List<SportDto> sports = new ArrayList<>();
            body.getRowsList().forEach(row -> {
                var sport = row.getSport();
                if (sport.getId() != 0 && !sport.getName().isBlank()) {
                    sports.add(new SportDto(
                            String.valueOf(sport.getId()),
                            sport.getName(),
                            sport.getAlias()
                    ));
                }
            });
            return ParseResult.ok(sports, ms(start));
        } catch (Exception e) {
            log.warn("BetBoom fetchSports failed", e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public ParseResult<List<TournamentDto>> fetchTournaments(String sportId) {
        long start = System.currentTimeMillis();
        int sid;
        try { sid = Integer.parseInt(sportId); }
        catch (NumberFormatException e) { return ParseResult.error("Invalid sportId: " + sportId); }

        try {
            byte[] req  = BetBoomSubscribeBuilder.sportTournamentsBytes(LINE, sid);
            byte[] resp = filtered("fetchTournaments[" + sid + "]", req,
                    Envelope::hasResponseSportTournaments);
            if (resp == null) return ParseResult.error("WS timeout");

            TournamentListFrame tlf = parseTournamentList(resp).orElse(null);
            if (tlf == null) return ParseResult.error("Cannot parse TournamentListFrame");

            String sportAlias = BetBoomSportsMap.getSport(sid).orElse("");
            if (sportAlias.isBlank() && tlf.getList().hasSport()) {
                sportAlias = tlf.getList().getSport().getSport().getAlias();
            }
            final String alias = sportAlias;

            List<TournamentDto> tournaments = new ArrayList<>();
            tlf.getList().getEntriesList().forEach(entry -> {
                var t = entry.getTournament();
                if (t.getTitle().isBlank()) return;
                // prefer region_id from category over tournament.country_id per proto docs
                int regionId = entry.hasCategory() && entry.getCategory().hasCategory()
                        ? entry.getCategory().getCategory().getRegionId()
                        : t.getCountryId();
                String url = "https://betboom.ru/sport/" + alias + "/" + regionId
                        + "/" + t.getId() + "?period=all";
                tournaments.add(new TournamentDto(
                        String.valueOf(t.getId()),
                        t.getTitle(),
                        sportId,
                        String.valueOf(regionId),
                        url
                ));
            });
            return ParseResult.ok(tournaments, ms(start));
        } catch (Exception e) {
            log.warn("BetBoom fetchTournaments failed for sportId={}", sportId, e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public ParseResult<List<MatchDto>> fetchMatches(String tournamentId) {
        long start = System.currentTimeMillis();
        int tid;
        try { tid = Integer.parseInt(tournamentId); }
        catch (NumberFormatException e) { return ParseResult.error("Invalid tournamentId: " + tournamentId); }

        try {
            byte[] req  = BetBoomSubscribeBuilder.tournamentMatchesBytes(LINE, tid);
            byte[] resp = filtered("fetchMatches[" + tid + "]", req,
                    env -> hasExpectedTournamentMatches(env, tid));
            if (resp == null) return ParseResult.error("WS timeout");

            MatchesFrame mf = parseMatches(resp).orElse(null);
            if (mf == null || !mf.hasSection()) return ParseResult.error("Cannot parse MatchesFrame");

            String sportAlias = mf.hasSport() && mf.getSport().hasSport()
                    ? mf.getSport().getSport().getAlias() : "";
            int countryId = mf.hasCountry() && mf.getCountry().hasCountry()
                    ? mf.getCountry().getCountry().getId() : 0;
            int sectionTid = mf.getSection().hasTournament()
                    ? mf.getSection().getTournament().getId() : 0;

            List<MatchDto> matches = new ArrayList<>();
            for (MatchesBody.Match match : mf.getSection().getMatchesList()) {
                if (!match.hasHeader()) continue;
                int eventId = match.getHeader().getId();
                int headerTid = match.getHeader().getTournamentId();
                if (headerTid != 0 && headerTid != tid) continue;

                String home = match.getHeader().hasTeams() && match.getHeader().getTeams().hasHome()
                        ? match.getHeader().getTeams().getHome().getName() : "";
                String away = match.getHeader().hasTeams() && match.getHeader().getTeams().hasAway()
                        ? match.getHeader().getTeams().getAway().getName() : "";
                String title = (!home.isBlank() || !away.isBlank()) ? home + " - " + away : "match#" + eventId;

                String url = "https://betboom.ru/sport/" + sportAlias + "/" + countryId
                        + "/" + sectionTid + "/" + eventId + "?period=all";

                Instant startsAt = parseInstant(match.getHeader().getStartsAt());
                boolean isLive   = match.getHeader().getLive() == 1;
                matches.add(new MatchDto(String.valueOf(eventId), title, tournamentId, url, startsAt, isLive));
            }
            return ParseResult.ok(matches, ms(start));
        } catch (Exception e) {
            log.warn("BetBoom fetchMatches failed for tournamentId={}", tournamentId, e);
            return ParseResult.error(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return ws.getPool().available() > 0;
    }

    // ── WS helpers ────────────────────────────────────────────────────────────

    private byte[] filtered(String op, byte[] req, Predicate<Envelope> ok) {
        try {
            return ws.sendAndAwaitFiltered(req, WS_TIMEOUT_MS, ok);
        } catch (Exception e) {
            log.error("{}: WS error", op, e);
            return null;
        }
    }

    private static boolean hasExpectedTournamentMatches(Envelope env, int expectedTid) {
        if (!env.hasResponseTournamentMatches()) return false;
        try {
            ServerFrame sf = ServerFrame.parseFrom(env.getResponseTournamentMatches().toByteArray());
            byte[] body = firstBody(sf);
            if (body == null) return false;
            MatchesFrame mf = MatchesFrame.parseFrom(body);
            return mf.hasSection() && mf.getSection().hasTournament()
                    && mf.getSection().getTournament().getId() == expectedTid;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Proto parsing ─────────────────────────────────────────────────────────

    private static Optional<SportAllBody> parseSportAll(byte[] raw) {
        try {
            Envelope env = Envelope.parseFrom(raw);
            ServerFrame sf = ServerFrame.parseFrom(env.getResponseSportAll().toByteArray());
            byte[] body = firstBody(sf);
            if (body == null) return Optional.empty();
            return Optional.of(SportAllBody.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            log.error("parseSportAll failed, b64={}", base64Safe(raw), e);
            return Optional.empty();
        }
    }

    private static Optional<TournamentListFrame> parseTournamentList(byte[] raw) {
        try {
            Envelope env = Envelope.parseFrom(raw);
            ServerFrame sf = ServerFrame.parseFrom(env.getResponseSportTournaments().toByteArray());
            byte[] body = firstBody(sf);
            if (body == null) return Optional.empty();
            return Optional.of(TournamentListFrame.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            log.error("parseTournamentList failed, b64={}", base64Safe(raw), e);
            return Optional.empty();
        }
    }

    private static Optional<MatchesFrame> parseMatches(byte[] raw) {
        try {
            Envelope env = Envelope.parseFrom(raw);
            ServerFrame sf = ServerFrame.parseFrom(env.getResponseTournamentMatches().toByteArray());
            byte[] body = firstBody(sf);
            if (body == null) return Optional.empty();
            return Optional.of(MatchesFrame.parseFrom(body));
        } catch (InvalidProtocolBufferException e) {
            log.error("parseMatches failed, b64={}", base64Safe(raw), e);
            return Optional.empty();
        }
    }

    private static byte[] firstBody(ServerFrame sf) {
        return sf.getBodyList().stream()
                .filter(bs -> bs != null && !bs.isEmpty())
                .findFirst()
                .map(ByteString::toByteArray)
                .orElse(null);
    }

    // ── utils ─────────────────────────────────────────────────────────────────

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return Instant.EPOCH;
        try { return Instant.ofEpochSecond(Long.parseLong(s)); }
        catch (NumberFormatException e) {
            try { return Instant.parse(s); } catch (Exception e2) { return Instant.EPOCH; }
        }
    }

    private static String base64Safe(byte[] data) {
        if (data == null) return "<null>";
        int max = Math.min(data.length, 512);
        byte[] slice = data.length == max ? data : Arrays.copyOf(data, max);
        return Base64.getEncoder().encodeToString(slice) + (data.length > max ? "...(+" + (data.length - max) + ")" : "");
    }

    private static long ms(long start) {
        return System.currentTimeMillis() - start;
    }
}
