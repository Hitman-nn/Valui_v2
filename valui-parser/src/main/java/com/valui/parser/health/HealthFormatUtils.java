package com.valui.parser.health;

import java.time.Duration;

/** Shared formatting helpers for parser health components. */
final class HealthFormatUtils {

    private HealthFormatUtils() {}

    static String formatDuration(Duration d) {
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) return h + "h" + m + "m";
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }
}
