package com.valui.app.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Map;

/**
 * Logback converter: appends context fields to console log lines when MDC is populated.
 *
 * HTTP requests:    [T=<traceId> r=<requestId> u=<userId> c=<chatId>]
 * Kafka consumers:  [T=<traceId> log=<logId>]
 * Scheduler tasks:  [ctrl=<controllerId> bk=<bookmaker>]
 * Background tasks with no context produce no output.
 */
public class MdcContextConverter extends ClassicConverter {

    private static final String[] KEYS    = {"traceId", "logId", "controllerId", "bookmaker", "kafkaTopic", "channel", "userId", "chatId", "requestId", "adminTelegramId"};
    private static final String[] ABBREVS = {"T",       "log",   "ctrl",         "bk",        "topic",      "ch",      "u",      "c",      "r",         "adm"};

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null || mdc.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) {
            String val = mdc.get(KEYS[i]);
            if (val != null && !val.isEmpty()) {
                sb.append(sb.isEmpty() ? " [" : " ");
                sb.append(ABBREVS[i]).append('=');
                // Intentionally compare by key name (not by array index) so truncation
                // logic stays correct if the KEYS array is ever reordered.
                // traceId is 32 hex chars — show only the first 8 in console to keep lines short.
                sb.append("traceId".equals(KEYS[i]) && val.length() > 8 ? val.substring(0, 8) : val);
            }
        }
        if (sb.isEmpty()) return "";
        sb.append(']');
        return sb.toString();
    }
}
