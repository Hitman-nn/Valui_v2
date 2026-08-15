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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParserHealthChecker {

    private static final int FAILURE_THRESHOLD        = 3;
    private static final int REPEAT_INTERVAL          = 24;
    private static final int RECOVERY_CHECKS_REQUIRED = 2;

    private final List<BookmakerParser> parsers;
    private final ApplicationEventPublisher eventPublisher;
    // Whether an incident is "open" for a bookmaker is tracked here, not in a local Set — see
    // class javadoc on ParserIncidentStateStore for why: this checker's active probe is the one
    // detector that survives a restart and can confirm a real recovery, so it must be able to
    // see an incident opened by the fast CB-transition path too, not just ones it detected itself.
    private final ParserIncidentStateStore incidentStore;

    private final Map<BookmakerType, Integer> consecutiveFailures  = new ConcurrentHashMap<>();
    private final Map<BookmakerType, Integer> consecutiveSuccesses = new ConcurrentHashMap<>();
    private final Map<BookmakerType, Instant> incidentStart        = new ConcurrentHashMap<>();

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
                int successes = consecutiveSuccesses.merge(type, 1, Integer::sum);
                boolean wasOpen = incidentStore.isOpen(type);
                if (wasOpen && successes < RECOVERY_CHECKS_REQUIRED) {
                    log.info("Parser {} passed check {}/{} — waiting for stable recovery",
                            type, successes, RECOVERY_CHECKS_REQUIRED);
                    return;
                }
                int prev = consecutiveFailures.getOrDefault(type, 0);
                consecutiveFailures.put(type, 0);
                consecutiveSuccesses.remove(type);
                Instant start = incidentStart.remove(type);
                String duration = start != null
                        ? " (duration=" + formatDuration(Duration.between(start, Instant.now())) + ", failures=" + prev + ")"
                        : "";
                // markClosed (not just wasOpen) so a restart-carried-over incident opened by the
                // fast CB path still gets its recovery published here, even though this JVM
                // instance never itself called markOpen for it.
                if (wasOpen && incidentStore.markClosed(type)) {
                    log.info("Parser {} recovered after extended unavailability{}", type, duration);
                    eventPublisher.publishEvent(new ParserRecoveredEvent(this, type));
                } else if (prev > 0) {
                    log.info("Parser {} recovered after {} consecutive failures{}", type, prev, duration);
                }
            } else {
                consecutiveSuccesses.remove(type);
                recordFailure(type, "isAvailable() returned false");
            }
        } catch (Exception e) {
            consecutiveSuccesses.remove(type);
            recordFailure(type, e.getMessage());
        }
    }

    private void recordFailure(BookmakerType type, String reason) {
        int failures = consecutiveFailures.merge(type, 1, Integer::sum);
        incidentStart.putIfAbsent(type, Instant.now());
        if (failures <= FAILURE_THRESHOLD || isRepeatAlert(failures)) {
            log.warn("Parser {} unavailable: {} (consecutive={})", type, reason, failures);
        } else {
            log.debug("Parser {} unavailable: {} (consecutive={})", type, reason, failures);
        }
        if (failures == FAILURE_THRESHOLD) {
            if (incidentStore.markOpen(type)) {
                log.error("Parser {} exceeded failure threshold — publishing ParserUnavailableEvent", type);
                eventPublisher.publishEvent(new ParserUnavailableEvent(this, type, failures));
            }
        } else if (incidentStore.isOpen(type) && isRepeatAlert(failures)) {
            log.error("Parser {} still unavailable (consecutive={}) — re-publishing ParserUnavailableEvent", type, failures);
            eventPublisher.publishEvent(new ParserUnavailableEvent(this, type, failures));
        }
    }

    /** Fires at REPEAT_INTERVAL/2 (~1h), then every REPEAT_INTERVAL (~2h) after that. */
    private static boolean isRepeatAlert(int failures) {
        return failures == REPEAT_INTERVAL / 2
                || (failures >= REPEAT_INTERVAL && failures % REPEAT_INTERVAL == 0);
    }

    private static String formatDuration(Duration d) {
        return HealthFormatUtils.formatDuration(d);
    }
}
