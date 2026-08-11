package com.valui.app.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/**
 * Logback converter: replaces %subsystem with a fixed-width colored tag
 * derived from the logger's package (com.valui.BOT / MON / PAR / USR …).
 *
 * Register in logback-spring.xml:
 *   <conversionRule conversionWord="subsystem"
 *                   converterClass="com.valui.app.logging.SubsystemConverter"/>
 */
public class SubsystemConverter extends CompositeConverter<ILoggingEvent> {

    // ANSI escape sequences
    private static final String RESET   = "\033[0m";
    private static final String BOLD    = "\033[1m";
    private static final String CYAN    = "\033[36m";
    private static final String GREEN   = "\033[32m";
    private static final String YELLOW  = "\033[33m";
    private static final String MAGENTA = "\033[35m";
    private static final String BLUE    = "\033[34m";
    private static final String WHITE   = "\033[37m";
    private static final String DIM     = "\033[2m";
    private static final String BRIGHT_GREEN = "\033[92m";

    @Override
    protected String transform(ILoggingEvent event, String ignored) {
        String logger = event.getLoggerName();
        return tag(label(logger), colorFor(logger));
    }

    /**
     * Bare 3-letter subsystem label ("BOT", "MON", …) with no ANSI — shared with
     * {@link PlainSubsystemConverter} so both stay in sync off one source of truth.
     */
    static String label(String logger) {
        if (logger == null)                          return "APP";
        if (logger.startsWith("com.valui.bot"))      return "BOT";
        if (logger.startsWith("com.valui.monitor"))  return "MON";
        if (logger.startsWith("com.valui.parser"))   return "PAR";
        if (logger.startsWith("com.valui.user"))     return "USR";
        if (logger.startsWith("com.valui.notify"))   return "NTF";
        if (logger.startsWith("com.valui.admin"))    return "ADM";
        if (logger.startsWith("com.valui.betting"))  return "BET";
        if (logger.startsWith("com.valui"))          return "APP";
        return "SYS";
    }

    private static String colorFor(String logger) {
        if (logger == null)                          return WHITE;
        if (logger.startsWith("com.valui.bot"))      return CYAN;
        if (logger.startsWith("com.valui.monitor"))  return YELLOW;
        if (logger.startsWith("com.valui.parser"))   return MAGENTA;
        if (logger.startsWith("com.valui.user"))     return GREEN;
        if (logger.startsWith("com.valui.notify"))   return BLUE;
        if (logger.startsWith("com.valui.admin"))    return WHITE;
        if (logger.startsWith("com.valui.betting"))  return BRIGHT_GREEN;
        if (logger.startsWith("com.valui"))          return DIM + WHITE;
        return DIM;
    }

    private static String tag(String label, String color) {
        return color + BOLD + "[" + label + "]" + RESET;
    }
}
