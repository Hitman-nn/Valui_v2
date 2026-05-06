package com.valui.monitor.scheduler;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.ControllerSubscriptionEntity;
import com.valui.common.entity.DetectedEventEntity;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.parser.dto.TournamentDto;
import com.valui.monitor.config.MonitorProperties;
import com.valui.monitor.dedup.EventDeduplicationService;
import com.valui.monitor.event.SportEventDetectedEvent;
import com.valui.monitor.outbox.OutboxEventRepository;
import com.valui.monitor.outbox.OutboxSenderService;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.factory.ParserFactory;
import com.valui.parser.util.ParsedUrlIds;
import com.valui.parser.util.UrlParser;
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles the transactional DB reads/writes for a single controller task execution.
 * HTTP calls are intentionally excluded from transaction boundaries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControllerTaskExecutor {

    private final ControllerPortService controllerPort;
    private final DetectedEventPortService detectedEventPort;
    private final ParserFactory parserFactory;
    private final ApplicationEventPublisher events;
    private final MonitorProperties props;
    private final EventDeduplicationService dedup;
    private final OutboxEventRepository outboxRepo;
    private final OutboxSenderService outboxSenderService;

    /** Minimal projection for scheduling decisions (no lazy associations). */
    public record ControllerScheduleInfo(
            UUID controllerId,
            UUID userId,
            Long telegramId,
            int pollIntervalSec
    ) {}

    /** Full context needed to execute a task. */
    record TaskContext(
            UUID controllerId,
            UUID userId,
            Long telegramId,
            BookmakerType bookmaker,
            String url,
            String tournamentId,
            String sportId,
            ControllerType type
    ) {}

    // ── Step 1: Load all active controllers for initial scheduling ────────────

    @Transactional(readOnly = true)
    public List<ControllerScheduleInfo> loadAllActiveForScheduling() {
        return controllerPort.findAllActive()
                .stream()
                .map(c -> new ControllerScheduleInfo(
                        c.getId(),
                        c.getUser().getId(),
                        c.getUser().getTelegramId(),
                        pollInterval(c)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ControllerScheduleInfo> loadActiveForUser(UUID userId) {
        return controllerPort.findAllActiveByUserId(userId)
                .stream()
                .map(c -> new ControllerScheduleInfo(
                        c.getId(),
                        c.getUser().getId(),
                        c.getUser().getTelegramId(),
                        pollInterval(c)))
                .toList();
    }

    // ── Step 2: Load task context just before running (fresh per iteration) ───

    @Transactional(readOnly = true)
    public Optional<TaskContext> loadContext(UUID controllerId) {
        return controllerPort.findById(controllerId)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .flatMap(c -> {
                    try {
                        ParsedUrlIds ids = UrlParser.extractIds(c.getUrl(), c.getBookmaker());
                        return Optional.of(new TaskContext(
                                c.getId(),
                                c.getUser().getId(),
                                c.getUser().getTelegramId(),
                                c.getBookmaker(),
                                c.getUrl(),
                                ids.tournamentId(),
                                ids.sportId(),
                                c.getType()));
                    } catch (Exception e) {
                        log.warn("Cannot parse URL for controller {}: {}", controllerId, e.getMessage());
                        return Optional.empty();
                    }
                });
    }

    /** Intermediate representation used to decouple the two parser DTO types. */
    record ParsedItem(String id, String title, String url) {}

    // ── Step 3: Fetch from parser (NO transaction — external HTTP call) ───────

    public boolean isParserAvailable(BookmakerType bookmaker) {
        return parserFactory.getParser(bookmaker).isConnectionReady();
    }

    public List<ParsedItem> fetch(TaskContext ctx) {
        if (ctx.tournamentId() == null && ctx.sportId() == null) {
            log.warn("No tournamentId/sportId extractable from URL {} — skipping", ctx.url());
            return List.of();
        }

        BookmakerParser parser = parserFactory.getParser(ctx.bookmaker());

        if (ctx.tournamentId() != null) {
            ParseResult<List<ParsedMatchDto>> result = parser.fetchMatches(ctx.tournamentId());
            if (!result.success() || result.data() == null) {
                if (!result.success())
                    log.debug("fetchMatches error for {}: {}", ctx.controllerId(), result.errorMessage());
                return List.of();
            }
            return result.data().stream()
                    .filter(m -> m.id() != null)
                    .map(m -> new ParsedItem(m.id(), m.title(), m.url()))
                    .toList();
        }

        // sportId case (no tournamentId)
        ParseResult<List<TournamentDto>> result = parser.fetchTournaments(ctx.sportId());
        if (!result.success() || result.data() == null) {
            if (!result.success())
                log.debug("fetchTournaments error for {}: {}", ctx.controllerId(), result.errorMessage());
            return List.of();
        }
        return result.data().stream()
                .filter(t -> t.id() != null)
                .map(t -> new ParsedItem(t.id(), t.title(), t.url()))
                .toList();
    }

    // ── Step 4: Dedup, persist, update timestamps, publish events ─────────────

    @Transactional
    public int persistNewEvents(TaskContext ctx, List<ParsedItem> fetched) {
        if (fetched.isEmpty()) {
            controllerPort.updateLastCheckedAt(ctx.controllerId(), OffsetDateTime.now());
            return 0;
        }

        ControllerEntity ctrl = controllerPort.findById(ctx.controllerId())
                .orElseThrow(() -> new IllegalStateException("Controller vanished: " + ctx.controllerId()));

        // First run (warmup): lastCheckedAt == null → mark all events as seen silently
        boolean isFirstRun = ctrl.getLastCheckedAt() == null;

        List<DetectedEventEntity> saved = new ArrayList<>();
        for (ParsedItem item : fetched) {
            // Redis atomic claim replaces the per-event DB existsBy query (O(1) vs O(log n))
            if (!dedup.claimIfNew(ctx.controllerId(), item.id())) continue;

            // Always persist to DB — including warmup — so the nightly dedup sync can find
            // these events and won't clear them from Redis on the first 3 AM run.
            // ON CONFLICT DO NOTHING keeps the transaction clean when Redis TTL expires and
            // already-seen events pass claimIfNew() (the key was gone but DB still has them).
            UUID entityId = UUID.randomUUID();
            String title  = item.title() != null ? item.title() : item.id();
            boolean inserted = detectedEventPort.insertIfAbsent(
                    entityId, ctrl.getId(), item.id(), title, item.url());

            if (inserted && !isFirstRun) {
                // Build a value object for the fan-out loop below; fields match what was inserted.
                DetectedEventEntity entity = DetectedEventEntity.builder()
                        .id(entityId)
                        .controller(ctrl)
                        .eventExternalId(item.id())
                        .title(title)
                        .url(item.url())
                        .build();
                saved.add(entity);
            }
        }

        if (isFirstRun) {
            log.info("[CTRL] Warmup run for controller {} — {} events marked as seen silently",
                    ctx.controllerId(), fetched.size());
        }

        OffsetDateTime now = OffsetDateTime.now();
        ctrl.setLastCheckedAt(now);
        if (!saved.isEmpty()) ctrl.setLastEventAt(now);
        controllerPort.save(ctrl);

        if (!isFirstRun && !saved.isEmpty()) {
            // Fan-out: save outbox row and publish Spring event for EACH active subscription.
            // SportEventKafkaProducer fires AFTER_COMMIT for immediate Kafka delivery;
            // OutboxSenderService retries any row still unsent after 15 s.
            List<ControllerSubscriptionEntity> subs = controllerPort.findActiveSubscriptions(ctx.controllerId());
            saved.forEach(e -> {
                for (ControllerSubscriptionEntity sub : subs) {
                    outboxRepo.save(outboxSenderService.buildOutboxEvent(
                            e.getEventExternalId(),
                            ctx.controllerId().toString(),
                            sub.getUserId().toString(),
                            sub.getTelegramId(),
                            sub.getChatId(),
                            ctx.bookmaker().name(),
                            e.getTitle(),
                            e.getUrl()));
                    events.publishEvent(new SportEventDetectedEvent(
                            ctrl.getId(),
                            sub.getUserId(),
                            sub.getTelegramId(),
                            sub.getChatId(),
                            ctx.bookmaker(),
                            e.getEventExternalId(),
                            e.getTitle(),
                            e.getUrl()));
                }
            });
        }

        return saved.size();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private int pollInterval(ControllerEntity c) {
        return c.getPollIntervalSec() != null
                ? c.getPollIntervalSec()
                : props.getDefaultPollIntervalSec();
    }
}
