package com.valui.monitor.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Nightly purge of old sent outbox rows.
 *
 * Sent rows are kept for auditing, but indefinite retention causes a correctness issue:
 * if a controller is recreated for the same URL (new UUID), the new controller detects
 * the same external_event_ids and tries to insert outbox rows that already exist in the
 * table (sent, from the old controller), hitting the uq_outbox_event_chat constraint.
 *
 * Runs at 03:30 daily — after the nightly dedup sync (03:00) so dedup state is stable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPurgeService {

    private static final int RETENTION_MONTHS = 6;

    private final OutboxEventRepository outboxRepo;

    @Scheduled(cron = "${valui.monitor.outbox.purge-cron:0 30 3 * * *}")
    @Transactional
    public void purge() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMonths(RETENTION_MONTHS);
        int deleted = outboxRepo.deleteSentBefore(cutoff);
        if (deleted > 0) {
            log.info("Outbox purge: deleted {} sent rows older than {} months", deleted, RETENTION_MONTHS);
        } else {
            log.debug("Outbox purge: nothing to delete (cutoff={})", cutoff.toLocalDate());
        }
    }
}
