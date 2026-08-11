package com.valui.app.health;

import com.valui.notify.service.AdminNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GcPauseWatchdog — threshold + alert cooldown")
class GcPauseWatchdogTest {

    @Mock AdminNotificationService adminNotificationService;

    private GcPauseWatchdog watchdog() {
        return new GcPauseWatchdog(adminNotificationService);
    }

    @Nested
    @DisplayName("Below WARN threshold (< 1000ms)")
    class BelowWarn {

        @Test
        @DisplayName("Never alerts, regardless of how many times it fires")
        void shortPause_neverAlerts() {
            GcPauseWatchdog w = watchdog();
            w.handlePause("G1 Young Generation", "end of minor GC", 200, 0L);
            w.handlePause("G1 Young Generation", "end of minor GC", 999, 100_000L);

            verify(adminNotificationService, never()).alertAdmin(anyString());
        }
    }

    @Nested
    @DisplayName("Between WARN and ALERT threshold (1000-2999ms)")
    class WarnOnly {

        @Test
        @DisplayName("Logs but does not alert Telegram — only genuinely severe pauses page the admin")
        void mediumPause_warnsButNoAlert() {
            GcPauseWatchdog w = watchdog();
            w.handlePause("G1 Old Generation", "end of major GC", 2_500, 0L);

            verify(adminNotificationService, never()).alertAdmin(anyString());
        }
    }

    @Nested
    @DisplayName("At/above ALERT threshold (>= 3000ms)")
    class AlertTier {

        @Test
        @DisplayName("First severe pause sends exactly one Telegram alert")
        void firstSeverePause_alertsOnce() {
            GcPauseWatchdog w = watchdog();
            w.handlePause("G1 Old Generation", "end of major GC", 3_000, 0L);

            verify(adminNotificationService, times(1)).alertAdmin(anyString());
        }

        @Test
        @DisplayName("Alert message names the collector, action, and duration")
        void alertMessage_containsPauseDetails() {
            GcPauseWatchdog w = watchdog();
            w.handlePause("G1 Old Generation", "end of major GC", 4_200, 0L);

            verify(adminNotificationService).alertAdmin(argThatContains(
                    "G1 Old Generation", "end of major GC", "4200"));
        }

        @Test
        @DisplayName("A second severe pause within the cooldown window is suppressed")
        void secondPauseWithinCooldown_suppressed() {
            GcPauseWatchdog w = watchdog();
            w.handlePause("G1 Old Generation", "end of major GC", 3_500, 0L);
            // 4 minutes later — still inside the 5-minute cooldown
            w.handlePause("G1 Old Generation", "end of major GC", 5_000, 4 * 60_000L);

            verify(adminNotificationService, times(1)).alertAdmin(anyString());
        }

        @Test
        @DisplayName("A pause after the cooldown expires sends a fresh alert")
        void pauseAfterCooldownExpires_alertsAgain() {
            GcPauseWatchdog w = watchdog();
            w.handlePause("G1 Old Generation", "end of major GC", 3_500, 0L);
            // 6 minutes later — past the 5-minute cooldown
            w.handlePause("G1 Old Generation", "end of major GC", 3_500, 6 * 60_000L);

            verify(adminNotificationService, times(2)).alertAdmin(anyString());
        }
    }

    private static String argThatContains(String... substrings) {
        return org.mockito.ArgumentMatchers.argThat(msg -> {
            if (msg == null) return false;
            for (String s : substrings) {
                if (!msg.contains(s)) return false;
            }
            return true;
        });
    }
}
