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
            String description,
            String module,
            String scheduleDescription,
            ScheduleType scheduleType,
            String cronExpr,      // set for CRON tasks
            long intervalMs       // set for FIXED_RATE / FIXED_DELAY tasks
    ) {}

    private static final List<TaskMeta> TASKS = List.of(
        new TaskMeta("DedupSyncScheduler.sync",
                "Dedup Sync",
                "Ночная синхронизация дедупликационных ключей: удаляет из Redis устаревшие L2-ключи событий и разбанивает контроллеры с истёкшим ban-TTL, предотвращая повторную рассылку старых событий.",
                "monitor", "03:00 ежедневно",
                ScheduleType.CRON,  "0 0 3 * * *", 0),
        new TaskMeta("OutboxPurgeService.purge",
                "Outbox Purge",
                "Удаляет из БД обработанные записи Outbox (статус SENT). Без этой очистки таблица outbox_events неограниченно растёт и замедляет основной цикл сканирования.",
                "monitor", "03:30 ежедневно",
                ScheduleType.CRON,  "0 30 3 * * *", 0),
        new TaskMeta("PollHistoryService.purgeOld",
                "Poll History Purge",
                "Удаляет старые записи истории опросов контроллеров (poll_history) старше заданного горизонта. Освобождает место в БД, оставляя актуальную статистику для графиков.",
                "monitor", "02:00 ежедневно",
                ScheduleType.CRON,  "0 0 2 * * *", 0),
        new TaskMeta("MonthlyTokenBillingScheduler.runMonthlyBilling",
                "Monthly Billing",
                "Ежемесячное списание токенов: 1-го числа в 01:00 начисляет/списывает токены всем активным пользователям согласно их тарифному плану (tokenMonthlyGrantRef).",
                "user", "1-е числа в 01:00",
                ScheduleType.CRON,  "0 0 1 1 * *", 0),
        new TaskMeta("OutboxHealthLogger.logHealth",
                "Outbox Health",
                "Логирует количество необработанных записей в таблице outbox_events. Если накопилось > порога — пишет WARN, что помогает обнаружить остановку Kafka-продюсера.",
                "monitor", "каждые 10 мин",
                ScheduleType.FIXED_RATE, null, 600_000),
        new TaskMeta("MonitorSummaryLogger.logSummary",
                "Monitor Summary",
                "Логирует сводку работы планировщика опросов за прошедший интервал: число выполненных задач, ошибки, среднюю задержку, количество обнаруженных событий.",
                "monitor", "каждые 10 мин",
                ScheduleType.FIXED_RATE, null, 600_000),
        new TaskMeta("SchedulerMetricsHistoryService.snapshot",
                "Metrics Snapshot",
                "Снимает и сохраняет в Redis скользящую историю метрик планировщика (глубина очереди, in-flight, p95-задержка). Используется для временны́х графиков на странице Scheduler.",
                "monitor", "каждые 30 сек",
                ScheduleType.FIXED_DELAY, null, 30_000),
        new TaskMeta("DlqMonitor.checkDlqFinal",
                "DLQ Monitor",
                "Проверяет Dead Letter Queue уведомлений (notifications.dlq): считает сообщения, которые не удалось доставить после всех ретраев. При накоплении пишет WARN в лог.",
                "notify", "каждые 15 мин",
                ScheduleType.FIXED_DELAY, null, 900_000),
        new TaskMeta("NotificationSummaryLogger.logSummary",
                "Notify Summary",
                "Логирует сводку сервиса уведомлений за прошедший интервал: отправлено, заблокировано rate-limiter-ом, ошибки доставки, число уникальных получателей.",
                "notify", "каждые 10 мин",
                ScheduleType.FIXED_RATE, null, 600_000),
        new TaskMeta("JvmMetricsHistoryService.collectAndStore",
                "JVM Metrics",
                "Собирает метрики JVM (heap, non-heap, CPU, потоки, RPS, диск) и сохраняет скользящую историю в Redis. Данные отображаются на графиках Dashboard → JVM.",
                "admin", "каждую минуту",
                ScheduleType.FIXED_RATE, null, 60_000),
        new TaskMeta("OutboxSenderService.scanAndSend",
                "Outbox Scan",
                "Основной цикл транзакционного Outbox: сканирует необработанные события в outbox_events и отправляет их в Kafka-топик sport.events.detected. Обеспечивает гарантию at-least-once delivery.",
                "monitor", "каждые 5 сек",
                ScheduleType.FIXED_DELAY, null, 5_000)
    );

    // ── Response DTOs ─────────────────────────────────────────────────────────

    public record SystemTaskDto(
            String  key,
            String  displayName,
            String  description,
            String  module,
            String  scheduleDescription,
            Instant nextFireTime,
            Instant lastRunAt,
            Long    lastDurationMs,
            String  lastStatus,
            String  lastErrorMessage,
            int     runCount,
            int     errorCount
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
                    String errorMessage = exec != null ? exec.lastErrorMessage() : null;
                    int    runCount     = exec != null ? exec.runCount()         : 0;
                    int    errorCount   = exec != null ? exec.errorCount()       : 0;
                    return new SystemTaskDto(
                            meta.key(), meta.displayName(), meta.description(),
                            meta.module(), meta.scheduleDescription(),
                            nextFireTime, lastRunAt, durationMs, status,
                            errorMessage, runCount, errorCount);
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
