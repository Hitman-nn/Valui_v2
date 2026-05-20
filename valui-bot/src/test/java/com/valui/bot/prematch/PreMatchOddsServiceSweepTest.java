package com.valui.bot.prematch;

import com.valui.common.domain.SlipResult;
import com.valui.common.entity.BetSlipEntity;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.factory.ParserFactory;
import com.valui.betting.repository.BetSlipRepository;
import com.valui.common.domain.BookmakerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PreMatchOddsService — sweep & checkReschedule")
class PreMatchOddsServiceSweepTest {

    @Mock BetSlipRepository slipRepo;
    @Mock ParserFactory     parserFactory;
    @Mock TransactionTemplate tx;

    @InjectMocks PreMatchOddsService service;

    static final UUID   SLIP_ID     = UUID.randomUUID();
    static final String UNKNOWN_URL = "https://unknown.example.com/match/1";
    // Fonbet URL: tournamentId="123", matchId="456"
    static final String FONBET_URL  = "https://fonbet.ru/sports/1/123/456";

    BetSlipEntity slip;

    @BeforeEach
    void setUp() {
        slip = BetSlipEntity.builder()
                .id(SLIP_ID)
                .matchUrl(FONBET_URL)
                .startsAt(Instant.now().plus(2, ChronoUnit.HOURS))
                .odds(new BigDecimal("2.00"))
                .result(SlipResult.OPEN)
                .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<UUID, ScheduledFuture<?>> pending() throws Exception {
        Field f = PreMatchOddsService.class.getDeclaredField("pending");
        f.setAccessible(true);
        return (ConcurrentHashMap<UUID, ScheduledFuture<?>>) f.get(service);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<UUID, Integer> sweepMisses() throws Exception {
        Field f = PreMatchOddsService.class.getDeclaredField("sweepMisses");
        f.setAccessible(true);
        return (ConcurrentHashMap<UUID, Integer>) f.get(service);
    }

    private void putPending(UUID id) throws Exception {
        pending().put(id, mock(ScheduledFuture.class));
    }

    private void stubParser(Instant startsAt) throws Exception {
        BookmakerParser parser = mock(BookmakerParser.class);
        given(parserFactory.getParser(BookmakerType.FONBET)).willReturn(parser);
        ParsedMatchDto match = new ParsedMatchDto("456", "Test Match", "123", FONBET_URL,
                startsAt, false, null);
        given(parser.fetchMatches("123")).willReturn(ParseResult.ok(List.of(match), 0L));
    }

    @SuppressWarnings("unchecked")
    private void stubTx() {
        given(tx.execute(any())).willAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        given(slipRepo.findById(SLIP_ID)).willReturn(Optional.of(slip));
        given(slipRepo.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    // ── skip if not pending ───────────────────────────────────────────────────

    @Test
    @DisplayName("не в pending → noop, репозиторий не вызывается")
    void not_in_pending_noop() {
        service.checkReschedule(SLIP_ID);
        verifyNoInteractions(slipRepo);
    }

    // ── early exits ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ранний выход — cancel")
    class EarlyExit {

        @Test
        @DisplayName("slip не найден → cancel")
        void slip_not_found_cancels() throws Exception {
            putPending(SLIP_ID);
            given(slipRepo.findById(SLIP_ID)).willReturn(Optional.empty());

            service.checkReschedule(SLIP_ID);

            assertThat(pending()).doesNotContainKey(SLIP_ID);
        }

        @Test
        @DisplayName("снапшот уже взят → cancel")
        void snapshot_taken_cancels() throws Exception {
            slip.setSnapshotTakenAt(java.time.OffsetDateTime.now());
            putPending(SLIP_ID);
            given(slipRepo.findById(SLIP_ID)).willReturn(Optional.of(slip));

            service.checkReschedule(SLIP_ID);

            assertThat(pending()).doesNotContainKey(SLIP_ID);
        }
    }

    // ── miss counter ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("счётчик пропусков (match not found)")
    class MissCounter {

        @BeforeEach
        void setUp() throws Exception {
            slip.setMatchUrl(UNKNOWN_URL); // неизвестный URL → fetchMatch вернёт null
            given(slipRepo.findById(SLIP_ID)).willReturn(Optional.of(slip));
        }

        @Test
        @DisplayName("первый miss → misses=1, pending остаётся")
        void first_miss_increments_counter() throws Exception {
            putPending(SLIP_ID);

            service.checkReschedule(SLIP_ID);

            assertThat(sweepMisses().get(SLIP_ID)).isEqualTo(1);
            assertThat(pending()).containsKey(SLIP_ID);
        }

        @Test
        @DisplayName("4 miss → pending остаётся, счётчик не сбрасывается")
        void four_misses_still_pending() throws Exception {
            for (int i = 1; i <= 4; i++) {
                putPending(SLIP_ID);
                service.checkReschedule(SLIP_ID);
                assertThat(sweepMisses().get(SLIP_ID)).isEqualTo(i);
                assertThat(pending()).containsKey(SLIP_ID);
            }
        }

        @Test
        @DisplayName("5-й miss → cancel, счётчик очищен")
        void fifth_miss_cancels_and_clears_counter() throws Exception {
            sweepMisses().put(SLIP_ID, 4); // симулируем 4 предыдущих
            putPending(SLIP_ID);

            service.checkReschedule(SLIP_ID);

            assertThat(pending()).doesNotContainKey(SLIP_ID);
            assertThat(sweepMisses().getOrDefault(SLIP_ID, 0)).isEqualTo(0);
        }

        @Test
        @DisplayName("match найден после пропусков → счётчик сбрасывается")
        void match_found_resets_miss_counter() throws Exception {
            sweepMisses().put(SLIP_ID, 3);
            slip.setMatchUrl(FONBET_URL); // вернём нормальный URL
            Instant startsAt = slip.getStartsAt();
            putPending(SLIP_ID);
            stubParser(startsAt); // матч найден, сдвига нет

            service.checkReschedule(SLIP_ID);

            assertThat(sweepMisses().getOrDefault(SLIP_ID, 0)).isEqualTo(0);
            assertThat(pending()).containsKey(SLIP_ID);
        }
    }

    // ── reschedule decisions ──────────────────────────────────────────────────

    @Nested
    @DisplayName("решения при обнаруженном переносе")
    class RescheduleBranches {

        @Test
        @DisplayName("матч уже начался → cancel")
        void match_already_started_cancels() throws Exception {
            Instant newStartsAt = Instant.now().minus(30, ChronoUnit.MINUTES);
            putPending(SLIP_ID);
            stubParser(newStartsAt);
            stubTx();

            service.checkReschedule(SLIP_ID);

            assertThat(pending()).doesNotContainKey(SLIP_ID);
        }

        @Test
        @DisplayName("внутри окна (start через 5 мин) → старый job отменён, сдвиг зафиксирован в DB")
        void inside_window_takes_snapshot_immediately() throws Exception {
            Instant newStartsAt = Instant.now().plus(5, ChronoUnit.MINUTES); // <15 мин → внутри окна
            ScheduledFuture<?> oldFuture = mock(ScheduledFuture.class);
            pending().put(SLIP_ID, oldFuture);
            stubParser(newStartsAt);
            stubTx();

            service.checkReschedule(SLIP_ID);

            // Старый job был отменён и startsAt обновлён в DB (tx.execute вызван)
            verify(oldFuture).cancel(false);
            verify(tx).execute(any());
        }

        @Test
        @DisplayName("start через 2 часа → старый job отменён, сдвиг зафиксирован в DB")
        void future_trigger_reschedules_job() throws Exception {
            slip.setStartsAt(Instant.now().plus(1, ChronoUnit.HOURS));
            Instant newStartsAt = Instant.now().plus(2, ChronoUnit.HOURS); // diff=60мин
            ScheduledFuture<?> oldFuture = mock(ScheduledFuture.class);
            pending().put(SLIP_ID, oldFuture);
            stubParser(newStartsAt);
            stubTx();

            service.checkReschedule(SLIP_ID);

            verify(oldFuture).cancel(false);
            verify(tx).execute(any());
            assertThat(pending()).containsKey(SLIP_ID); // новый job запланирован
        }

        @Test
        @DisplayName("незначительный сдвиг (≤10 мин) → job не меняется, TX не вызывается")
        void small_shift_no_reschedule() throws Exception {
            Instant base    = slip.getStartsAt(); // now+2h
            Instant shifted = base.plus(5, ChronoUnit.MINUTES); // 5 мин ≤ 10
            ScheduledFuture<?> oldFuture = mock(ScheduledFuture.class);
            pending().put(SLIP_ID, oldFuture);
            given(slipRepo.findById(SLIP_ID)).willReturn(Optional.of(slip));
            stubParser(shifted);

            service.checkReschedule(SLIP_ID);

            verify(tx, never()).execute(any()); // DB не трогалась
            verify(oldFuture, never()).cancel(anyBoolean()); // job не отменялся
        }
    }
}
