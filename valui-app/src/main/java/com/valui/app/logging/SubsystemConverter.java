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

    @Override
    protected String transform(ILoggingEvent event, String ignored) {
        String logger = event.getLoggerName();
        if (logger == null) return tag("APP", WHITE);

        if (logger.startsWith("com.valui.bot"))    return tag("BOT", CYAN);
        if (logger.startsWith("com.valui.monitor"))return tag("MON", YELLOW);
        if (logger.startsWith("com.valui.parser")) return tag("PAR", MAGENTA);
        if (logger.startsWith("com.valui.user"))   return tag("USR", GREEN);
        if (logger.startsWith("com.valui.notify")) return tag("NTF", BLUE);
        if (logger.startsWith("com.valui.admin"))  return tag("ADM", WHITE);
        if (logger.startsWith("com.valui"))        return tag("APP", DIM + WHITE);
        return tag("SYS", DIM);
    }

    private static String tag(String label, String color) {
        return color + BOLD + "[" + label + "]" + RESET;
    }
}
