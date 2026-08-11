package com.valui.app.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback converter: same as the built-in %msg, but strips ANSI escape codes first.
 *
 * Most log messages never contain ANSI — the color tag is added separately by
 * {@code %subsystem}/{@code %highlight} at the pattern-layout level, which each appender
 * can already opt in or out of per-appender. The one exception is {@link StartupLogger}'s
 * multi-line startup banner, which builds its color codes directly into the message text
 * (it needs fine-grained inline coloring a pattern layout can't express) — for any
 * non-interactive appender (the rolling file), route %msg through this converter instead
 * so that banner doesn't leave raw escape sequences in a file meant to be grepped/tailed
 * without a terminal.
 *
 * Register in logback-spring.xml:
 *   <conversionRule conversionWord="plainmsg"
 *                   converterClass="com.valui.app.logging.PlainMessageConverter"/>
 */
public class PlainMessageConverter extends MessageConverter {

    // Built from the numeric code point (not a literal escape char in source) so this file
    // stays free of raw control bytes regardless of editor/encoding.
    private static final Pattern ANSI_RE = Pattern.compile(((char) 27) + "\\[[0-9;]*m");

    @Override
    public String convert(ILoggingEvent event) {
        return ANSI_RE.matcher(super.convert(event)).replaceAll("");
    }
}
