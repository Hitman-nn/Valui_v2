package com.valui.monitor.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Logs outbox health every 10 minutes when there are pending (unsent) rows.
 * Silent when outbox is clean — no noise in steady state.
 * Signals Kafka delivery lag without waiting for the DlqMonitor threshold.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxHealthLogger {

    private final OutboxEventRepository outboxRepo;

    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.MINUTES, initialDelay = 10)
    public void logHealth() {
        long pending = outboxRepo.countUnsent();
        if (pending == 0) return;

        String age = outboxRepo.findOldestUnsentCreatedAt()
                .map(t -> Duration.between(t.toInstant(), Instant.now()).toSeconds() + "s")
                .orElse("?");
        log.info("[OUTBOX] pending={} oldest={}", pending, age);
    }
}
