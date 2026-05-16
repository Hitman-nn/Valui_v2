package com.valui.betting.service;

import com.valui.betting.dto.*;
import com.valui.betting.repository.*;
import com.valui.betting.service.impl.BettingServiceImpl;
import com.valui.common.domain.BetStatus;
import com.valui.common.domain.BetType;
import com.valui.common.domain.SlipResult;
import com.valui.common.entity.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BettingServiceImplTest {

    @Mock BetRepository              betRepo;
    @Mock BetAccountRepository       accountRepo;
    @Mock BetPersonRepository        personRepo;
    @Mock BetPersonBalanceRepository balanceRepo;

    @InjectMocks BettingServiceImpl service;

    // ── placeBet validation ───────────────────────────────────────────────────

    @Nested
    @DisplayName("placeBet validation")
    class PlaceBetValidation {

        @Test void single_with_two_slips_throws() {
            var req = new CreateBetRequest(BetType.SINGLE,
                    List.of(slip("A","1.5"), slip("B","2.0")),
                    bd("100"), null,
                    List.of(participant(null,"Me","100","1.0")));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("ровно одно событие");
        }

        @Test void express_with_one_slip_throws() {
            var req = new CreateBetRequest(BetType.EXPRESS,
                    List.of(slip("A","1.5")),
                    bd("100"), null,
                    List.of(participant(null,"Me","100","1.0")));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("минимум два события");
        }

        @Test void no_participants_throws() {
            var req = new CreateBetRequest(BetType.SINGLE, List.of(slip("A","1.5")),
                    bd("100"), null, List.of());
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("хотя бы одного участника");
        }

        @Test void mixed_null_stakes_throws() {
            var req = new CreateBetRequest(BetType.SINGLE, List.of(slip("M","2.0")),
                    bd("100"), null,
                    List.of(
                        new ParticipantRequest(null,"A", bd("50"), bd("0.5")),
                        new ParticipantRequest(null,"B", null, null)));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("либо для всех");
        }

        @Test void share_sum_beyond_tolerance_throws() {
            var req = new CreateBetRequest(BetType.SINGLE, List.of(slip("M","2.0")),
                    bd("100"), null,
                    List.of(
                        new ParticipantRequest(null,"A", bd("60"), bd("0.6")),
                        new ParticipantRequest(null,"B", bd("40"), bd("0.5"))));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("Сумма долей");
        }

        @Test void stake_sum_beyond_tolerance_throws() {
            var req = new CreateBetRequest(BetType.SINGLE, List.of(slip("M","2.0")),
                    bd("100"), null,
                    List.of(
                        new ParticipantRequest(null,"A", bd("50"), bd("0.5")),
                        new ParticipantRequest(null,"B", bd("40"), bd("0.5"))));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("Сумма ставок");
        }
    }

    @Nested
    @DisplayName("placeBet — null stakes auto-distributed equally")
    class PlaceBetNullDistribution {

        @Test void two_participants_split_equally() {
            var req = new CreateBetRequest(BetType.SINGLE, List.of(slip("M","2.0")),
                    bd("100"), null,
                    List.of(
                        new ParticipantRequest(null,"A", null, null),
                        new ParticipantRequest(null,"B", null, null)));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.placeBet(1L, 1L, req);

            ArgumentCaptor<BetEntity> cap = ArgumentCaptor.forClass(BetEntity.class);
            verify(betRepo).save(cap.capture());
            BetEntity saved = cap.getValue();
            assertThat(saved.getParticipants()).hasSize(2);
            BigDecimal totalStake = saved.getParticipants().stream()
                    .map(BetParticipantEntity::getStake).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalStake).isEqualByComparingTo("100");
            BigDecimal totalShare = saved.getParticipants().stream()
                    .map(BetParticipantEntity::getProfitShare).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalShare).isEqualByComparingTo("1.0");
        }
    }

    // ── resolveBet ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveBet")
    class ResolveBet {

        @Test void invalid_status_throws() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.resolveBet(UUID.randomUUID(), 1L, BetStatus.CANCELLED))
                    .withMessageContaining("Недопустимый статус");
        }

        @Test void already_resolved_throws() {
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.setStatus(BetStatus.WON);
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            assertThatIllegalStateException()
                    .isThrownBy(() -> service.resolveBet(bet.getId(), 1L, BetStatus.LOST))
                    .withMessageContaining("уже завершена");
        }

        @Test void won_credits_balance_as_net_profit() {
            BetPersonEntity person = person(1L);
            BetAccountEntity acct = account(1L);
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.setAccount(acct);
            addParticipant(bet, person, "100", "1.0");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(balanceRepo.findByAccountIdAndPersonId(any(), any())).thenReturn(Optional.empty());
            when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveBet(bet.getId(), 1L, BetStatus.WON);

            // WIN: net profit = 100*(2.0-1) = 100; profitShare=1.0 → delta=+100
            ArgumentCaptor<BetPersonBalanceEntity> cap = ArgumentCaptor.forClass(BetPersonBalanceEntity.class);
            verify(balanceRepo).save(cap.capture());
            assertThat(cap.getValue().getBalance()).isEqualByComparingTo("100.00");
        }

        @Test void lost_debits_balance_by_stake() {
            BetPersonEntity person = person(1L);
            BetAccountEntity acct = account(1L);
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.setAccount(acct);
            addParticipant(bet, person, "100", "1.0");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(balanceRepo.findByAccountIdAndPersonId(any(), any())).thenReturn(Optional.empty());
            when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveBet(bet.getId(), 1L, BetStatus.LOST);

            // LOST: delta = -stake * profitShare = -100
            ArgumentCaptor<BetPersonBalanceEntity> cap = ArgumentCaptor.forClass(BetPersonBalanceEntity.class);
            verify(balanceRepo).save(cap.capture());
            assertThat(cap.getValue().getBalance()).isEqualByComparingTo("-100.00");
        }

        @Test void returned_no_balance_change() {
            BetPersonEntity person = person(1L);
            BetAccountEntity acct = account(1L);
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.setAccount(acct);
            addParticipant(bet, person, "100", "1.0");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveBet(bet.getId(), 1L, BetStatus.RETURNED);

            verify(balanceRepo, never()).save(any());
        }

        @Test void won_splits_profit_by_share() {
            BetPersonEntity p1 = person(1L);
            BetPersonEntity p2 = person(2L);
            BetAccountEntity acct = account(1L);
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.setAccount(acct);
            addParticipant(bet, p1, "50", "0.5");
            addParticipant(bet, p2, "50", "0.5");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(balanceRepo.findByAccountIdAndPersonId(any(), any())).thenReturn(Optional.empty());
            when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveBet(bet.getId(), 1L, BetStatus.WON);

            // net profit=100; each share=0.5 → each gets +50
            ArgumentCaptor<BetPersonBalanceEntity> cap = ArgumentCaptor.forClass(BetPersonBalanceEntity.class);
            verify(balanceRepo, times(2)).save(cap.capture());
            assertThat(cap.getAllValues()).allMatch(b -> b.getBalance().compareTo(bd("50.00")) == 0);
        }

        @Test void all_slips_marked_with_result() {
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.getSlips().add(betSlip(bet, 0, "2.00"));
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveBet(bet.getId(), 1L, BetStatus.WON);

            assertThat(bet.getSlips()).allMatch(s -> s.getResult() == SlipResult.WON);
        }
    }

    // ── cancelBet ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelBet")
    class CancelBet {

        @Test void cancel_sets_status_and_no_balance_change() {
            BetEntity bet = openBet(1L, "2.00", "100");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.cancelBet(bet.getId(), 1L);

            verify(betRepo).save(argThat(b -> b.getStatus() == BetStatus.CANCELLED));
            verify(balanceRepo, never()).save(any());
        }

        @Test void already_closed_bet_throws() {
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.setStatus(BetStatus.WON);
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            assertThatIllegalStateException()
                    .isThrownBy(() -> service.cancelBet(bet.getId(), 1L))
                    .withMessageContaining("открытую ставку");
        }
    }

    // ── resolveSlip (express) ─────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveSlip — express auto-resolve")
    class ResolveSlipExpress {

        @Test void one_lost_slip_resolves_express_as_lost() {
            BetPersonEntity person = person(1L);
            BetAccountEntity acct  = account(1L);
            BetEntity bet = openBet(1L, "3.00", "100");
            bet.setType(BetType.EXPRESS);
            bet.setAccount(acct);
            bet.getSlips().add(betSlip(bet, 0, "2.00"));
            bet.getSlips().add(betSlip(bet, 1, "1.50"));
            addParticipant(bet, person, "100", "1.0");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(balanceRepo.findByAccountIdAndPersonId(any(), any())).thenReturn(Optional.empty());
            when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveSlip(bet.getId(), 0, 1L, SlipResult.LOST);

            ArgumentCaptor<BetEntity> cap = ArgumentCaptor.forClass(BetEntity.class);
            verify(betRepo).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(BetStatus.LOST);
            // The marked slip is LOST; the unresolved slip gets VOID (not LOST)
            assertThat(cap.getValue().getSlips()).anySatisfy(s -> {
                assertThat(s.getSortOrder()).isEqualTo(0);
                assertThat(s.getResult()).isEqualTo(SlipResult.LOST);
            });
            assertThat(cap.getValue().getSlips()).anySatisfy(s -> {
                assertThat(s.getSortOrder()).isEqualTo(1);
                assertThat(s.getResult()).isEqualTo(SlipResult.VOID);
            });
            // LOST: balance -= stake * share = -100
            ArgumentCaptor<BetPersonBalanceEntity> balCap = ArgumentCaptor.forClass(BetPersonBalanceEntity.class);
            verify(balanceRepo).save(balCap.capture());
            assertThat(balCap.getValue().getBalance()).isEqualByComparingTo("-100.00");
        }

        @Test void all_won_resolves_as_won_with_effective_odds() {
            BetPersonEntity person = person(1L);
            BetAccountEntity acct  = account(1L);
            BetEntity bet = openBet(1L, "3.00", "100");
            bet.setType(BetType.EXPRESS);
            bet.setAccount(acct);
            BetSlipEntity s0 = betSlip(bet, 0, "2.00");
            BetSlipEntity s1 = betSlip(bet, 1, "1.50");
            s0.setResult(SlipResult.WON);
            bet.getSlips().add(s0);
            bet.getSlips().add(s1);
            addParticipant(bet, person, "100", "1.0");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(balanceRepo.findByAccountIdAndPersonId(any(), any())).thenReturn(Optional.empty());
            when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveSlip(bet.getId(), 1, 1L, SlipResult.WON);

            // effectiveOdds = 2.00 * 1.50 = 3.00; payout=300; net profit = 300-100 = 200
            ArgumentCaptor<BetEntity> betCap = ArgumentCaptor.forClass(BetEntity.class);
            verify(betRepo).save(betCap.capture());
            assertThat(betCap.getValue().getStatus()).isEqualTo(BetStatus.WON);
            assertThat(betCap.getValue().getActualPayout()).isEqualByComparingTo("300.00");

            ArgumentCaptor<BetPersonBalanceEntity> balCap = ArgumentCaptor.forClass(BetPersonBalanceEntity.class);
            verify(balanceRepo).save(balCap.capture());
            assertThat(balCap.getValue().getBalance()).isEqualByComparingTo("200.00");
        }

        @Test void returned_slip_excluded_from_odds() {
            BetPersonEntity person = person(1L);
            BetAccountEntity acct  = account(1L);
            BetEntity bet = openBet(1L, "4.00", "100");
            bet.setType(BetType.EXPRESS);
            bet.setAccount(acct);
            BetSlipEntity s0 = betSlip(bet, 0, "2.00");
            s0.setResult(SlipResult.WON);
            BetSlipEntity s1 = betSlip(bet, 1, "2.00");
            bet.getSlips().add(s0);
            bet.getSlips().add(s1);
            addParticipant(bet, person, "100", "1.0");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(balanceRepo.findByAccountIdAndPersonId(any(), any())).thenReturn(Optional.empty());
            when(balanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveSlip(bet.getId(), 1, 1L, SlipResult.RETURNED);

            // effectiveOdds = 2.00 (returned gets 1.0); payout=200; net profit=100
            ArgumentCaptor<BetEntity> betCap = ArgumentCaptor.forClass(BetEntity.class);
            verify(betRepo).save(betCap.capture());
            assertThat(betCap.getValue().getStatus()).isEqualTo(BetStatus.WON);
            assertThat(betCap.getValue().getActualPayout()).isEqualByComparingTo("200.00");
        }

        @Test void all_returned_resolves_as_returned() {
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.setType(BetType.EXPRESS);
            BetSlipEntity s0 = betSlip(bet, 0, "2.00");
            s0.setResult(SlipResult.RETURNED);
            bet.getSlips().add(s0);
            bet.getSlips().add(betSlip(bet, 1, "1.00"));
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveSlip(bet.getId(), 1, 1L, SlipResult.RETURNED);

            ArgumentCaptor<BetEntity> cap = ArgumentCaptor.forClass(BetEntity.class);
            verify(betRepo).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(BetStatus.RETURNED);
            verify(balanceRepo, never()).save(any());
        }

        @Test void partial_resolution_does_not_auto_resolve() {
            BetEntity bet = openBet(1L, "3.00", "100");
            bet.setType(BetType.EXPRESS);
            bet.getSlips().add(betSlip(bet, 0, "2.00"));
            bet.getSlips().add(betSlip(bet, 1, "1.50"));
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveSlip(bet.getId(), 0, 1L, SlipResult.WON);

            ArgumentCaptor<BetEntity> cap = ArgumentCaptor.forClass(BetEntity.class);
            verify(betRepo).save(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo(BetStatus.OPEN);
        }

        @Test
        @DisplayName("void slip in lost bet can be resolved retroactively — no P&L change")
        void void_slip_can_be_resolved_retroactively() {
            BetEntity bet = openBet(1L, "3.00", "100");
            bet.setType(BetType.EXPRESS);
            bet.setStatus(BetStatus.LOST);
            bet.setActualPayout(BigDecimal.ZERO);
            BetSlipEntity s0 = betSlip(bet, 0, "2.00");
            s0.setResult(SlipResult.LOST);
            BetSlipEntity s1 = betSlip(bet, 1, "1.50");
            s1.setResult(SlipResult.VOID);
            bet.getSlips().add(s0);
            bet.getSlips().add(s1);
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BetDto result = service.resolveSlip(bet.getId(), 1, 1L, SlipResult.WON);

            ArgumentCaptor<BetEntity> cap = ArgumentCaptor.forClass(BetEntity.class);
            verify(betRepo).save(cap.capture());
            // Bet stays LOST, no balance change
            assertThat(cap.getValue().getStatus()).isEqualTo(BetStatus.LOST);
            assertThat(cap.getValue().getSlips()).anySatisfy(s -> {
                assertThat(s.getSortOrder()).isEqualTo(1);
                assertThat(s.getResult()).isEqualTo(SlipResult.WON);
            });
            verify(balanceRepo, never()).save(any());
        }

        @Test void single_bet_throws() {
            BetEntity bet = openBet(1L, "2.00", "100");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            assertThatIllegalStateException()
                    .isThrownBy(() -> service.resolveSlip(bet.getId(), 0, 1L, SlipResult.WON))
                    .withMessageContaining("только для экспресса");
        }

        @Test void already_resolved_slip_throws() {
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.setType(BetType.EXPRESS);
            BetSlipEntity s = betSlip(bet, 0, "2.00");
            s.setResult(SlipResult.WON);
            bet.getSlips().add(s);
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            assertThatIllegalStateException()
                    .isThrownBy(() -> service.resolveSlip(bet.getId(), 0, 1L, SlipResult.LOST))
                    .withMessageContaining("уже выставлен");
        }
    }

    // ── access control ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBet — wrong chat throws SecurityException")
    void get_bet_wrong_chat_throws() {
        BetEntity bet = openBet(1L, "2.00", "100");
        when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
        // bet.chatId = 1L, request chatId = 999L
        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.getBet(bet.getId(), 999L))
                .withMessageContaining("недоступна");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private BetSlipRequest slip(String title, String odds) {
        return new BetSlipRequest(title, null, null, bd(odds));
    }

    private ParticipantRequest participant(UUID personId, String name, String stake, String share) {
        return new ParticipantRequest(personId, name, bd(stake), bd(share));
    }

    private BetPersonEntity person(long chatId) {
        return BetPersonEntity.builder()
                .id(UUID.randomUUID())
                .chatId(chatId)
                .displayName("Person " + chatId)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private BetAccountEntity account(long chatId) {
        return BetAccountEntity.builder()
                .id(UUID.randomUUID())
                .chatId(chatId)
                .name("Account " + chatId)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private BetEntity openBet(long chatId, String odds, String stake) {
        return BetEntity.builder()
                .id(UUID.randomUUID())
                .telegramId(chatId)
                .chatId(chatId)
                .type(BetType.SINGLE)
                .status(BetStatus.OPEN)
                .totalOdds(bd(odds))
                .totalStake(bd(stake))
                .potentialPayout(bd(stake).multiply(bd(odds)))
                .slips(new ArrayList<>())
                .participants(new ArrayList<>())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private void addParticipant(BetEntity bet, BetPersonEntity person, String stake, String share) {
        bet.getParticipants().add(BetParticipantEntity.builder()
                .bet(bet)
                .person(person)
                .displayName(person.getDisplayName())
                .stake(bd(stake))
                .profitShare(bd(share))
                .build());
    }

    private BetSlipEntity betSlip(BetEntity bet, int sortOrder, String odds) {
        return BetSlipEntity.builder()
                .bet(bet)
                .matchTitle("Match " + sortOrder)
                .odds(bd(odds))
                .result(SlipResult.OPEN)
                .sortOrder(sortOrder)
                .build();
    }

    // ── getPersonStats ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getPersonStats P/L and ROI")
    class GetPersonStats {

        @Test
        @DisplayName("WON: pl = profitShare × (payout − stake), not stake × odds × profitShare")
        void won_pl_uses_profit_share_times_actual_payout() {
            long chatId = 1L;
            BetPersonEntity p = person(chatId);
            // profitShare=0.3, totalStake=1000, totalOdds=2.0, actualPayout=2000
            // Correct pl for participant: 0.3 × (2000 − 1000) = +300
            // Old wrong formula:          300 × 2.0 × 0.3   = +180
            when(personRepo.findById(p.getId())).thenReturn(Optional.of(p));
            when(betRepo.countByPersonIdAndChatIdAndStatus(eq(p.getId()), eq(chatId), any()))
                    .thenReturn(0L);
            when(betRepo.sumStakeByPersonId(p.getId(), chatId)).thenReturn(bd("300")); // p.stake = 1000×0.3
            when(betRepo.sumPayoutByPersonId(p.getId(), chatId)).thenReturn(bd("600")); // 0.3 × 2000

            BetPersonStatsDto stats = service.getPersonStats(p.getId(), chatId);

            assertThat(stats.profitLoss()).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("RETURNED bets cancel out: pl = 0 when only returned")
        void returned_bets_cancel_out_in_pl() {
            long chatId = 1L;
            BetPersonEntity p = person(chatId);
            // profitShare=0.5, totalStake=1000, actualPayout=1000 (returned)
            // payout = 0.5 × 1000 = 500; staked = 0.5 × 1000 = 500 → pl = 0
            when(personRepo.findById(p.getId())).thenReturn(Optional.of(p));
            when(betRepo.countByPersonIdAndChatIdAndStatus(any(), anyLong(), any())).thenReturn(0L);
            when(betRepo.sumStakeByPersonId(p.getId(), chatId)).thenReturn(bd("500"));
            when(betRepo.sumPayoutByPersonId(p.getId(), chatId)).thenReturn(bd("500"));

            BetPersonStatsDto stats = service.getPersonStats(p.getId(), chatId);

            assertThat(stats.profitLoss()).isEqualByComparingTo("0");
            assertThat(stats.roi()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("LOST: pl = −stake")
        void lost_pl_equals_negative_stake() {
            long chatId = 1L;
            BetPersonEntity p = person(chatId);
            // profitShare=1.0, totalStake=500 → staked=500, payout=0
            when(personRepo.findById(p.getId())).thenReturn(Optional.of(p));
            when(betRepo.countByPersonIdAndChatIdAndStatus(any(), anyLong(), any())).thenReturn(0L);
            when(betRepo.sumStakeByPersonId(p.getId(), chatId)).thenReturn(bd("500"));
            when(betRepo.sumPayoutByPersonId(p.getId(), chatId)).thenReturn(bd("0"));

            BetPersonStatsDto stats = service.getPersonStats(p.getId(), chatId);

            assertThat(stats.profitLoss()).isEqualByComparingTo("-500");
            assertThat(stats.roi()).isLessThan(0);
        }

        @Test
        @DisplayName("ROI = 0 when staked = 0")
        void roi_zero_when_no_bets() {
            long chatId = 1L;
            BetPersonEntity p = person(chatId);
            when(personRepo.findById(p.getId())).thenReturn(Optional.of(p));
            when(betRepo.countByPersonIdAndChatIdAndStatus(any(), anyLong(), any())).thenReturn(0L);
            when(betRepo.sumStakeByPersonId(p.getId(), chatId)).thenReturn(bd("0"));
            when(betRepo.sumPayoutByPersonId(p.getId(), chatId)).thenReturn(bd("0"));

            BetPersonStatsDto stats = service.getPersonStats(p.getId(), chatId);

            assertThat(stats.roi()).isEqualTo(0.0);
        }
    }

    private static BigDecimal bd(String val) { return new BigDecimal(val); }
}
