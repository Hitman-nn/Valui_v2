package com.valui.parser.bookmaker.betboom;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import proto.betboom.Envelope;
import proto.betboom.MatchesBody;
import proto.betboom.MatchesFrame;
import proto.betboom.ServerFrame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Reads the raw protobuf binary from _migration/rpl-matches-raw.bin
 * and prints ALL market fields for the first few matches.
 *
 * Run:
 *   mvn test -pl valui-parser -Dtest=BetBoomMarketAnalysisTest -DfailIfNoTests=false
 */
@Disabled("Manual exploration — run on demand")
class BetBoomMarketAnalysisTest {

    @Test
    void analyzeMarkets() throws Exception {
        Path binPath = Path.of("target/betboom-dump", "rpl-matches-raw.bin");
        if (!Files.exists(binPath)) {
            System.out.println("File not found: " + binPath.toAbsolutePath());
            return;
        }

        byte[] raw = Files.readAllBytes(binPath);
        System.out.printf("Binary size: %,d bytes%n%n", raw.length);

        Envelope env = Envelope.parseFrom(raw);
        System.out.println("Envelope kind: " + env.getKindCase());

        // Envelope.field9 → ServerFrame → firstBody (field5) → MatchesFrame
        ServerFrame sf = ServerFrame.parseFrom(env.getResponseTournamentMatches().toByteArray());
        System.out.println("ServerFrame bodies: " + sf.getBodyCount());
        byte[] body = sf.getBodyList().stream()
                .filter(bs -> bs != null && !bs.isEmpty())
                .findFirst().map(com.google.protobuf.ByteString::toByteArray).orElse(null);
        if (body == null) { System.out.println("No body in ServerFrame."); return; }

        MatchesFrame frame = MatchesFrame.parseFrom(body);
        System.out.println("hasSport:   " + frame.hasSport());
        System.out.println("hasSection: " + frame.hasSection());

        if (!frame.hasSection()) {
            System.out.println("No section in frame.");
            return;
        }

        List<MatchesBody.Match> allMatches = frame.getSection().getMatchesList();
        System.out.printf("Total matches: %d%n%n", allMatches.size());

        // Analyse first 3 matches
        int limit = Math.min(3, allMatches.size());
        for (int mi = 0; mi < limit; mi++) {
            MatchesBody.Match match = allMatches.get(mi);
            if (!match.hasHeader()) continue;

            MatchesBody.MatchHeader h = match.getHeader();
            System.out.printf("═══ Match %d: id=%d  %s - %s (%s)%n",
                    mi + 1,
                    h.getId(),
                    h.hasTeams() ? h.getTeams().getHome().getName() : "?",
                    h.hasTeams() ? h.getTeams().getAway().getName() : "?",
                    h.getStartsAt());

            System.out.printf("  statusId=%d  live=%d  tournamentId=%d%n",
                    h.getStatusId(), h.getLive(), h.getTournamentId());

            List<MatchesBody.Market> markets = match.getMarketsList();
            System.out.printf("  Markets count: %d%n", markets.size());

            // Collect all unique group_name / market_name / view_key combinations
            System.out.println("\n  ─── All unique groupName/marketName/viewKey ───");
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (MatchesBody.Market m : markets) {
                String key = String.format("groupName=%-30s marketName=%-30s viewKey=%s",
                        m.getGroupName(), m.getMarketName(), m.getViewKey());
                seen.add(key);
            }
            seen.forEach(k -> System.out.println("    " + k));

            // Print first 20 markets with ALL fields
            System.out.println("\n  ─── First 20 markets (all fields) ───");
            int mLimit = Math.min(20, markets.size());
            for (int i = 0; i < mLimit; i++) {
                MatchesBody.Market m = markets.get(i);
                System.out.printf("  [%2d] key=%-20s groupName=%-25s marketName=%-25s%n",
                        i, m.getKey(), m.getGroupName(), m.getMarketName());
                System.out.printf("       titleFull=%-25s titleShort=%-15s label=%-10s%n",
                        m.getTitleFull(), m.getTitleShort(), m.getLabel());
                System.out.printf("       viewKey=%-20s viewId=%-6d groupId=%-6d period=%-4d%n",
                        m.getViewKey(), m.getViewId(), m.getGroupId(), m.getPeriod());
                System.out.printf("       outcomeId=%-6d outcomeNo=%-4d%n",
                        m.getOutcomeId(), m.getOutcomeNo());
                System.out.printf("       oddsRaw1=%d  oddsRaw2=%d%n",
                        m.getOddsRaw1(), m.getOddsRaw2());

                // Try decoding odds_raw1 as IEEE 754 double
                double asDouble1 = Double.longBitsToDouble(m.getOddsRaw1());
                double asDouble2 = Double.longBitsToDouble(m.getOddsRaw2());
                System.out.printf("       as-double: odds1=%.4f  odds2=%.4f%n",
                        asDouble1, asDouble2);

                // Try as fixed-point /1000
                System.out.printf("       as-/1000:  odds1=%.3f  odds2=%.3f%n",
                        m.getOddsRaw1() / 1000.0, m.getOddsRaw2() / 1000.0);

                System.out.printf("       timeScope=%-10s sort=%-4d cacheKey=%s%n",
                        m.getTimeScope(), m.getSort(), m.getCacheKey());
                System.out.println();
            }
            System.out.println();
        }

        // Summary: all distinct groupNames across ALL matches
        System.out.println("═══ All distinct groupName values across all matches ═══");
        LinkedHashSet<String> allGroups = new LinkedHashSet<>();
        for (MatchesBody.Match match : allMatches) {
            match.getMarketsList().forEach(m -> allGroups.add(m.getGroupName()));
        }
        allGroups.forEach(g -> System.out.println("  " + g));

        System.out.println("\n═══ All distinct marketName values ═══");
        LinkedHashSet<String> allMarkets = new LinkedHashSet<>();
        for (MatchesBody.Match match : allMatches) {
            match.getMarketsList().forEach(m -> allMarkets.add(m.getMarketName()));
        }
        allMarkets.forEach(g -> System.out.println("  " + g));

        System.out.println("\n═══ All distinct viewKey values ═══");
        LinkedHashSet<String> allViewKeys = new LinkedHashSet<>();
        for (MatchesBody.Match match : allMatches) {
            match.getMarketsList().forEach(m -> allViewKeys.add(m.getViewKey()));
        }
        allViewKeys.forEach(g -> System.out.println("  " + g));
    }
}
