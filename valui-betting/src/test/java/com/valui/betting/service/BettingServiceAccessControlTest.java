package com.valui.betting.service;

import com.valui.betting.repository.BetAccountRepository;
import com.valui.betting.repository.BetPersonBalanceRepository;
import com.valui.betting.repository.BetPersonRepository;
import com.valui.betting.repository.BetRepository;
import com.valui.betting.service.impl.BettingServiceImpl;
import com.valui.common.domain.BetStatus;
import com.valui.common.domain.BetType;
import com.valui.common.domain.SlipResult;
import com.valui.common.entity.BetEntity;
import com.valui.common.entity.BetParticipantEntity;
import com.valui.common.entity.BetPersonEntity;
import com.valui.common.entity.BetSlipEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Tests for BettingServiceImpl.requireAccessible() — verifying the Long-equality fix (M10).
 *
 * The bug: `bet.getTelegramId() == telegramId` compared a boxed Long with a primitive long.
 * JVM caches Long instances only in [-128, 127], so for real Telegram IDs (9-10 digits)
 * the reference comparison always returned false → owners got a SecurityException.
 *
 * The fix: Long.valueOf(telegramId).equals(bet.getTelegramId()) — value-based comparison.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BettingServiceImpl — access control (requireAccessible)")
class BettingServiceAccessControlTest {

    @Mock BetRepository              betRepo;
    @Mock BetAccountRepository       accountRepo;
    @Mock BetPersonRepository        personRepo;
    @Mock BetPersonBalanceRepository balanceRepo;

    @InjectMocks BettingServiceImpl service;

    // ── Owner access — large Telegram IDs ────────────────────────────────────

    @Nested
    @DisplayName("owner access with real-world Telegram IDs (> 127, outside JVM cache range)")
    class OwnerAccessLargeIds {

        @ParameterizedTest(name = "telegramId = {0}")
        @ValueSource(longs = {
                128L,               // just above JVM cache limit
                1_000_000L,         // 7 digits
                123_456_789L,       // 9 digits — typical Telegram user ID
                1_234_567_890L,     // 10 digits — typical group/channel ID
                7_777_777_777L      // large bot/channel ID
        })
        @DisplayName("owner with large telegramId can cancel their own bet")
        void largeId_owner_canCancelBet(long telegramId) {
            BetEntity bet = singleBet(telegramId);
            given(betRepo.findWithDetailById(bet.getId())).willReturn(Optional.of(bet));
            given(betRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> service.cancelBet(bet.getId(), telegramId))
                    .as("Owner with telegramId=%d should be able to cancel their own bet", telegramId)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("owner with 9-digit telegram ID can resolve a slip on their express bet")
        void nineDigitId_owner_canResolveSlip() {
            long realId = 987_654_321L;
            BetEntity bet = expressBetWithSlip(realId);
            given(betRepo.findWithDetailById(bet.getId())).willReturn(Optional.of(bet));
            given(betRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> service.resolveSlip(bet.getId(), 0, realId, SlipResult.WON))
                    .doesNotThrowAnyException();
        }
    }

    // ── Participant access — large Telegram IDs ───────────────────────────────

    @Nested
    @DisplayName("group chat access — anyone calling from the same chatId can manage the bet")
    class GroupChatAccess {

        @Test
        @DisplayName("any member of the group chat that created the bet can cancel it")
        void groupMember_canCancelBet() {
            long groupChatId = -1_001_234_567_890L;
            long ownerTgId   = 111_111_111L;

            // Bet was created in the group chat
            BetEntity bet = singleBet(ownerTgId);
            bet.setChatId(groupChatId);

            given(betRepo.findWithDetailById(bet.getId())).willReturn(Optional.of(bet));
            given(betRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

            // Any call originating from the same group chat is allowed
            assertThatCode(() -> service.cancelBet(bet.getId(), groupChatId))
                    .as("Call from the same group chatId should be allowed")
                    .doesNotThrowAnyException();
        }
    }

    // ── Non-owner / non-participant is rejected ───────────────────────────────

    @Nested
    @DisplayName("non-owner/non-participant is rejected regardless of ID size")
    class UnauthorizedAccess {

        @ParameterizedTest(name = "stranger telegramId = {0}")
        @ValueSource(longs = {1L, 100L, 128L, 123_456_789L})
        @DisplayName("stranger cannot cancel someone else's bet")
        void stranger_isRejected(long strangerId) {
            long ownerId = 555_555_555L;
            BetEntity bet = singleBet(ownerId);
            given(betRepo.findWithDetailById(bet.getId())).willReturn(Optional.of(bet));

            assertThatExceptionOfType(SecurityException.class)
                    .isThrownBy(() -> service.cancelBet(bet.getId(), strangerId))
                    .withMessageContaining("недоступна");
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("small IDs (≤ 127, within JVM cache) still work correctly after the fix")
        void smallId_withinJvmCache_works() {
            long ownerId = 42L;
            BetEntity bet = singleBet(ownerId);
            given(betRepo.findWithDetailById(bet.getId())).willReturn(Optional.of(bet));
            given(betRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> service.cancelBet(bet.getId(), ownerId))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("bet not found → NoSuchElementException (not SecurityException)")
        void betNotFound_throwsNoSuchElement() {
            UUID missing = UUID.randomUUID();
            given(betRepo.findWithDetailById(missing)).willReturn(Optional.empty());

            assertThatExceptionOfType(java.util.NoSuchElementException.class)
                    .isThrownBy(() -> service.cancelBet(missing, 123_456_789L));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static BetEntity singleBet(long telegramId) {
        return BetEntity.builder()
                .id(UUID.randomUUID())
                .telegramId(telegramId)
                .chatId(telegramId)
                .type(BetType.SINGLE)
                .status(BetStatus.OPEN)
                .totalOdds(new BigDecimal("2.00"))
                .totalStake(new BigDecimal("100"))
                .potentialPayout(new BigDecimal("200"))
                .slips(new ArrayList<>())
                .participants(new ArrayList<>())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private static BetEntity expressBetWithSlip(long telegramId) {
        BetEntity bet = singleBet(telegramId);
        bet.setType(BetType.EXPRESS);
        bet.getSlips().add(BetSlipEntity.builder()
                .bet(bet)
                .matchTitle("Match A")
                .odds(new BigDecimal("2.00"))
                .result(SlipResult.OPEN)
                .sortOrder(0)
                .build());
        return bet;
    }
}
