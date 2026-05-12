package com.valui.parser.cache;

import com.valui.common.domain.BookmakerType;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.SportDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CachedBookmakerParserTest {

    @Mock ParserCacheService cache;
    @Mock BookmakerParser xbetParser;

    CachedBookmakerParser cachedParser;

    @BeforeEach
    void setUp() {
        when(xbetParser.getBookmaker()).thenReturn(BookmakerType.XBET);
        cachedParser = new CachedBookmakerParser(List.of(xbetParser), cache);
    }

    // ── fetchSports ───────────────────────────────────────────────────────────

    @Test
    void fetchSports_cacheHit_doesNotCallDelegate() {
        List<SportDto> data = List.of(new SportDto("1", "Football", "football"));
        when(cache.getSports(BookmakerType.XBET)).thenReturn(Optional.of(data));

        ParseResult<List<SportDto>> result = cachedParser.fetchSports(BookmakerType.XBET);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(data);
        verify(xbetParser, never()).fetchSports();
    }

    @Test
    void fetchSports_cacheMiss_callsDelegateAndStores() {
        List<SportDto> fresh = List.of(new SportDto("1", "Football", "football"));
        when(cache.getSports(BookmakerType.XBET))
                .thenReturn(Optional.empty())   // initial check
                .thenReturn(Optional.empty());  // double-check after lock
        when(cache.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(xbetParser.fetchSports()).thenReturn(ParseResult.ok(fresh, 50));

        ParseResult<List<SportDto>> result = cachedParser.fetchSports(BookmakerType.XBET);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(fresh);
        verify(xbetParser).fetchSports();
        verify(cache).setSports(BookmakerType.XBET, fresh);
        verify(cache).releaseLock(anyString());
    }

    @Test
    void fetchSports_lockAcquired_doubleCheckHit_skipsDelegate() {
        List<SportDto> data = List.of(new SportDto("1", "Football", "football"));
        when(cache.getSports(BookmakerType.XBET))
                .thenReturn(Optional.empty())   // initial — miss
                .thenReturn(Optional.of(data)); // double-check — hit (another node populated it)
        when(cache.tryLock(anyString(), any(Duration.class))).thenReturn(true);

        ParseResult<List<SportDto>> result = cachedParser.fetchSports(BookmakerType.XBET);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(data);
        verify(xbetParser, never()).fetchSports();
        verify(cache).releaseLock(anyString());
    }

    @Test
    void fetchSports_lockHeld_waitsThenReturnsCachedData() {
        List<SportDto> data = List.of(new SportDto("1", "Football", "football"));
        when(cache.getSports(BookmakerType.XBET))
                .thenReturn(Optional.empty())   // initial — miss
                .thenReturn(Optional.of(data)); // retry after sleep — lock holder populated it
        when(cache.tryLock(anyString(), any(Duration.class))).thenReturn(false);

        ParseResult<List<SportDto>> result = cachedParser.fetchSports(BookmakerType.XBET);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(data);
        verify(xbetParser, never()).fetchSports();
    }

    // ── fetchTournaments ──────────────────────────────────────────────────────

    @Test
    void fetchTournaments_cacheHit_doesNotCallDelegate() {
        List<TournamentDto> data = List.of(new TournamentDto("100", "EPL", "1", null, "/epl"));
        when(cache.getTournaments(BookmakerType.XBET, "1")).thenReturn(Optional.of(data));

        ParseResult<List<TournamentDto>> result = cachedParser.fetchTournaments(BookmakerType.XBET, "1");

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(data);
        verify(xbetParser, never()).fetchTournaments(anyString());
    }

    @Test
    void fetchTournaments_cacheMiss_callsDelegateAndStores() {
        List<TournamentDto> fresh = List.of(new TournamentDto("100", "EPL", "1", null, "/epl"));
        when(cache.getTournaments(BookmakerType.XBET, "1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(cache.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(xbetParser.fetchTournaments("1")).thenReturn(ParseResult.ok(fresh, 80));

        ParseResult<List<TournamentDto>> result = cachedParser.fetchTournaments(BookmakerType.XBET, "1");

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(fresh);
        verify(cache).setTournaments(BookmakerType.XBET, "1", fresh);
    }

    // ── fetchMatches ──────────────────────────────────────────────────────────

    @Test
    void fetchMatches_cacheHit_doesNotCallDelegate() {
        List<ParsedMatchDto> data = List.of(
                new ParsedMatchDto("500", "A - B", "100", "/match/500", Instant.EPOCH, false, null));
        when(cache.getMatches(BookmakerType.XBET, "100")).thenReturn(Optional.of(data));

        ParseResult<List<ParsedMatchDto>> result = cachedParser.fetchMatches(BookmakerType.XBET, "100");

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(data);
        verify(xbetParser, never()).fetchMatches(anyString());
    }

    @Test
    void fetchMatches_cacheMiss_callsDelegateAndStores() {
        List<ParsedMatchDto> fresh = List.of(
                new ParsedMatchDto("500", "A - B", "100", "/match/500", Instant.EPOCH, false, null));
        when(cache.getMatches(BookmakerType.XBET, "100"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(cache.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(xbetParser.fetchMatches("100")).thenReturn(ParseResult.ok(fresh, 60));

        ParseResult<List<ParsedMatchDto>> result = cachedParser.fetchMatches(BookmakerType.XBET, "100");

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(fresh);
        verify(cache).setMatches(BookmakerType.XBET, "100", fresh);
    }
}
