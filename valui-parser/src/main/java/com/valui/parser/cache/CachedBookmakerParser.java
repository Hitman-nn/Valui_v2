package com.valui.parser.cache;

import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
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
 *
 * Stampede protection: only the thread that acquires the lock fetches from the parser.
 * Other threads spin-wait (up to LOCK_TTL) checking the cache, then fall back to a
 * direct parser call only if the cache is still empty after all retries.
 */
@Slf4j
@Component
public class CachedBookmakerParser {

    private static final Duration LOCK_TTL    = Duration.ofSeconds(30);
    private static final int      RETRY_COUNT = 10;
    private static final long     RETRY_MS    = 500L;

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
        return waitAndRetry(
                () -> cache.getSports(bk).map(d -> ParseResult.ok(d, 0)).orElse(null),
                () -> delegate(bk).fetchSports());
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
        return waitAndRetry(
                () -> cache.getTournaments(bk, sportId).map(d -> ParseResult.ok(d, 0)).orElse(null),
                () -> delegate(bk).fetchTournaments(sportId));
    }

    public ParseResult<List<ParsedMatchDto>> fetchMatches(BookmakerType bk, String tournamentId) {
        Optional<List<ParsedMatchDto>> hit = cache.getMatches(bk, tournamentId);
        if (hit.isPresent()) return ParseResult.ok(hit.get(), 0);

        String lock = "matches:" + bk.name().toLowerCase() + ":" + tournamentId;
        if (cache.tryLock(lock, LOCK_TTL)) {
            try {
                Optional<List<ParsedMatchDto>> recheck = cache.getMatches(bk, tournamentId);
                if (recheck.isPresent()) return ParseResult.ok(recheck.get(), 0);
                ParseResult<List<ParsedMatchDto>> result = delegate(bk).fetchMatches(tournamentId);
                if (result.success()) cache.setMatches(bk, tournamentId, result.data());
                return result;
            } finally {
                cache.releaseLock(lock);
            }
        }
        return waitAndRetry(
                () -> cache.getMatches(bk, tournamentId).map(d -> ParseResult.ok(d, 0)).orElse(null),
                () -> delegate(bk).fetchMatches(tournamentId));
    }

    private BookmakerParser delegate(BookmakerType bk) {
        BookmakerParser p = delegates.get(bk);
        if (p == null) throw new IllegalArgumentException("No parser registered for: " + bk);
        return p;
    }

    /**
     * Waits for the lock-holder to populate the cache, returning early as soon as
     * {@code cacheCheck} yields a non-null result. If the cache is still empty after
     * all retries (lock-holder failed or was too slow), falls back to {@code parserFallback}.
     */
    private <T> T waitAndRetry(Supplier<T> cacheCheck, Supplier<T> parserFallback) {
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return parserFallback.get();
            }
            T val = cacheCheck.get();
            if (val != null) return val;
        }
        return parserFallback.get();
    }
}
