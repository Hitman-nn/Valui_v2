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
    /** Per-user cap to prevent one user from hogging all task slots. */
    private int maxTasksPerUser = 5;
    /** Fallback poll interval when controller.pollIntervalSec is null. */
    private int defaultPollIntervalSec = 60;
    /** How long to keep event IDs in the Redis dedup SET (days). */
    private int dedupTtlDays = 7;
    /** Cron expression for the nightly dedup sync job. */
    private String dedupSyncCron = "0 0 3 * * *";
}
