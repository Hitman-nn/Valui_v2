package com.valui.bot.handler.callback.betting;

import com.valui.betting.dto.BetDto;
import com.valui.betting.dto.BetParticipantDto;
import com.valui.betting.dto.BetSlipDto;
import com.valui.common.domain.BetStatus;
import com.valui.common.domain.BetType;
import com.valui.common.domain.SlipResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BettingMenuCallback#needsPayoutConfirmation(BetDto)}.
 *
 * Confirmation must be shown only when stake × odds(2dp) ≠ stake × odds(4dp).
 * When effectiveOdds is already representable in 2dp, skip the dialog.
 */
@DisplayName("BettingMenuCallback — needsPayoutConfirmation")
class BettingMenuCallbackPayoutTest {

    // ── кэф до 2 знаков → диалог не нужен ────────────────────────────────────

    @Test
    @DisplayName("кэф 2.00 × 1 событие → закрыть без диалога")
    void single_leg_round_odds_no_confirmation() {
        BetDto bet = wonExpress(bd("1000"), slip("2.00"));
        assertThat(BettingMenuCallback.needsPayoutConfirmation(bet)).isFalse();
    }

    @Test
    @DisplayName("кэф 1.85 × 2.00 = 3.70 (2dp) → закрыть без диалога")
    void two_legs_product_is_2dp() {
        BetDto bet = wonExpress(bd("500"), slip("1.85"), slip("2.00"));
        // 1.85 × 2.00 = 3.70 → 2dp ok
        assertThat(BettingMenuCallback.needsPayoutConfirmation(bet)).isFalse();
    }

    @Test
    @DisplayName("кэф 2.50 × 2.00 = 5.00 (ровный) → закрыть без диалога")
    void round_product_no_confirmation() {
        BetDto bet = wonExpress(bd("1000"), slip("2.50"), slip("2.00"));
        assertThat(BettingMenuCallback.needsPayoutConfirmation(bet)).isFalse();
    }

    @Test
    @DisplayName("RETURNED событие (odds=1.00) не влияет — остаётся 2dp → без диалога")
    void returned_slip_excluded_still_2dp() {
        BetDto bet = wonExpress(bd("1000"), slip("2.00"), returnedSlip("1.50"));
        // effectiveOdds = product of WON slips only = 2.00
        assertThat(BettingMenuCallback.needsPayoutConfirmation(bet)).isFalse();
    }

    // ── кэф имеет 3-4 знака → показать диалог ────────────────────────────────

    @Test
    @DisplayName("кэф 4.3275 → 2dp(4330) ≠ 4dp(4327.50) → показать диалог")
    void betboom_style_4dp_odds_needs_confirmation() {
        // Simulate effective odds with 4dp from multi-leg express
        // e.g. 1.95 × 2.065 × 1.073 ≈ 4.3275 — use direct single-slip for simplicity
        BetDto bet = wonExpress(bd("1000"), wonSlip("4.3275"));
        assertThat(BettingMenuCallback.needsPayoutConfirmation(bet)).isTrue();
    }

    @Test
    @DisplayName("кэф 1.85 × 1.95 = 3.6075 → диалог нужен")
    void two_legs_4dp_product_needs_confirmation() {
        // 1.85 × 1.95 = 3.6075 → 2dp=3.61 → pay2dp≠pay4dp
        BetDto bet = wonExpress(bd("1000"), slip("1.85"), slip("1.95"));
        assertThat(BettingMenuCallback.needsPayoutConfirmation(bet)).isTrue();
    }

    @Test
    @DisplayName("4 события: 1.85×2.10×1.70×1.60 = 10.5672 → диалог нужен")
    void four_legs_compound_needs_confirmation() {
        BetDto bet = wonExpress(bd("1000"),
                slip("1.85"), slip("2.10"), slip("1.70"), slip("1.60"));
        // effectiveOdds ≈ 10.5672 → 2dp=10570 ≠ 4dp=10567.20
        assertThat(BettingMenuCallback.needsPayoutConfirmation(bet)).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static BetDto wonExpress(BigDecimal stake, BetSlipDto... slips) {
        return new BetDto(
                UUID.randomUUID(), 1L, 1L,
                BetType.EXPRESS, BetStatus.WON,
                BigDecimal.ONE, stake, stake,
                null, null, OffsetDateTime.now(),
                null, null,
                List.of(slips),
                List.of());
    }

    /** WON slip with given odds. */
    private static BetSlipDto slip(String odds) {
        return wonSlip(odds);
    }

    private static BetSlipDto wonSlip(String odds) {
        return new BetSlipDto(UUID.randomUUID(), "Match", null, null,
                bd(odds), SlipResult.WON, OffsetDateTime.now(), 0);
    }

    /** RETURNED slip — excluded from effectiveOdds by needsPayoutConfirmation. */
    private static BetSlipDto returnedSlip(String odds) {
        return new BetSlipDto(UUID.randomUUID(), "Match", null, null,
                bd(odds), SlipResult.RETURNED, OffsetDateTime.now(), 1);
    }

    private static BigDecimal bd(String val) { return new BigDecimal(val); }
}
