package com.valui.admin.scheduler;

import com.valui.admin.scheduler.dto.*;
import com.valui.admin.security.CurrentUser;
import com.valui.admin.security.ValuiPrincipal;
import com.valui.common.entity.ControllerEntity;
import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.history.PollHistoryHourlyDto;
import com.valui.monitor.history.PollHistoryService;
import com.valui.monitor.scheduler.SchedulerConfigStore;
import com.valui.monitor.scheduler.SchedulerMetricsHistoryService;
import com.valui.monitor.scheduler.SchedulerMetricsSnapshot;
import com.valui.monitor.scheduler.drr.DrrDispatcher;
import com.valui.monitor.scheduler.job.ControllerJob;
import com.valui.monitor.scheduler.job.JobRegistry;
import com.valui.user.api.ControllerPortService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "Admin — Scheduler", description = "Планировщик мониторинга: конфиг, метрики, задания")
@RestController
@RequestMapping("/api/v1/admin/scheduler")
@RequiredArgsConstructor
public class AdminSchedulerController {

    private final MonitorProperties           props;
    private final SchedulerConfigStore        configStore;
    private final JobRegistry                 jobRegistry;
    private final DrrDispatcher               dispatcher;
    private final PollHistoryService          pollHistory;
    private final SchedulerMetricsHistoryService metricsHistory;
    private final MeterRegistry               meterRegistry;
    private final ControllerPortService       controllerPort;

    // ── Overview ──────────────────────────────────────────────────────────────

    @GetMapping("/overview")
    @Transactional(readOnly = true)
    @Operation(summary = "Полный снимок: конфиг + метрики + список заданий")
    public SchedulerOverviewDto overview() {
        return new SchedulerOverviewDto(getConfig(), getStats(), jobs());
    }

    // ── Config ────────────────────────────────────────────────────────────────

    @GetMapping("/config")
    @Operation(summary = "Текущая конфигурация планировщика")
    public SchedulerConfigDto getConfig() {
        return new SchedulerConfigDto(
                props.getMaxConcurrentTasks(),
                props.getDefaultPollIntervalSec(),
                props.getFetchBudgetMs(),
                props.getDeferBaseMs(),
                props.getDeferJitterMs(),
                props.getDefaultUserWeight()
        );
    }

