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
import com.valui.parser.health.ParserHealthService;
import com.valui.parser.util.ParsedUrlIds;
import com.valui.parser.util.UrlParser;
import com.valui.monitor.watch.MarketWatchFiredEvent;
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.watch.MarketWatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    private final ControllerPortService     controllerPort;
    private final DetectedEventPortService  detectedEventPort;
    private final MarketWatchService        marketWatchService;
    private final ParserFactory             parserFactory;
    private final ParserHealthService       parserHealthService;
    private final ApplicationEventPublisher events;
    private final MonitorProperties         props;
    private final EventDeduplicationService dedup;
    private final OutboxEventRepository outboxRepo;
    private final OutboxSenderService outboxSenderService;

    /** Minimal projection for scheduling decisions (no lazy associations). */
    public record ControllerScheduleInfo(
            UUID controllerId,
            UUID userId,
            Long telegramId,
            int pollIntervalSec,
            BookmakerType bookmaker
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
        return controllerPort.findAllActiveWithUser()
                .stream()
                .map(c -> new ControllerScheduleInfo(
                        c.getId(),
                        c.getUser().getId(),
                        c.getUser().getTelegramId(),
                        pollInterval(c),
                        c.getBookmaker()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ControllerScheduleInfo> loadActiveForUser(UUID userId) {
        return controllerPort.findAllActiveByUserIdWithUser(userId)
                .stream()
                .map(c -> new ControllerScheduleInfo(
                        c.getId(),
                        c.getUser().getId(),
                        c.getUser().getTelegramId(),
                        pollInterval(c),
                        c.getBookmaker()))
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
    record ParsedItem(String id, String title, String url, String extraData) {}

    // ── Step 3: Fetch from parser (NO transaction — external HTTP call) ───────

    public boolean isParserAvailable(BookmakerType bookmaker) {
        return parserHealthService.isAvailable(bookmaker)
            && parserFactory.getParser(bookmaker).isConnectionReady();
    }

    // Controllers whose URL can never yield a tournamentId/sportId are permanently broken —
    // without this, the WARN below would repeat every single poll cycle (every
    // defaultPollIntervalSec) forever, for as long as the controller exists. Warn once per
    // controller, then drop to DEBUG so the condition stays visible without spamming.
    private final java.util.Set<UUID> warnedNoTournament = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public List<ParsedItem> fetch(TaskContext ctx) {
        if (ctx.tournamentId() == null && ctx.sportId() == null) {
            if (warnedNoTournament.add(ctx.controllerId())) {
                log.warn("No tournamentId/sportId extractable from URL {} controllerId={} — " +
                        "will keep retrying every poll (further occurrences logged at DEBUG)",
                        ctx.url(), ctx.controllerId());
            } else {
                log.debug("No tournamentId/sportId extractable from URL {} controllerId={}",
                        ctx.url(), ctx.controllerId());
            }
            return List.of();
        }

        BookmakerParser parser = parserFactory.getParser(ctx.bookmaker());

        if (ctx.tournamentId() != null) {
            ParseResult<List<ParsedMatchDto>> result = parser.fetchMatches(ctx.tournamentId());
            if (!result.success() || result.data() == null) {
                // Promoted from DEBUG: this is the actual root cause behind a controller going
                // quiet (bookmaker API error, CB-open passthrough, timeout) — DEBUG is invisible
                // in prod (com.valui is INFO there), so this was previously undiagnosable
                // without correlating scattered parser-layer logs after the fact.
                if (!result.success())
                    log.warn("fetchMatches error controllerId={} tournamentId={}: {}",
                            ctx.controllerId(), ctx.tournamentId(), result.errorMessage());
                return List.of();
            }
            return result.data().stream()
                    .filter(m -> m.id() != null)
                    .map(m -> new ParsedItem(m.id(), m.title(), m.url(), m.extraData()))
                    .toList();
        }

        // sportId case (no tournamentId)
        ParseResult<List<TournamentDto>> result = parser.fetchTournaments(ctx.sportId());
        if (!result.success() || result.data() == null) {
            if (!result.success())
                log.warn("fetchTournaments error controllerId={} sportId={}: {}",
                        ctx.controllerId(), ctx.sportId(), result.errorMessage());
            return List.of();
        }
        return result.data().stream()
                .filter(t -> t.id() != null)
                .map(t -> new ParsedItem(t.id(), t.title(), t.url(), null))
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

        // Build lookup: externalId → extraData (for passing through to outbox/events)
        java.util.Map<String, String> extraByExternalId = new java.util.HashMap<>();
        for (ParsedItem item : fetched) {
            if (item.extraData() != null) extraByExternalId.put(item.id(), item.extraData());
        }

        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(props.getDedupTtlDays());
        List<DetectedEventEntity> saved = new ArrayList<>();
        for (ParsedItem item : fetched) {
            // Redis atomic claim replaces the per-event DB existsBy query (O(1) vs O(log n)).
            // Deliberately not caught here: a Redis outage must abort this whole transaction
            // (better to retry the entire poll next cycle than to silently persist events with
            // no dedup guarantee). Logged distinctly before rethrow so "Event persistence
            // failed" in ControllerTask (the generic catch-all one level up) doesn't get
            // conflated with an unrelated DB constraint failure — those need different
            // on-call responses.
            boolean isNew;
            try {
                isNew = dedup.claimIfNew(ctx.controllerId(), item.id());
            } catch (Exception e) {
                log.error("[DEDUP] Redis claimIfNew failed controllerId={} eventId={} — aborting this poll's persist: {}",
                        ctx.controllerId(), item.id(), e.toString());
                throw e;
            }
            if (!isNew) continue;

            // Always persist to DB — including warmup — so the nightly dedup sync can find
            // these events and won't clear them from Redis on the first 3 AM run.
            // ON CONFLICT DO NOTHING keeps the transaction clean when Redis TTL expires and
            // already-seen events pass claimIfNew() (the key was gone but DB still has them).
            UUID entityId = UUID.randomUUID();
            String title  = item.title() != null ? item.title() : item.id();
            boolean inserted = detectedEventPort.insertIfAbsent(
                    entityId, ctrl.getId(), item.id(), title, item.url(),
                    extraByExternalId.get(item.id()), expiresAt);

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
                    // Guard against uq_outbox_event_chat violation: if the nightly DedupSyncScheduler
                    // removed this event from Redis (because detected_events was cleaned), the next poll
                    // re-detects the event and tries to re-insert an outbox row that was already sent.
                    if (outboxRepo.existsByExternalEventIdAndChatId(e.getEventExternalId(), sub.getChatId())) {
                        log.warn("[CTRL] Outbox duplicate skipped: event={} chat={} ctrl={}",
                                e.getEventExternalId(), sub.getChatId(), ctx.controllerId());
                        continue;
                    }
                    String extraData = extraByExternalId.get(e.getEventExternalId());
                    outboxRepo.save(outboxSenderService.buildOutboxEvent(
                            e.getEventExternalId(),
                            ctx.controllerId().toString(),
                            sub.getUserId().toString(),
                            sub.getTelegramId(),
                            sub.getChatId(),
                            ctx.bookmaker().name(),
                            e.getTitle(),
                            e.getUrl(),
                            extraData));
                    events.publishEvent(new SportEventDetectedEvent(
                            ctrl.getId(),
                            sub.getUserId(),
                            sub.getTelegramId(),
                            sub.getChatId(),
                            ctx.bookmaker(),
                            e.getEventExternalId(),
                            e.getTitle(),
                            e.getUrl(),
                            extraData));
                }
            });
        }

        return saved.size();
    }

    // ── Market watch check ────────────────────────────────────────────────────

    /**
     * For each active market watch on this controller, checks if the watched market (hcap or total)
     * has appeared in the latest fetch result. Fires {@link MarketWatchFiredEvent} for each match.
     * Called after every successful fetch, including cycles with no new events.
     *
     * Delivery guarantee: {@code markFired} and {@code publishEvent} run inside a single
     * transaction; the event is published AFTER_COMMIT so the listener can never see a watch
     * row that is not yet FIRED in the DB.  If the transaction rolls back, no alert is sent.
     */
    @Transactional
    public void checkMarketWatches(UUID controllerId, List<ParsedItem> fetched) {
        if (fetched.isEmpty()) return;

        List<String> fetchedIds = fetched.stream().map(ParsedItem::id).toList();
        var watches = marketWatchService.findActiveByController(controllerId, fetchedIds);
        if (watches.isEmpty()) return;

        String tournamentTitle = controllerPort.findById(controllerId)
                .map(com.valui.common.entity.ControllerEntity::getTitle)
                .orElse(null);

        // Build a lookup map: externalEventId → extraData from latest fetch
        java.util.Map<String, String> extraByEventId = new java.util.HashMap<>();
        for (ParsedItem item : fetched) {
            if (item.extraData() != null) extraByEventId.put(item.id(), item.extraData());
        }

        for (var watch : watches) {
            String extraData = extraByEventId.get(watch.getExternalEventId());
            if (extraData == null) continue; // event not in this fetch (may have ended)

            // Use "key":{ pattern to match JSON object values only — prevents false positives
            // from string fields that might contain "h1": or "tb": as substrings.
            boolean appeared = "HCAP".equals(watch.getMarketType())
                    ? extraData.contains("\"h1\":{")
                    : extraData.contains("\"tb\":{");

            if (appeared) {
                marketWatchService.markFired(watch.getId());
                // Publish AFTER_COMMIT: listener only receives the event once the DB row is
                // durably committed as FIRED, preventing duplicate alerts on rollback.
                events.publishEvent(new MarketWatchFiredEvent(
                        this,
                        watch.getId(),
                        watch.getChatId(),
                        watch.getTelegramId(),
                        watch.getMarketType(),
                        watch.getMatchTitle(),
                        watch.getMatchUrl(),
                        watch.getBookmaker(),
                        extraData,
                        tournamentTitle));
                log.info("[WATCH] {} appeared for event={} chatId={}",
                        watch.getMarketType(), watch.getExternalEventId(), watch.getChatId());
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private int pollInterval(ControllerEntity c) {
        return c.getPollIntervalSec() != null
                ? c.getPollIntervalSec()
                : props.getDefaultPollIntervalSec();
    }
}
