package com.valui.parser.cache;

import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.MatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Cache-first facade for all parsers with Redis-backed caching and stampede protection.
 * Use this instead of calling parsers directly when caching is desired.
 */
@Slf4j
@Component
public class CachedBookmakerParser {

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final ParserCacheService cache;
    private final Map<BookmakerType, BookmakerParser> delegates;

    public CachedBookmakerParser(List<BookmakerParser> parsers, ParserCacheService cache) {
        this.cache = cache;
        this.delegates = parsers.stream()
                .collect(Collectors.toMap(BookmakerParser::getBookmaker, Function.identity()));
    }

    public ParseResult<List<SportDto>> fetchSports(BookmakerType bk) {
        Optional<List<SportDto>> hit = cache.getSports(bk);
        if (hit.isPresent()) return ParseResult.ok(hit.get(), 0);

        String lock = "sports:" + bk.name().toLowerCase();
        if (cache.tryLock(lock, LOCK_TTL)) {
            try {
                Optional<List<SportDto>> recheck = cache.getSports(bk);
                if (recheck.isPresent()) return ParseResult.ok(recheck.get(), 0);
                ParseResult<List<SportDto>> result = delegate(bk).fetchSports();
                if (result.success()) cache.setSports(bk, result.data());
                return result;
            } finally {
                cache.releaseLock(lock);
            }
        }
        return waitAndRetry(() -> cache.getSports(bk)
                .map(d -> ParseResult.ok(d, 0))
                .orElseGet(() -> delegate(bk).fetchSports()));
    }

    public ParseResult<List<TournamentDto>> fetchTournaments(BookmakerType bk, String sportId) {
        Optional<List<TournamentDto>> hit = cache.getTournaments(bk, sportId);
        if (hit.isPresent()) return ParseResult.ok(hit.get(), 0);

        String lock = "tournaments:" + bk.name().toLowerCase() + ":" + sportId;
        if (cache.tryLock(lock, LOCK_TTL)) {
            try {
                Optional<List<TournamentDto>> recheck = cache.getTournaments(bk, sportId);
                if (recheck.isPresent()) return ParseResult.ok(recheck.get(), 0);
                ParseResult<List<TournamentDto>> result = delegate(bk).fetchTournaments(sportId);
                if (result.success()) cache.setTournaments(bk, sportId, result.data());
                return result;
            } finally {
                cache.releaseLock(lock);
            }
        }
        return waitAndRetry(() -> cache.getTournaments(bk, sportId)
                .map(d -> ParseResult.ok(d, 0))
                .orElseGet(() -> delegate(bk).fetchTournaments(sportId)));
    }

    public ParseResult<List<MatchDto>> fetchMatches(BookmakerType bk, String tournamentId) {
        Optional<List<MatchDto>> hit = cache.getMatches(bk, tournamentId);
        if (hit.isPresent()) return ParseResult.ok(hit.get(), 0);

        String lock = "matches:" + bk.name().toLowerCase() + ":" + tournamentId;
        if (cache.tryLock(lock, LOCK_TTL)) {
            try {
                Optional<List<MatchDto>> recheck = cache.getMatches(bk, tournamentId);
                if (recheck.isPresent()) return ParseResult.ok(recheck.get(), 0);
                ParseResult<List<MatchDto>> result = delegate(bk).fetchMatches(tournamentId);
                if (result.success()) cache.setMatches(bk, tournamentId, result.data());
                return result;
            } finally {
                cache.releaseLock(lock);
            }
        }
        return waitAndRetry(() -> cache.getMatches(bk, tournamentId)
                .map(d -> ParseResult.ok(d, 0))
                .orElseGet(() -> delegate(bk).fetchMatches(tournamentId)));
    }

    private BookmakerParser delegate(BookmakerType bk) {
        BookmakerParser p = delegates.get(bk);
        if (p == null) throw new IllegalArgumentException("No parser registered for: " + bk);
        return p;
    }

    private <T> T waitAndRetry(Supplier<T> supplier) {
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return supplier.get();
    }
}
