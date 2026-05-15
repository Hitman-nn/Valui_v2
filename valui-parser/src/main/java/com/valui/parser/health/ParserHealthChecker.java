package com.valui.parser.health;

import com.valui.common.domain.BookmakerType;
import com.valui.parser.api.BookmakerParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParserHealthChecker {

    private static final int FAILURE_THRESHOLD = 3;
    // Re-alert every N failures while still unavailable (~60 min at 5-min check interval)
    private static final int REPEAT_INTERVAL   = 12;

    private final List<BookmakerParser> parsers;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<BookmakerType, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<BookmakerType, Instant> incidentStart      = new ConcurrentHashMap<>();
    // Tracks parsers for which ParserUnavailableEvent was already published so we don't re-publish
    // until the parser recovers and fails again in a new incident.
    private final Set<BookmakerType> notifiedUnavailable =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void checkAll() {
        log.debug("Running parser health check for {} parsers", parsers.size());
        parsers.forEach(this::checkOne);
    }

    private void checkOne(BookmakerParser parser) {
        BookmakerType type = parser.getBookmaker();
        try {
            boolean available = parser.isAvailable();
            if (available) {
                int prev = consecutiveFailures.getOrDefault(type, 0);
                boolean wasNotified = notifiedUnavailable.remove(type);
                consecutiveFailures.put(type, 0);
                Instant start = incidentStart.remove(type);
                String duration = start != null
                        ? " (duration=" + formatDuration(Duration.between(start, Instant.now())) + ", failures=" + prev + ")"
                        : "";
                if (wasNotified) {
                    log.info("Parser {} recovered after extended unavailability{}", type, duration);
                } else if (prev > 0) {
                    log.info("Parser {} recovered after {} consecutive failures{}", type, prev, duration);
                }
            } else {
                recordFailure(type, "isAvailable() returned false");
            }
        } catch (Exception e) {
            recordFailure(type, e.getMessage());
        }
    }

    private void recordFailure(BookmakerType type, String reason) {
        int failures = consecutiveFailures.merge(type, 1, Integer::sum);
        incidentStart.putIfAbsent(type, Instant.now());
        log.warn("Parser {} unavailable: {} (consecutive={})", type, reason, failures);
        if (failures == FAILURE_THRESHOLD && !notifiedUnavailable.contains(type)) {
            log.error("Parser {} exceeded failure threshold — publishing ParserUnavailableEvent", type);
            eventPublisher.publishEvent(new ParserUnavailableEvent(this, type, failures));
            notifiedUnavailable.add(type);
        } else if (notifiedUnavailable.contains(type) && failures % REPEAT_INTERVAL == 0) {
            log.error("Parser {} still unavailable (consecutive={}) — re-publishing ParserUnavailableEvent", type, failures);
            eventPublisher.publishEvent(new ParserUnavailableEvent(this, type, failures));
        }
    }

    private static String formatDuration(Duration d) {
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) return h + "h" + m + "m";
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }
}
