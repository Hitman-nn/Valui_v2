package com.valui.app.jobs;

import com.valui.bot.prematch.PreMatchOddsService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Exposes system scheduled tasks and pre-match snapshot stats for the admin UI.
 * URL: GET /api/v1/admin/jobs
 *
 * Task metadata is declared statically; runtime data (lastRunAt, duration, status)
 * is supplied by {@link ScheduledTaskTracker} via {@link ScheduledTaskAspect}.
 */
@RestController
@RequestMapping("/api/v1/admin/jobs")
@RequiredArgsConstructor
public class AdminJobsController {

    // ── Static task metadata ──────────────────────────────────────────────────

    private enum ScheduleType { CRON, FIXED_RATE, FIXED_DELAY }

    private record TaskMeta(
            String key,           // ClassName.methodName — matches AOP key
            String displayName,
            String module,
            String scheduleDescription,
            ScheduleType scheduleType,
            String cronExpr,      // set for CRON tasks
            long intervalMs       // set for FIXED_RATE / FIXED_DELAY tasks
    ) {}

    private static final List<TaskMeta> TASKS = List.of(
        new TaskMeta("DedupSyncScheduler.sync",
                "Dedup Sync",           "monitor", "03:00 ежедневно",
                ScheduleType.CRON,  "0 0 3 * * *", 0),
        new TaskMeta("OutboxPurgeService.purge",
                "Outbox Purge",         "monitor", "03:30 ежедневно",
                ScheduleType.CRON,  "0 30 3 * * *", 0),
        new TaskMeta("PollHistoryService.purgeOld",
                "Poll History Purge",   "monitor", "02:00 ежедневно",
                ScheduleType.CRON,  "0 0 2 * * *", 0),
        new TaskMeta("MonthlyTokenBillingScheduler.runMonthlyBilling",
                "Monthly Billing",      "user",    "1-е числа в 01:00",
                ScheduleType.CRON,  "0 0 1 1 * *", 0),
        new TaskMeta("OutboxHealthLogger.logHealth",
                "Outbox Health",        "monitor", "каждые 10 мин",
                ScheduleType.FIXED_RATE, null, 600_000),
        new TaskMeta("MonitorSummaryLogger.logSummary",
                "Monitor Summary",      "monitor", "каждые 10 мин",
                ScheduleType.FIXED_RATE, null, 600_000),
        new TaskMeta("SchedulerMetricsHistoryService.snapshot",
                "Metrics Snapshot",     "monitor", "каждые 30 сек",
                ScheduleType.FIXED_DELAY, null, 30_000),
        new TaskMeta("DlqMonitor.checkDlqFinal",
                "DLQ Monitor",          "notify",  "каждые 15 мин",
                ScheduleType.FIXED_DELAY, null, 900_000),
        new TaskMeta("NotificationSummaryLogger.logSummary",
                "Notify Summary",       "notify",  "каждые 10 мин",
                ScheduleType.FIXED_RATE, null, 600_000),
        new TaskMeta("JvmMetricsHistoryService.collectAndStore",
                "JVM Metrics",          "admin",   "каждую минуту",
                ScheduleType.FIXED_RATE, null, 60_000),
        new TaskMeta("OutboxSenderService.scanAndSend",
                "Outbox Scan",          "monitor", "каждые 5 сек",
                ScheduleType.FIXED_DELAY, null, 5_000)
    );

    // ── Response DTOs ─────────────────────────────────────────────────────────

    public record SystemTaskDto(
            String key,
            String displayName,
            String module,
            String scheduleDescription,
            Instant nextFireTime,
            Instant lastRunAt,
            Long   lastDurationMs,
            String lastStatus
    ) {}

    public record JobsOverviewDto(
            List<SystemTaskDto>           tasks,
            PreMatchOddsService.PreMatchStats preMatch
    ) {}

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final ScheduledTaskTracker tracker;
    private final PreMatchOddsService  preMatchOddsService;

    // ── Endpoint ──────────────────────────────────────────────────────────────

    @GetMapping
    public JobsOverviewDto overview() {
        Map<String, ScheduledTaskTracker.TaskExecution> executions = tracker.getAll();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());

        List<SystemTaskDto> tasks = TASKS.stream()
                .map(meta -> {
                    ScheduledTaskTracker.TaskExecution exec = executions.get(meta.key());
                    Instant lastRunAt     = exec != null ? exec.lastRunAt()   : null;
                    Long    durationMs    = exec != null ? exec.durationMs()  : null;
                    String  status        = exec != null ? exec.status()      : "NEVER_RUN";
                    Instant nextFireTime  = computeNext(meta, exec, now);
                    return new SystemTaskDto(
                            meta.key(), meta.displayName(), meta.module(),
                            meta.scheduleDescription(),
                            nextFireTime, lastRunAt, durationMs, status);
                })
                .sorted(Comparator.comparing(t -> t.nextFireTime() == null
                        ? Instant.MAX : t.nextFireTime()))
                .toList();

        return new JobsOverviewDto(tasks, preMatchOddsService.getStats());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Instant computeNext(TaskMeta meta,
                                        ScheduledTaskTracker.TaskExecution exec,
                                        ZonedDateTime now) {
        return switch (meta.scheduleType()) {
            case CRON -> {
                try {
                    ZonedDateTime next = CronExpression.parse(meta.cronExpr()).next(now);
                    yield next != null ? next.toInstant() : null;
                } catch (Exception e) {
                    yield null;
                }
            }
            case FIXED_RATE -> {
                if (exec == null) yield now.toInstant().plusMillis(meta.intervalMs());
                yield exec.lastRunAt().plusMillis(meta.intervalMs());
            }
            case FIXED_DELAY -> {
                if (exec == null) yield now.toInstant().plusMillis(meta.intervalMs());
                // fixed delay starts AFTER the previous run finishes
                yield exec.lastRunAt()
                        .plusMillis(exec.durationMs())
                        .plusMillis(meta.intervalMs());
            }
        };
    }
}
