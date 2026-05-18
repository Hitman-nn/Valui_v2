package com.valui.bot.prematch;

import com.valui.betting.repository.BetSlipRepository;
import com.valui.common.entity.BetSlipEntity;
import com.valui.common.parser.dto.ParsedMatchDto;
import com.valui.common.domain.BookmakerType;
import com.valui.parser.api.BookmakerParser;
import com.valui.parser.api.ParseResult;
import com.valui.parser.factory.ParserFactory;
import com.valui.parser.util.ParsedUrlIds;
import com.valui.parser.util.UrlParser;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreMatchOddsService {

    private static final long SNAPSHOT_OFFSET_MIN      = 30;
    private static final long RESCHEDULE_THRESHOLD_MIN = 10;
    private static final int  MAX_REGISTER_RETRIES     = 3;
    private static final long REGISTER_RETRY_DELAY_MIN = 15;
    // Caps simultaneous HTTP calls to bookmakers (shared by @Async VTs and scheduler threads)
    private static final int  MAX_CONCURRENT_FETCHES   = 4;

    private final BetSlipRepository slipRepo;
    private final ParserFactory     parserFactory;
    private final TransactionTemplate tx;

    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> pending         = new ConcurrentHashMap<>();
    /** Tracks scheduled retry futures to cancel superseded attempts (prevents duplicate retries on concurrent failure). */
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> retryPending    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer>            registerRetries  = new ConcurrentHashMap<>();
    private final Semaphore                                   fetchSemaphore   = new Semaphore(MAX_CONCURRENT_FETCHES, true);

    private final AtomicInteger threadCounter = new AtomicInteger();
    // 4 threads: up to 2 concurrent snapshots + 2 for registration retries
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4,
            r -> { Thread t = new Thread(r, "pre-match-" + threadCounter.getAndIncrement()); t.setDaemon(true); return t; });

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Async: fetches the match from the parser, stores startsAt + initial odds in the slip,
     * then schedules the snapshot task. Called right after a bet is saved.
     * On transient failure retries up to {@value MAX_REGISTER_RETRIES} times with increasing delay.
     */
    @Async
    public void register(UUID slipId) {
        doRegister(slipId);
    }

    /** Snapshot of current pre-match task state for admin monitoring. */
    public record PreMatchStats(
            int pendingSnapshots,
            int activeRetries,
            int fetchSemaphoreAvailable,
            Map<String, Integer> retryAttempts
    ) {}

    public PreMatchStats getStats() {
        Map<String, Integer> attempts = registerRetries.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().toString(),
                        Map.Entry::getValue));
        return new PreMatchStats(
                pending.size(),
                retryPending.size(),
                fetchSemaphore.availablePermits(),
                Collections.unmodifiableMap(attempts));
    }

    /** Cancel the scheduled snapshot and any pending registration retries for this slip. */
    public void cancel(UUID slipId) {
        ScheduledFuture<?> f = pending.remove(slipId);
        if (f != null) f.cancel(false);
        ScheduledFuture<?> rf = retryPending.remove(slipId);
        if (rf != null) rf.cancel(false);
        registerRetries.remove(slipId);
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void rescheduleOnStartup() {
        List<BetSlipEntity> slips = slipRepo.findPendingSnapshots(Instant.now());
        if (slips.isEmpty()) return;
        log.info("[PRE-MATCH] rescheduling {} slip(s) on startup", slips.size());
        for (BetSlipEntity slip : slips) {
            Instant triggerAt = slip.getStartsAt().minus(SNAPSHOT_OFFSET_MIN, ChronoUnit.MINUTES);
            if (triggerAt.isBefore(Instant.now())) {
                // Missed the window while app was down — take snapshot immediately
                scheduler.execute(() -> takeSnapshot(slip.getId()));
            } else {
                scheduleFor(slip);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        pending.values().forEach(f -> f.cancel(false));
        pending.clear();
        scheduler.shutdownNow();
    }

    // ── registration ──────────────────────────────────────────────────────────

    /**
     * Core registration logic, called both by {@link #register} (VT) and retry scheduler (PT).
     * Extracts match start time + initial odds, persists them, then arms the snapshot task.
     */
    private void doRegister(UUID slipId) {
        BetSlipEntity slip = slipRepo.findById(slipId).orElse(null);
        if (slip == null || slip.getMatchUrl() == null || slip.getMatchUrl().isBlank()) {
            registerRetries.remove(slipId);
            return;
        }
        if (slip.getStartsAt() != null) {
            // Already registered (e.g. by a previous retry that succeeded)
            registerRetries.remove(slipId);
            scheduleFor(slip);
            return;
        }
        try {
            ParsedMatchDto match = fetchMatch(slip.getMatchUrl());
            if (match == null) {
                log.debug("[PRE-MATCH] match not found at registration, slipId={}", slipId);
                scheduleRegisterRetry(slipId);
                return;
            }
            Instant startsAt = match.startsAt();
            if (startsAt == null || startsAt.equals(Instant.EPOCH)) {
                log.debug("[PRE-MATCH] startsAt unknown for slipId={}", slipId);
                scheduleRegisterRetry(slipId);
                return;
            }
            slip.setStartsAt(startsAt);
            slip.setInitialExtraData(match.extraData());
            slipRepo.save(slip);
            scheduleFor(slip);
            registerRetries.remove(slipId);
        } catch (Exception e) {
            log.warn("[PRE-MATCH] register failed slipId={}: {}", slipId, e.getMessage());
            scheduleRegisterRetry(slipId);
        }
    }

    private void scheduleRegisterRetry(UUID slipId) {
        int attempt = registerRetries.merge(slipId, 1, Integer::sum);
        if (attempt > MAX_REGISTER_RETRIES) {
            log.warn("[PRE-MATCH] giving up registration slipId={} after {} attempts", slipId, attempt - 1);
            registerRetries.remove(slipId);
            return;
        }
        long delayMin = REGISTER_RETRY_DELAY_MIN * attempt; // 15, 30, 45 min
        log.info("[PRE-MATCH] registration retry {}/{} in {}min for slipId={}",
                attempt, MAX_REGISTER_RETRIES, delayMin, slipId);

        // Cancel any previously scheduled retry to prevent duplicates when two concurrent
        // doRegister() calls both fail (e.g. two VTs from the same @Async invocation chain).
        ScheduledFuture<?> old = retryPending.remove(slipId);
        if (old != null) old.cancel(false);

        ScheduledFuture<?> f = scheduler.schedule(() -> {
            retryPending.remove(slipId);
            doRegister(slipId);
        }, delayMin, TimeUnit.MINUTES);
        retryPending.put(slipId, f);
    }

    // ── snapshot ──────────────────────────────────────────────────────────────

    private void scheduleFor(BetSlipEntity slip) {
        Instant triggerAt = slip.getStartsAt().minus(SNAPSHOT_OFFSET_MIN, ChronoUnit.MINUTES);
        if (!triggerAt.isAfter(Instant.now())) return;

        ScheduledFuture<?> old = pending.remove(slip.getId());
        if (old != null) old.cancel(false);

        long delayMs = Duration.between(Instant.now(), triggerAt).toMillis();
        ScheduledFuture<?> f = scheduler.schedule(() -> takeSnapshot(slip.getId()), delayMs, TimeUnit.MILLISECONDS);
        pending.put(slip.getId(), f);
        log.debug("[PRE-MATCH] snapshot scheduled slipId={} triggerAt={}", slip.getId(), triggerAt);
    }

    private void takeSnapshot(UUID slipId) {
        pending.remove(slipId);
        try {
            // Step 1: idempotency guard + read matchUrl — short read-only TX
            String matchUrl = tx.execute(status -> {
                BetSlipEntity slip = slipRepo.findById(slipId).orElse(null);
                if (slip == null || slip.getSnapshotTakenAt() != null || slip.getMatchUrl() == null)
                    return null;
                return slip.getMatchUrl();
            });
            if (matchUrl == null) return;

            // Step 2: HTTP fetch — outside any transaction, limited by semaphore
            ParsedMatchDto m = fetchMatch(matchUrl);
            if (m == null) {
                log.info("[PRE-MATCH] match not found at snapshot time, slipId={} — skipping", slipId);
                return;
            }

            // Step 3: write result — short write TX, no I/O inside
            boolean shifted = Boolean.TRUE.equals(tx.execute(status -> {
                BetSlipEntity slip = slipRepo.findById(slipId).orElse(null);
                if (slip == null || slip.getSnapshotTakenAt() != null) return false;

                if (!Instant.EPOCH.equals(m.startsAt()) && slip.getStartsAt() != null) {
                    long diffMin = Math.abs(Duration.between(slip.getStartsAt(), m.startsAt()).toMinutes());
                    if (diffMin > RESCHEDULE_THRESHOLD_MIN) {
                        log.info("[PRE-MATCH] startsAt shifted {}min for slipId={}", diffMin, slipId);
                        slip.setStartsAt(m.startsAt());
                        slipRepo.save(slip);
                        return true; // caller reschedules or takes immediate snapshot
                    }
                }

                slip.setSnapshotExtraData(m.extraData());
                slip.setSnapshotTakenAt(OffsetDateTime.now());
                slipRepo.save(slip);
                log.info("[PRE-MATCH] snapshot saved slipId={}", slipId);
                return false;
            }));

            // Step 4: handle startsAt shift outside TX (no new I/O needed — reuse m)
            if (shifted) {
                BetSlipEntity refreshed = slipRepo.findById(slipId).orElse(null);
                if (refreshed == null) return;
                Instant newTrigger = refreshed.getStartsAt().minus(SNAPSHOT_OFFSET_MIN, ChronoUnit.MINUTES);
                if (newTrigger.isAfter(Instant.now())) {
                    scheduleFor(refreshed); // future window — reschedule
                } else if (refreshed.getStartsAt().isAfter(Instant.now())) {
                    // Match moved earlier, we're inside the window — use the data we already have
                    tx.execute(status -> {
                        BetSlipEntity s = slipRepo.findById(slipId).orElse(null);
                        if (s == null || s.getSnapshotTakenAt() != null) return null;
                        s.setSnapshotExtraData(m.extraData());
                        s.setSnapshotTakenAt(OffsetDateTime.now());
                        slipRepo.save(s);
                        log.info("[PRE-MATCH] snapshot saved (moved earlier) slipId={}", slipId);
                        return null;
                    });
                }
                // If match already started — skip
            }
        } catch (Exception e) {
            log.warn("[PRE-MATCH] takeSnapshot failed slipId={}: {}", slipId, e.getMessage());
        }
    }

    // ── HTTP fetch ────────────────────────────────────────────────────────────

    @Nullable
    private ParsedMatchDto fetchMatch(String matchUrl) {
        BookmakerType bk;
        try { bk = UrlParser.parseBookmaker(matchUrl); }
        catch (Exception e) { return null; }

        ParsedUrlIds ids = UrlParser.extractIds(matchUrl, bk);
        if (ids.tournamentId() == null) return null;

        BookmakerParser parser;
        try { parser = parserFactory.getParser(bk); }
        catch (Exception e) { return null; }

        try {
            fetchSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            ParseResult<List<ParsedMatchDto>> result = parser.fetchMatches(ids.tournamentId());
            if (!result.success() || result.data() == null) return null;
            String matchId = ids.matchId();
            return result.data().stream()
                    .filter(m -> matchId == null || matchId.equals(m.id()))
                    .findFirst()
                    .orElse(null);
        } finally {
            fetchSemaphore.release();
        }
    }
}