    @PatchMapping("/config")
    @Operation(summary = "Обновить конфигурацию (сохраняется в БД, выживает перезапуск)")
    public SchedulerConfigDto updateConfig(@RequestBody SchedulerConfigUpdateRequest req,
                                           @Parameter(hidden = true) @CurrentUser ValuiPrincipal principal) {
        // Live-mutates concurrency/poll-interval config affecting every controller poll in prod —
        // logging only the new state (as before) makes it impossible to tell what actually
        // changed just from the log. Diff + adminId now both captured.
        SchedulerConfigDto before = getConfig();
        if (req.maxConcurrentTasks()     != null) props.setMaxConcurrentTasks(req.maxConcurrentTasks());
        if (req.defaultPollIntervalSec() != null) props.setDefaultPollIntervalSec(req.defaultPollIntervalSec());
        if (req.fetchBudgetMs()          != null) props.setFetchBudgetMs(req.fetchBudgetMs());
        if (req.deferBaseMs()            != null) props.setDeferBaseMs(req.deferBaseMs());
        if (req.deferJitterMs()          != null) props.setDeferJitterMs(req.deferJitterMs());
        if (req.defaultUserWeight()      != null) props.setDefaultUserWeight(req.defaultUserWeight());
        configStore.save();
        SchedulerConfigDto after = getConfig();
        log.info("[SCHEDULER] Config updated by adminTelegramId={}: {} -> {}",
                principal.telegramId(), before, after);
        return after;
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    @Operation(summary = "Метрики планировщика из Micrometer")
    public SchedulerStatsDto getStats() {
        return new SchedulerStatsDto(
                jobRegistry.size(),
                dispatcher.queueDepth(),
                dispatcher.availableSlots(),
                props.getMaxConcurrentTasks(),
                safeGauge("monitor.scheduler.starvation.seconds"),
                safeCounter("monitor.tasks.deferred"),
                safeCounter("monitor.tasks.skipped"),
                safeCounter("monitor.events.detected"),
                safeSummaryPct("monitor.scheduler.dispatch.lag.ms", 0.50),
                safeSummaryPct("monitor.scheduler.dispatch.lag.ms", 0.95),
                safeSummaryPct("monitor.scheduler.dispatch.lag.ms", 0.99),
                safeTimerMs("monitor.task.duration", 0.50),
                safeTimerMs("monitor.task.duration", 0.95),
                safeTimerMs("monitor.task.duration", 0.99)
        );
    }

    // ── Metrics history (ring buffer) ─────────────────────────────────────────

    @GetMapping("/metrics-history")
    @Operation(summary = "История метрик планировщика для графика (ring buffer Redis)")
    public List<SchedulerMetricsSnapshot> metricsHistory(
            @RequestParam(defaultValue = "1h") String range) {
        return metricsHistory.getHistory(range);
    }

    // ── Jobs ──────────────────────────────────────────────────────────────────

    @GetMapping("/jobs")
    @Transactional(readOnly = true)
    @Operation(summary = "Список всех активных заданий с информацией о контроллере")
    public List<ControllerJobDto> getJobs() {
        return jobs();
    }

    @GetMapping("/jobs/{controllerId}")
    @Transactional(readOnly = true)
    @Operation(summary = "Детали задания + последние 5 опросов из Redis")
    public ControllerJobDetailDto getJobDetail(@PathVariable UUID controllerId) {
        ControllerJob job = jobRegistry.get(controllerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Job not found: " + controllerId));
        return new ControllerJobDetailDto(
                enrichJob(job, Instant.now(), buildCtrlMap()),
                pollHistory.getLast(controllerId));
    }

    @GetMapping("/jobs/{controllerId}/hourly-stats")
    @Operation(summary = "Почасовая аналитика опросов за последние N часов")
    public List<PollHistoryHourlyDto> jobHourlyStats(
            @PathVariable UUID controllerId,
            @RequestParam(defaultValue = "24") int hours) {
        return pollHistory.getHourlyStats(controllerId, hours);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private List<ControllerJobDto> jobs() {
        Instant now = Instant.now();
        Map<UUID, ControllerEntity> ctrlMap = buildCtrlMap();
        return jobRegistry.all().stream()
                .map(j -> enrichJob(j, now, ctrlMap))
                .sorted(Comparator
                        .comparingLong(ControllerJobDto::overdueSec).reversed()
                        .thenComparing(d -> d.inFlight() ? 0 : 1))
                .toList();
    }

    /** Builds a Map controllerId → ControllerEntity for O(1) lookup. */
    private Map<UUID, ControllerEntity> buildCtrlMap() {
        try {
            return controllerPort.findAllActive().stream()
                    .collect(Collectors.toMap(ControllerEntity::getId, c -> c));
        } catch (Exception e) {
            log.warn("[SCHEDULER] Could not load controller map: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private ControllerJobDto enrichJob(ControllerJob j, Instant now,
                                       Map<UUID, ControllerEntity> ctrlMap) {
        long overdueSec = 0;
        String status;
        if (j.inFlight()) {
            status = "IN_FLIGHT";
        } else {
            long secLate = now.getEpochSecond() - j.nextRunAt().getEpochSecond();
            if (secLate > 0) { overdueSec = secLate; status = "LATE"; }
            else              { status = "IDLE"; }
        }

        ControllerEntity ctrl = ctrlMap.get(j.controllerId());
        String bookmaker       = ctrl != null ? ctrl.getBookmaker().name() : null;
        String controllerTitle = ctrl != null ? ctrl.getTitle() : null;
        String username        = ctrl != null && ctrl.getUser() != null
                ? ctrl.getUser().getUsername() : null;

        return new ControllerJobDto(
                j.controllerId().toString(),
                j.userId().toString(),
                j.pollIntervalSec(),
                j.nextRunAt().toString(),
                j.lastStartedAt()  != null ? j.lastStartedAt().toString()  : null,
                j.lastFinishedAt() != null ? j.lastFinishedAt().toString() : null,
                j.inFlight(),
                overdueSec,
                status,
                bookmaker,
                controllerTitle,
                username
        );
    }

    private double safeGauge(String name) {
        try { return meterRegistry.find(name).gauge().value(); }
        catch (Exception e) { return 0; }
    }

    private long safeCounter(String name) {
        try { return (long) meterRegistry.find(name).counter().count(); }
        catch (Exception e) { return 0; }
    }

    private double safeSummaryPct(String name, double pct) {
        try { return meterRegistry.find(name).summary().percentile(pct); }
        catch (Exception e) { return 0; }
    }

    private double safeTimerMs(String name, double pct) {
        try { return meterRegistry.find(name).timer().percentile(pct, TimeUnit.MILLISECONDS); }
        catch (Exception e) { return 0; }
    }
}
