package com.valui.app.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/**
 * Logback converter: same subsystem tag as {@link SubsystemConverter} ([BOT] / [MON] / [PAR] …)
 * but without ANSI color codes — for destinations that aren't an interactive terminal (the
 * rolling file appender). Keeping this as a distinct conversion word (rather than a runtime
 * flag on SubsystemConverter) means each appender's PatternLayout picks the variant it needs
 * independently — no coordination required between appenders sharing the same log event.
 *
 * Register in logback-spring.xml:
 *   <conversionRule conversionWord="subsystemPlain"
 *                   converterClass="com.valui.app.logging.PlainSubsystemConverter"/>
 */
public class PlainSubsystemConverter extends CompositeConverter<ILoggingEvent> {

    @Override
    protected String transform(ILoggingEvent event, String ignored) {
        return "[" + SubsystemConverter.label(event.getLoggerName()) + "]";
    }
}
