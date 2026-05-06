package com.valui.betting.service;

import com.valui.betting.dto.*;
import com.valui.betting.repository.BankAccountRepository;
import com.valui.betting.repository.BetRepository;
import com.valui.betting.service.impl.BettingServiceImpl;
import com.valui.common.domain.BetStatus;
import com.valui.common.domain.BetType;
import com.valui.common.domain.SlipResult;
import com.valui.common.entity.*;
import org.junit.jupiter.api.BeforeEach;
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

    @Mock BetRepository betRepo;
    @Mock BankAccountRepository bankRepo;

    @InjectMocks BettingServiceImpl service;

    // ── placeBet ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("placeBet validation")
    class PlaceBetValidation {

        @Test
        void single_with_two_slips_throws() {
            CreateBetRequest req = new CreateBetRequest(
                    BetType.SINGLE,
                    List.of(slip("Match A", "1.5"), slip("Match B", "2.0")),
                    new BigDecimal("100"),
                    List.of(participant(1L, "100", "1.0"))
            );
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("ровно одно событие");
        }

        @Test
        void express_with_one_slip_throws() {
            CreateBetRequest req = new CreateBetRequest(
                    BetType.EXPRESS,
                    List.of(slip("Match A", "1.5")),
                    new BigDecimal("100"),
                    List.of(participant(1L, "100", "1.0"))
            );
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("минимум два события");
        }

        @Test
        void no_slips_throws() {
            CreateBetRequest req = new CreateBetRequest(
                    BetType.SINGLE, List.of(), new BigDecimal("100"),
                    List.of(participant(1L, "100", "1.0"))
            );
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("хотя бы одно событие");
        }

        @Test
        void no_participants_throws() {
            CreateBetRequest req = new CreateBetRequest(
                    BetType.SINGLE, List.of(slip("Match A", "1.5")), new BigDecimal("100"), List.of()
            );
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("хотя бы одного участника");
        }

        @Test
        void mixed_null_stakes_throws() {
            List<ParticipantRequest> parts = List.of(
                    new ParticipantRequest(1L, "A", new BigDecimal("50"), new BigDecimal("0.5"), null),
                    new ParticipantRequest(2L, "B", null, null, null)
            );
            CreateBetRequest req = new CreateBetRequest(
                    BetType.SINGLE, List.of(slip("M", "2.0")), new BigDecimal("100"), parts
            );
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("либо для всех");
        }

        @Test
        void share_sum_beyond_tolerance_throws() {
            List<ParticipantRequest> parts = List.of(
                    new ParticipantRequest(1L, "A", new BigDecimal("60"), new BigDecimal("0.6"), null),
                    new ParticipantRequest(2L, "B", new BigDecimal("40"), new BigDecimal("0.5"), null)
            );
            CreateBetRequest req = new CreateBetRequest(
                    BetType.SINGLE, List.of(slip("M", "2.0")), new BigDecimal("100"), parts
            );
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("Сумма долей");
        }

        @Test
        void stake_sum_beyond_tolerance_throws() {
            List<ParticipantRequest> parts = List.of(
                    new ParticipantRequest(1L, "A", new BigDecimal("50"), new BigDecimal("0.5"), null),
                    new ParticipantRequest(2L, "B", new BigDecimal("40"), new BigDecimal("0.5"), null)
            );
            CreateBetRequest req = new CreateBetRequest(
                    BetType.SINGLE, List.of(slip("M", "2.0")), new BigDecimal("100"), parts
            );
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("Сумма ставок");
        }
    }

    @Nested
    @DisplayName("placeBet — null stakes auto-distributed equally")
    class PlaceBetNullDistribution {

        @Test
        void two_participants_split_equally() {
            List<ParticipantRequest> parts = List.of(
                    new ParticipantRequest(1L, "A", null, null, null),
                    new ParticipantRequest(2L, "B", null, null, null)
            );
            CreateBetRequest req = new CreateBetRequest(
                    BetType.SINGLE, List.of(slip("Match", "2.0")), new BigDecimal("100"), parts
            );
            when(bankRepo.findByOwnerTelegramId(anyLong())).thenReturn(Optional.empty());
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.placeBet(1L, 1L, req);

            ArgumentCaptor<BetEntity> cap = ArgumentCaptor.forClass(BetEntity.class);
            verify(betRepo).save(cap.capture());
            BetEntity saved = cap.getValue();
            assertThat(saved.getParticipants()).hasSize(2);
            BigDecimal totalStake = saved.getParticipants().stream()
                    .map(BetParticipantEntity::getStake)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalStake).isEqualByComparingTo("100");
            BigDecimal totalShare = saved.getParticipants().stream()
                    .map(BetParticipantEntity::getProfitShare)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(totalShare).isEqualByComparingTo("1.0");
        }
    }

    // ── resolveBet ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveBet")
    class ResolveBet {

        @Test
        void invalid_status_throws() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.resolveBet(UUID.randomUUID(), 1L, BetStatus.CANCELLED))
                    .withMessageContaining("Недопустимый статус");
        }

        @Test
        void already_resolved_throws() {
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.setStatus(BetStatus.WON);
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));

            assertThatIllegalStateException()
                    .isThrownBy(() -> service.resolveBet(bet.getId(), 1L, BetStatus.LOST))
                    .withMessageContaining("уже завершена");
        }

        @Test
        void won_credits_bank_proportionally() {
            BankAccountEntity acct = bankAccount(1L, "1000");
            BetEntity bet = openBetWithParticipant(1L, "2.00", "100", acct, "1.0");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveBet(bet.getId(), 1L, BetStatus.WON);

            // payout = 100 * 2.00 = 200; share = 1.0 → credit 200
            verify(bankRepo).save(argThat(a -> a.getBalance().compareTo(new BigDecimal("1200.00")) == 0));
        }

        @Test
        void lost_does_not_credit_bank() {
            BankAccountEntity acct = bankAccount(1L, "1000");
            BetEntity bet = openBetWithParticipant(1L, "2.00", "100", acct, "1.0");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveBet(bet.getId(), 1L, BetStatus.LOST);

            // payout = 0 → no balance change → save not called on bankRepo
            verify(bankRepo).save(argThat(a -> a.getBalance().compareTo(new BigDecimal("1000")) == 0));
        }

        @Test
        void returned_refunds_full_stake() {
            BankAccountEntity acct = bankAccount(1L, "900");
            BetEntity bet = openBetWithParticipant(1L, "2.00", "100", acct, "1.0");
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveBet(bet.getId(), 1L, BetStatus.RETURNED);

            verify(bankRepo).save(argThat(a -> a.getBalance().compareTo(new BigDecimal("1000.00")) == 0));
        }

        @Test
        void won_splits_payout_by_share() {
            BankAccountEntity acctA = bankAccount(1L, "500");
            BankAccountEntity acctB = bankAccount(2L, "500");
            BetEntity bet = openBet(1L, "2.00", "100");
            addParticipant(bet, 1L, "50", "0.5", acctA);
            addParticipant(bet, 2L, "50", "0.5", acctB);
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.resolveBet(bet.getId(), 1L, BetStatus.WON);

            // total payout = 200; each gets 100
            ArgumentCaptor<BankAccountEntity> cap = ArgumentCaptor.forClass(BankAccountEntity.class);
            verify(bankRepo, times(2)).save(cap.capture());
            List<BigDecimal> balances = cap.getAllValues().stream()
                    .map(BankAccountEntity::getBalance).toList();
            assertThat(balances).allMatch(b -> b.compareTo(new BigDecimal("600.00")) == 0);
        }

        @Test
        void all_slips_marked_with_result() {
            BetEntity bet = openBet(1L, "2.00", "100");
            BetSlipEntity slip = BetSlipEntity.builder().bet(bet).matchTitle("M").odds(new BigDecimal("2.00"))
                    .result(SlipResult.OPEN).build();
            bet.getSlips().add(slip);
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

        @Test
        void refunds_stakes_to_all_participants() {
            BankAccountEntity acctA = bankAccount(1L, "500");
            BankAccountEntity acctB = bankAccount(2L, "300");
            BetEntity bet = openBet(1L, "2.00", "100");
            addParticipant(bet, 1L, "60", "0.6", acctA);
            addParticipant(bet, 2L, "40", "0.4", acctB);
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));
            when(betRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.cancelBet(bet.getId(), 1L);

            ArgumentCaptor<BankAccountEntity> cap = ArgumentCaptor.forClass(BankAccountEntity.class);
            verify(bankRepo, times(2)).save(cap.capture());
            Map<Long, BigDecimal> balanceByOwner = new HashMap<>();
            cap.getAllValues().forEach(a -> balanceByOwner.put(a.getOwnerTelegramId(), a.getBalance()));
            assertThat(balanceByOwner.get(1L)).isEqualByComparingTo("560.00");
            assertThat(balanceByOwner.get(2L)).isEqualByComparingTo("340.00");
        }

        @Test
        void already_closed_bet_throws() {
            BetEntity bet = openBet(1L, "2.00", "100");
            bet.setStatus(BetStatus.WON);
            when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));

            assertThatIllegalStateException()
                    .isThrownBy(() -> service.cancelBet(bet.getId(), 1L))
                    .withMessageContaining("открытую ставку");
        }
    }

    // ── resolveAccount security ───────────────────────────────────────────────

    @Nested
    @DisplayName("resolveAccount — cross-user access")
    class ResolveAccountSecurity {

        @Test
        void cross_user_account_throws_on_placeBet() {
            UUID foreignAccountId = UUID.randomUUID();
            BankAccountEntity foreignAcct = BankAccountEntity.builder()
                    .id(foreignAccountId)
                    .ownerTelegramId(99L)   // belongs to user 99, not user 1
                    .balance(BigDecimal.ZERO)
                    .updatedAt(OffsetDateTime.now())
                    .build();
            when(bankRepo.findById(foreignAccountId)).thenReturn(Optional.of(foreignAcct));

            CreateBetRequest req = new CreateBetRequest(
                    BetType.SINGLE,
                    List.of(slip("M", "2.0")),
                    new BigDecimal("100"),
                    List.of(new ParticipantRequest(1L, "A", new BigDecimal("100"), BigDecimal.ONE, foreignAccountId))
            );

            assertThatExceptionOfType(SecurityException.class)
                    .isThrownBy(() -> service.placeBet(1L, 1L, req))
                    .withMessageContaining("не принадлежит");
        }
    }

    // ── access control ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBet — non-owner, non-participant throws SecurityException")
    void get_bet_unauthorized_throws() {
        BetEntity bet = openBet(1L, "2.00", "100");
        when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));

        assertThatExceptionOfType(SecurityException.class)
                .isThrownBy(() -> service.getBet(bet.getId(), 999L))
                .withMessageContaining("недоступна");
    }

    @Test
    @DisplayName("getBet — participant (not owner) is allowed")
    void get_bet_as_participant_allowed() {
        BetEntity bet = openBet(1L, "2.00", "100");
        addParticipant(bet, 42L, "100", "1.0", null);
        when(betRepo.findWithDetailById(bet.getId())).thenReturn(Optional.of(bet));

        assertThatNoException().isThrownBy(() -> service.getBet(bet.getId(), 42L));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private BetSlipRequest slip(String title, String odds) {
        return new BetSlipRequest(title, null, null, new BigDecimal(odds));
    }

    private ParticipantRequest participant(long tid, String stake, String share) {
        return new ParticipantRequest(tid, "User" + tid, new BigDecimal(stake), new BigDecimal(share), null);
    }

    private BankAccountEntity bankAccount(long ownerId, String balance) {
        return BankAccountEntity.builder()
                .id(UUID.randomUUID())
                .ownerTelegramId(ownerId)
                .balance(new BigDecimal(balance))
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private BetEntity openBet(long telegramId, String odds, String stake) {
        BetEntity bet = BetEntity.builder()
                .id(UUID.randomUUID())
                .telegramId(telegramId)
                .chatId(telegramId)
                .type(BetType.SINGLE)
                .status(BetStatus.OPEN)
                .totalOdds(new BigDecimal(odds))
                .totalStake(new BigDecimal(stake))
                .potentialPayout(new BigDecimal(stake).multiply(new BigDecimal(odds)))
                .slips(new ArrayList<>())
                .participants(new ArrayList<>())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        return bet;
    }

    private BetEntity openBetWithParticipant(long telegramId, String odds, String stake,
                                              BankAccountEntity account, String share) {
        BetEntity bet = openBet(telegramId, odds, stake);
        addParticipant(bet, telegramId, stake, share, account);
        return bet;
    }

    private void addParticipant(BetEntity bet, long tid, String stake, String share, BankAccountEntity acct) {
        bet.getParticipants().add(BetParticipantEntity.builder()
                .bet(bet)
                .telegramId(tid)
                .stake(new BigDecimal(stake))
                .profitShare(new BigDecimal(share))
                .bankAccount(acct)
                .build());
    }
}
