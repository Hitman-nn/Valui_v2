package com.valui.monitor.scheduler;

import com.valui.parser.api.BookmakerParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls availability of all registered parsers.
 * Full per-tournament polling is delegated to a controller-aware service
 * that resolves tournament IDs from active ControllerEntity records.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorScheduler {

    private final List<BookmakerParser> parsers;
    private final MatchEventPublisher publisher;

    @Scheduled(fixedDelayString = "${valui.monitor.poll-interval-ms:30000}")
    public void poll() {
        parsers.forEach(parser -> {
            try {
                boolean up = parser.isAvailable();
                log.debug("Parser {} available={}", parser.getBookmaker(), up);
            } catch (Exception e) {
                log.error("Availability check failed for {}", parser.getBookmaker(), e);
            }
        });
    }
}
