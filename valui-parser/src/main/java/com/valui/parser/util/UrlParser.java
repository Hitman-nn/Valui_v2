package com.valui.parser.util;

import com.valui.common.domain.BookmakerType;
import com.valui.parser.bookmaker.betboom.BetBoomSportsMap;
import com.valui.parser.bookmaker.betcity.BetcitySportsMap;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

@Slf4j
public final class UrlParser {

    private UrlParser() {}

    public static BookmakerType parseBookmaker(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("1xbet.kz") || lower.contains("1xstavka.ru")) return BookmakerType.XBET;
        if (lower.contains("fon.bet") || lower.contains("fonbet.ru"))    return BookmakerType.FONBET;
        if (lower.contains("olimp.bet"))                                  return BookmakerType.OLIMP;
        if (lower.contains("betcity.ru"))                                 return BookmakerType.BETCITY;
        if (lower.contains("betboom.ru"))                                 return BookmakerType.BETBOOM;
        throw new IllegalArgumentException("Unknown bookmaker URL: " + url);
    }

    public static ParsedUrlIds extractIds(String url, BookmakerType bookmaker) {
        String[] parts = pathParts(url);
        return switch (bookmaker) {
            case XBET -> extractXbet(parts);
            case FONBET -> extractFonbet(parts);
            case OLIMP -> extractOlimp(parts);
            case BETCITY -> extractBetcity(parts);
            case BETBOOM -> extractBetboom(parts);
        };
    }

    // Old: /line/{sportSlug}/{champId}[-name][/{matchId}-teams]       (1xstavka.ru)
    // New: /ru/line/{sportSlug}/{champId}-name[/{matchId}-teams]     (1xbet.kz/ru)
    private static ParsedUrlIds extractXbet(String[] p) {
        int base        = p.length > 0 && "ru".equals(p[0]) ? 2 : 1; // skip "ru"+"line" or just "line"
        String sportId      = p.length > base     ? p[base]                   : null;
        String tournamentId = p.length > base + 1 ? p[base + 1].split("-")[0] : null;
        String matchId      = p.length > base + 2 ? p[base + 2].split("-")[0] : null;
        return new ParsedUrlIds(sportId, tournamentId, matchId);
    }

    // /sports/{sportId}/tournament/{champId}           — tournament
    // /sports/{sportId}/{champId}[/{matchId}]          — tournament or match (legacy/direct)
    private static ParsedUrlIds extractFonbet(String[] p) {
        String sportId = p.length > 1 ? p[1] : null;
        if (p.length > 2 && "tournament".equals(p[2])) {
            return new ParsedUrlIds(sportId, p.length > 3 ? p[3] : null, null);
        }
        String tournamentId = p.length > 2 ? p[2] : null;
        String matchId      = p.length > 3 ? p[3] : null;
        return new ParsedUrlIds(sportId, tournamentId, matchId);
    }

    // /line/{sportId}/{champId}[/{matchId}]
    private static ParsedUrlIds extractOlimp(String[] p) {
        // p[0]="line", p[1]=sportId, p[2]=champId, p[3]=matchId
        String sportId      = p.length > 1 ? p[1] : null;
        String tournamentId = p.length > 2 ? p[2] : null;
        String matchId      = p.length > 3 ? p[3] : null;
        return new ParsedUrlIds(sportId, tournamentId, matchId);
    }

    // /ru/line/{sportName}/{champId}[/{matchId}]
    private static ParsedUrlIds extractBetcity(String[] p) {
        // p[0]="ru", p[1]="line", p[2]=sportName, p[3]=champId, p[4]=matchId
        String sportAlias  = p.length > 2 ? p[2] : null;
        String sportId     = sportAlias != null
                ? BetcitySportsMap.getSportId(sportAlias).map(String::valueOf).orElse(sportAlias)
                : null;
        String tournamentId = p.length > 3 ? p[3] : null;
        String matchId      = p.length > 4 ? p[4] : null;
        return new ParsedUrlIds(sportId, tournamentId, matchId);
    }

    // /sport/{sportAlias}/{countryId}/{champId}[/{matchId}]?period=all
    private static ParsedUrlIds extractBetboom(String[] p) {
        // p[0]="sport", p[1]=sportAlias, p[2]=countryId, p[3]=champId, p[4]=matchId
        String sportAlias  = p.length > 1 ? p[1] : null;
        String sportId     = sportAlias != null
                ? BetBoomSportsMap.getSportId(sportAlias).map(String::valueOf).orElse(sportAlias)
                : null;
        String tournamentId = p.length > 3 ? p[3] : null;
        String matchId     = p.length > 4 ? p[4] : null;
        return new ParsedUrlIds(sportId, tournamentId, matchId);
    }

    private static String[] pathParts(String rawUrl) {
        try {
            String path = URI.create(rawUrl).getPath();
            return Arrays.stream(path.split("/"))
                    .filter(s -> !s.isBlank())
                    .toArray(String[]::new);
        } catch (Exception e) {
            // Previously silent — every downstream extractXxx() would just produce null ids
            // from the empty array with zero trace of why, making a malformed matchUrl
            // indistinguishable from "this URL legitimately has no tournament segment."
            log.warn("Malformed URL, cannot extract path segments: {}", rawUrl);
            return new String[0];
        }
    }
}
