package com.valui.betting.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the two-option payout display logic used when an express bet is won:
 *   option-2dp = stake × (effectiveOdds rounded to 2dp)
 *   option-4dp = stake × effectiveOdds (rounded to 4dp, then payout rounded to 2dp)
 *
 * When they differ, the bot shows both buttons (Fonbet vs BetBoom style).
 * When equal, only one button is shown.
 */
@DisplayName("Express payout options — 2dp vs 4dp odds rounding")
class BetPayoutOptionsTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static BigDecimal payout2dp(BigDecimal stake, BigDecimal effectiveOdds) {
        BigDecimal odds2dp = effectiveOdds.setScale(2, RoundingMode.HALF_UP);
        return stake.multiply(odds2dp).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal payout4dp(BigDecimal stake, BigDecimal effectiveOdds) {
        BigDecimal odds4dp = effectiveOdds.setScale(4, RoundingMode.HALF_UP);
        return stake.multiply(odds4dp).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal odds(String val) { return new BigDecimal(val); }
    private static BigDecimal stake(String val) { return new BigDecimal(val); }

    // ── BetBoom-style (4dp) ≠ Fonbet-style (2dp) ─────────────────────────────

    @Test
    @DisplayName("кэф 4.3275, ставка 1000 → 4330 vs 4327.50 (различаются)")
    void betboom_vs_fonbet_1000stake() {
        BigDecimal eff = odds("4.3275");
        BigDecimal s   = stake("1000");

        assertThat(payout2dp(s, eff)).isEqualByComparingTo("4330.00"); // кэф 4.33
        assertThat(payout4dp(s, eff)).isEqualByComparingTo("4327.50"); // кэф 4.3275
        assertThat(payout2dp(s, eff)).isNotEqualByComparingTo(payout4dp(s, eff));
    }

    @Test
    @DisplayName("кэф 4.1532, ставка 500 → 2075 vs 2076.60 (различаются)")
    void different_with_500stake() {
        BigDecimal eff = odds("4.1532");
        BigDecimal s   = stake("500");

        assertThat(payout2dp(s, eff)).isEqualByComparingTo("2075.00"); // кэф 4.15
        assertThat(payout4dp(s, eff)).isEqualByComparingTo("2076.60"); // кэф 4.1532
        assertThat(payout2dp(s, eff)).isNotEqualByComparingTo(payout4dp(s, eff));
    }

    // ── равные варианты — одна кнопка ─────────────────────────────────────────

    @Test
    @DisplayName("кэф 2.00 → оба варианта равны (одна кнопка)")
    void round_odds_equal_options() {
        BigDecimal eff = odds("2.00");
        BigDecimal s   = stake("1000");

        assertThat(payout2dp(s, eff)).isEqualByComparingTo("2000.00");
        assertThat(payout4dp(s, eff)).isEqualByComparingTo("2000.00");
        assertThat(payout2dp(s, eff)).isEqualByComparingTo(payout4dp(s, eff));
    }

    @Test
    @DisplayName("кэф 2.50 → оба варианта равны (одна кнопка)")
    void two_decimal_odds_equal_options() {
        BigDecimal eff = odds("2.50");
        BigDecimal s   = stake("200");

        assertThat(payout2dp(s, eff)).isEqualByComparingTo("500.00");
        assertThat(payout4dp(s, eff)).isEqualByComparingTo("500.00");
        assertThat(payout2dp(s, eff)).isEqualByComparingTo(payout4dp(s, eff));
    }

    // ── составной кэф из нескольких событий ──────────────────────────────────

    @Test
    @DisplayName("4 события: 1.85 × 2.10 × 1.70 × 1.60 → кэф 10.5672, ставка 1000")
    void compound_express_four_legs() {
        // 1.85 × 2.10 = 3.885; × 1.70 = 6.6045; × 1.60 = 10.5672
        BigDecimal eff = odds("1.85")
                .multiply(odds("2.10"))
                .multiply(odds("1.70"))
                .multiply(odds("1.60"))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal s = stake("1000");

        assertThat(eff).isEqualByComparingTo("10.5672");
        assertThat(payout2dp(s, eff)).isEqualByComparingTo("10570.00"); // кэф 10.57
        assertThat(payout4dp(s, eff)).isEqualByComparingTo("10567.20"); // кэф 10.5672
        assertThat(payout2dp(s, eff)).isNotEqualByComparingTo(payout4dp(s, eff));
    }

    @Test
    @DisplayName("2 события с возвратом: 2.00 × 1.00 (RETURNED slip) → effectiveOdds 2.00")
    void express_with_one_returned_slip() {
        // RETURNED slip contributes 1.0 to effectiveOdds
        BigDecimal eff = odds("2.00").multiply(odds("1.00")).setScale(4, RoundingMode.HALF_UP);
        BigDecimal s   = stake("500");

        assertThat(eff).isEqualByComparingTo("2.0000");
        assertThat(payout2dp(s, eff)).isEqualByComparingTo("1000.00");
        assertThat(payout4dp(s, eff)).isEqualByComparingTo("1000.00");
        assertThat(payout2dp(s, eff)).isEqualByComparingTo(payout4dp(s, eff));
    }
}
