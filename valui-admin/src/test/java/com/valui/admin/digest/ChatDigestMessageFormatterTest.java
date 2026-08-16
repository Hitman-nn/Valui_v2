package com.valui.admin.digest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatDigestMessageFormatter — MarkdownV2 rendering")
class ChatDigestMessageFormatterTest {

    private final ChatDigestMessageFormatter formatter = new ChatDigestMessageFormatter();

    @Nested
    @DisplayName("Base fields — always present")
    class BaseFields {

        @Test
        @DisplayName("Renders controller/bookmaker/notification counts")
        void rendersBaseCounts() {
            String text = formatter.format(new ChatDigestStatsDto(-100L, 5, 2, 0, 0, 12, 0, 0));

            assertThat(text)
                    .contains("Активных контроллеров")
                    .contains("*5*")
                    .contains("Букмекеров в работе")
                    .contains("*2*")
                    .contains("Уведомлений за 7 дней")
                    .contains("12");
        }

        @Test
        @DisplayName("Reserved MarkdownV2 characters in static labels are escaped")
        void escapesReservedCharsInLabels() {
            // "На паузе (не хватает токенов)" contains literal parentheses — MarkdownV2-reserved.
            String text = formatter.format(new ChatDigestStatsDto(-100L, 1, 1, 0, 3, 0, 0, 0));

            assertThat(text).contains("\\(не хватает токенов\\)");
            assertThat(text).doesNotContain("(не хватает токенов)");
        }

        @Test
        @DisplayName("Stale-controller line escapes the literal '+' in '30+ дней'")
        void escapesPlusInStaleLine() {
            String text = formatter.format(new ChatDigestStatsDto(-100L, 1, 1, 0, 0, 0, 0, 4));

            assertThat(text).contains("30\\+ дней");
        }
    }

    @Nested
    @DisplayName("Optional lines — hidden when zero")
    class OptionalLines {

        @Test
        @DisplayName("Stale/muted/paused lines omitted entirely when their count is 0")
        void zeroCounts_linesOmitted() {
            String text = formatter.format(new ChatDigestStatsDto(-100L, 5, 2, 0, 0, 12, 0, 0));

            assertThat(text).doesNotContain("Без новых событий");
            assertThat(text).doesNotContain("Замьючено");
            assertThat(text).doesNotContain("На паузе");
        }

        @Test
        @DisplayName("Muted line present when count > 0")
        void mutedGreaterThanZero_linePresent() {
            String text = formatter.format(new ChatDigestStatsDto(-100L, 5, 2, 3, 0, 12, 0, 0));

            assertThat(text).contains("Замьючено").contains("*3*");
        }
    }

    @Nested
    @DisplayName("Week-over-week trend")
    class Trend {

        @Test
        @DisplayName("No prior-week baseline (0) — no trend suffix, no divide-by-zero")
        void noPreviousWeek_noTrendSuffix() {
            String text = formatter.format(new ChatDigestStatsDto(-100L, 1, 1, 0, 0, 10, 0, 0));

            assertThat(text).doesNotContain("↑").doesNotContain("↓");
        }

        @Test
        @DisplayName("Increase from last week renders an escaped up-arrow percentage")
        void increase_rendersUpArrow() {
            String text = formatter.format(new ChatDigestStatsDto(-100L, 1, 1, 0, 0, 15, 10, 0));

            assertThat(text).contains("↑ 50%").contains("\\(↑ 50%\\)");
        }

        @Test
        @DisplayName("Decrease from last week renders an escaped down-arrow percentage")
        void decrease_rendersDownArrow() {
            String text = formatter.format(new ChatDigestStatsDto(-100L, 1, 1, 0, 0, 5, 10, 0));

            assertThat(text).contains("↓ 50%").contains("\\(↓ 50%\\)");
        }
    }
}
