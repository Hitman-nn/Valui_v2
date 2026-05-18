package com.valui.monitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "valui.monitor")
public class MonitorProperties {
    /** Hard cap on parallel controller tasks across all users. */
    private int maxConcurrentTasks = 50;
    /** Per-user cap (legacy, kept for backward compat — DRR weight is the primary fairness knob). */
    private int maxTasksPerUser = 5;
    /** Fallback poll interval when controller.pollIntervalSec is null. */
    private int defaultPollIntervalSec = 60;
    /** How long to keep event IDs in the Redis dedup SET and detected_events DB rows (days).
     *  Must cover the full match lifecycle: tennis/football events can run up to 6 months. */
    private int dedupTtlDays = 180;
    /** Cron expression for the nightly dedup sync job. */
    private String dedupSyncCron = "0 0 3 * * *";
    /** Batch size for the nightly expired detected_events cleanup. */
    private int dedupCleanupBatchSize = 500;

    // ── DRR dispatcher ────────────────────────────────────────────────────────

    /** Hard wall-clock budget for one parser fetch call (ms). Enforced on top of WebClient timeouts. */
    private int fetchBudgetMs = 8_000;
    /** Minimum re-queue delay (ms) when the global pool is full (throttle, not drop). */
    private int deferBaseMs = 100;
    /** Maximum extra random jitter (ms) added to deferBaseMs on each defer. */
    private int deferJitterMs = 400;
    /** DRR weight assigned to users without an explicit plan mapping. */
    private int defaultUserWeight = 1;
}
