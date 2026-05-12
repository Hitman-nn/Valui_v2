package com.valui.app.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Map;

/**
 * Logback converter: appends " [u=X c=Y r=Z]" to log lines only when at least one
 * MDC field is set (i.e., inside an HTTP request context populated by MdcFilter).
 * Background tasks (scheduler, Kafka consumers) produce no MDC fields → silent.
 */
public class MdcContextConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        String userId    = mdc.getOrDefault("userId",    "");
        String chatId    = mdc.getOrDefault("chatId",    "");
        String requestId = mdc.getOrDefault("requestId", "");
        if (userId.isEmpty() && chatId.isEmpty() && requestId.isEmpty()) {
            return "";
        }
        return " [u=" + userId + " c=" + chatId + " r=" + requestId + "]";
    }
}
