package com.valui.parser.bookmaker.betboom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.ParseResult;
import com.valui.parser.bookmaker.betboom.ws.WsClientBorrowingPool;
import com.valui.parser.bookmaker.betboom.ws.WsPoolProperties;
import com.valui.parser.bookmaker.betboom.ws.WsRequestService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import proto.betboom.Current;
import proto.betboom.Envelope;
import proto.betboom.MatchesBody;
import proto.betboom.MatchesFrame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Manual exploration — connects to BetBoom WebSocket, fetches a live Протобуф frame
 * for Россия. Премьер-лига and saves it to disk.
 *
 * Run:
 *   mvn test -pl valui-parser -Dtest=BetBoomSnapshotDumpTest -DfailIfNoTests=false
 *
 * Output files in target/betboom-dump/:
 *   sports.json              — parsed sport list (SportDto[])
 *   tournaments-football.json — parsed tournaments for football
 *   rpl-matches.json         — parsed ParsedMatchDto[] for RPL
 *   rpl-matches-proto.json   — raw MatchesFrame protobuf fields as JSON text
 */
@Disabled("Manual exploration — run on demand")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BetBoomSnapshotDumpTest {

    private static final String WS_URL = "wss://ru-ws.sporthub.bet:444/api/tree_ws/v1";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private WsClientBorrowingPool pool;
    private BetBoomParser parser;

    @BeforeAll
    void startPool() throws Exception {
        WsPoolProperties props = new WsPoolProperties();
        props.setUrl(WS_URL);
        props.setMinSize(1);
        props.setMaxSize(2);
        props.setConnectTimeout(Duration.ofSeconds(8));
        props.setReadyTimeout(Duration.ofSeconds(15));

        pool = new WsClientBorrowingPool(props);
        pool.start();
        System.out.println("WS pool started, waiting for connections …");
        // Give connections time to handshake
        Thread.sleep(3_000);
        System.out.printf("Pool: connected=%d available=%d%n", pool.connected(), pool.available());

        WsRequestService wsService = new WsRequestService(pool, 5);
        parser = new BetBoomParser(wsService);
    }

    @AfterAll
    void stopPool() {
        if (pool != null) pool.stop();
    }

    @Test
    void dumpRussiaPremierLeagueSnapshot() throws Exception {
        Path outDir = Path.of("target", "betboom-dump");
        Files.createDirectories(outDir);

        // ── 1. Fetch sports ───────────────────────────────────────────────────
        System.out.println("Fetching sports …");
        ParseResult<List<SportDto>> sportsResult = parser.fetchSports();
        if (!sportsResult.success()) {
            System.out.println("fetchSports failed: " + sportsResult.errorMessage());
            return;
        }
        List<SportDto> sports = sportsResult.data();
        MAPPER.writeValue(outDir.resolve("sports.json").toFile(), sports);
        System.out.printf("  saved sports.json (%d sports)%n", sports.size());

        System.out.println("\n=== Sports ===");
        sports.forEach(s -> System.out.printf("  id=%-6s alias=%-20s %s%n", s.id(), s.alias(), s.name()));

        // Find football
        String footballId = sports.stream()
                .filter(s -> s.name().contains("Футбол") || s.name().equalsIgnoreCase("Football")
                          || s.alias().equalsIgnoreCase("football"))
                .map(SportDto::id)
                .findFirst()
                .orElse(null);

        if (footballId == null) {
            System.out.println("Football not found — check sports list above.");
            return;
        }
        System.out.println("\nFootball id=" + footballId);

        // ── 2. Fetch tournaments ──────────────────────────────────────────────
        System.out.println("\nFetching tournaments for football …");
        ParseResult<List<TournamentDto>> tournamentsResult = parser.fetchTournaments(footballId);
        if (!tournamentsResult.success()) {
            System.out.println("fetchTournaments failed: " + tournamentsResult.errorMessage());
            return;
        }
        List<TournamentDto> tournaments = tournamentsResult.data();
        MAPPER.writeValue(outDir.resolve("tournaments-football.json").toFile(), tournaments);
        System.out.printf("  saved tournaments-football.json (%d tournaments)%n", tournaments.size());

        System.out.println("\n=== Tournaments containing 'Россия. Премьер-лига' ===");
        tournaments.stream()
                .filter(t -> t.title().contains("Россия. Премьер-лига"))
                .forEach(t -> System.out.printf("  id=%-8s countryId=%-6s %s%n",
                        t.id(), t.countryId(), t.title()));

        TournamentDto rpl = tournaments.stream()
                .filter(t -> t.title().contains("Россия. Премьер-лига"))
                .findFirst()
                .orElse(null);

        if (rpl == null) {
            System.out.println("\nRPL not found. All tournaments:");
            tournaments.forEach(t -> System.out.printf("  id=%-8s %s%n", t.id(), t.title()));
            return;
        }
        System.out.printf("\nFound RPL: id=%s  countryId=%s  title=%s%n",
                rpl.id(), rpl.countryId(), rpl.title());

        // ── 3. Fetch matches ──────────────────────────────────────────────────
        System.out.println("\nFetching matches for RPL id=" + rpl.id() + " …");
        ParseResult<List<ParsedMatchDto>> matchesResult = parser.fetchMatches(rpl.id());
        if (!matchesResult.success()) {
            System.out.println("fetchMatches failed: " + matchesResult.errorMessage());
            return;
        }
        List<ParsedMatchDto> matches = matchesResult.data();
        // Instant → String to avoid jackson-datatype-jsr310 dependency in this module
        ArrayNode matchesNode = MAPPER.createArrayNode();
        for (ParsedMatchDto m : matches) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("id",          m.id());
            n.put("title",       m.title());
            n.put("tournamentId",m.tournamentId());
            n.put("url",         m.url());
            n.put("startsAt",    m.startsAt() != null ? m.startsAt().toString() : null);
            n.put("isLive",      m.isLive());
            n.put("extraData",   m.extraData());
            matchesNode.add(n);
        }
        MAPPER.writeValue(outDir.resolve("rpl-matches.json").toFile(), matchesNode);
        System.out.printf("  saved rpl-matches.json (%d matches)%n", matches.size());

        matches.forEach(m -> System.out.printf("  id=%-8s live=%-5s %s%n",
                m.id(), m.isLive(), m.title()));

        if (!matches.isEmpty()) {
            System.out.println("\n=== First match (ParsedMatchDto all fields) ===");
            System.out.println(MAPPER.writeValueAsString(matchesNode.get(0)));
        }

        // ── 4. Fetch raw protobuf frame and inspect all fields ────────────────
        System.out.println("\nFetching raw protobuf frame for RPL …");
        int tid = Integer.parseInt(rpl.id());
        byte[] req = BetBoomSubscribeBuilder.tournamentMatchesBytes(Current.TypeLine.LINE, tid);

        // Use WsRequestService directly for raw access
        WsRequestService ws = new WsRequestService(pool, 5);
        byte[] rawResp = ws.sendAndAwaitFiltered(req, 5_000,
                env -> env.hasResponseTournamentMatches());

        if (rawResp == null) {
            System.out.println("No raw protobuf response received.");
            return;
        }

        // Save raw bytes
        Files.write(outDir.resolve("rpl-matches-raw.bin"), rawResp);
        System.out.printf("  saved rpl-matches-raw.bin (%d bytes)%n", rawResp.length);

        // Parse and print ALL protobuf fields
        Envelope env = Envelope.parseFrom(rawResp);
        System.out.println("\n=== Envelope.kindCase ===");
        System.out.println("  " + env.getKindCase());

        MatchesFrame frame = MatchesFrame.parseFrom(env.getResponseTournamentMatches());
        System.out.println("\n=== MatchesFrame fields ===");
        System.out.println("  hasSport:    " + frame.hasSport());
        System.out.println("  hasCountry:  " + frame.hasCountry());
        System.out.println("  hasSection:  " + frame.hasSection());
        if (frame.hasSport() && frame.getSport().hasSport()) {
            System.out.println("  sport.id:    " + frame.getSport().getSport().getId());
            System.out.println("  sport.alias: " + frame.getSport().getSport().getAlias());
        }

        if (frame.hasSection()) {
            int matchCount = frame.getSection().getMatchesCount();
            System.out.printf("\n  section.matches count: %d%n", matchCount);

            if (matchCount > 0) {
                MatchesBody.Match first = frame.getSection().getMatches(0);
                System.out.println("\n=== First MatchesBody.Match fields ===");
                System.out.println("  hasHeader:       " + first.hasHeader());
                if (first.hasHeader()) {
                    System.out.println("  header.id:       " + first.getHeader().getId());
                    System.out.println("  header.live:     " + first.getHeader().getLive());
                    System.out.println("  header.startsAt: " + first.getHeader().getStartsAt());
                    System.out.println("  header.hasTeams: " + first.getHeader().hasTeams());
                    if (first.getHeader().hasTeams()) {
                        System.out.println("  teams.home: " + first.getHeader().getTeams().getHome().getName());
                        System.out.println("  teams.away: " + first.getHeader().getTeams().getAway().getName());
                    }
                    System.out.println("  header.statusId:     " + first.getHeader().getStatusId());
                    System.out.println("  header.tournamentId: " + first.getHeader().getTournamentId());
                    System.out.println("  header.srMatchId:    " + first.getHeader().getSrMatchId());
                    System.out.println("  header.sortOrder:    " + first.getHeader().getSortOrder());
                }
                System.out.println("  marketsCount:        " + first.getMarketsCount());
                // Print first market fields if available
                if (first.getMarketsCount() > 0) {
                    MatchesBody.Market m0 = first.getMarkets(0);
                    System.out.println("\n=== First market fields ===");
                    System.out.println("  key:         " + m0.getKey());
                    System.out.println("  titleFull:   " + m0.getTitleFull());
                    System.out.println("  titleShort:  " + m0.getTitleShort());
                    System.out.println("  marketName:  " + m0.getMarketName());
                    System.out.println("  groupId:     " + m0.getGroupId());
                    System.out.println("  groupName:   " + m0.getGroupName());
                    System.out.println("  viewId:      " + m0.getViewId());
                    System.out.println("  viewKey:     " + m0.getViewKey());
                    System.out.println("  outcomeId:   " + m0.getOutcomeId());
                    System.out.println("  outcomeNo:   " + m0.getOutcomeNo());
                    System.out.println("  oddsRaw1:    " + m0.getOddsRaw1());
                    System.out.println("  oddsRaw2:    " + m0.getOddsRaw2());
                    System.out.println("  period:      " + m0.getPeriod());
                    System.out.println("  timeScope:   " + m0.getTimeScope());
                    System.out.println("  label:       " + m0.getLabel());
                    System.out.println("  cacheKey:    " + m0.getCacheKey());
                }

                // Save full protobuf text representation of first match
                StringBuilder sb = new StringBuilder();
                sb.append("First match proto:\n").append(first);
                Path protoTxt = outDir.resolve("rpl-first-match-proto.txt");
                Files.writeString(protoTxt, sb.toString());
                System.out.println("\n  saved rpl-first-match-proto.txt (all proto fields)");
            }
        }

        System.out.println("\nAll files saved to: " + outDir.toAbsolutePath());
    }
}
