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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreMatchOddsService {

    private static final long SNAPSHOT_OFFSET_MIN = 30;
    private static final long RESCHEDULE_THRESHOLD_MIN = 10;

    private final BetSlipRepository slipRepo;
    private final ParserFactory parserFactory;
    private final TransactionTemplate tx;

    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
            r -> { Thread t = new Thread(r, "pre-match-" + threadCounter.getAndIncrement()); t.setDaemon(true); return t; });

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Async: fetches the match from the parser, stores startsAt + initial odds in the slip,
     * then schedules the snapshot task. Called right after a bet is saved.
     */
    @Async
    public void register(UUID slipId) {
        BetSlipEntity slip = slipRepo.findById(slipId).orElse(null);
        if (slip == null || slip.getMatchUrl() == null || slip.getMatchUrl().isBlank()) return;
        try {
            ParsedMatchDto match = fetchMatch(slip.getMatchUrl());
            if (match == null) {
                log.debug("[PRE-MATCH] match not found at registration, slipId={}", slipId);
                return;
            }
            Instant startsAt = match.startsAt();
            if (startsAt == null || startsAt.equals(Instant.EPOCH)) {
                log.debug("[PRE-MATCH] startsAt unknown for slipId={}", slipId);
                return;
            }
            slip.setStartsAt(startsAt);
            slip.setInitialExtraData(match.extraData());
            slipRepo.save(slip);
            scheduleFor(slip);
        } catch (Exception e) {
            log.warn("[PRE-MATCH] register failed slipId={}: {}", slipId, e.getMessage());
        }
    }

    /** Cancel the scheduled snapshot for this slip (e.g. when bet is deleted). */
    public void cancel(UUID slipId) {
        ScheduledFuture<?> f = pending.remove(slipId);
        if (f != null) f.cancel(false);
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

    // ── internals ─────────────────────────────────────────────────────────────

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
            ParsedMatchDto match = tx.execute(status -> {
                BetSlipEntity slip = slipRepo.findById(slipId).orElse(null);
                if (slip == null || slip.getSnapshotTakenAt() != null || slip.getMatchUrl() == null)
                    return null;

                ParsedMatchDto m = fetchMatch(slip.getMatchUrl());
                if (m == null) {
                    log.info("[PRE-MATCH] match not found at snapshot time, slipId={} — skipping", slipId);
                    return null;
                }

                if (!m.startsAt().equals(Instant.EPOCH) && slip.getStartsAt() != null) {
                    long diffMin = Math.abs(Duration.between(slip.getStartsAt(), m.startsAt()).toMinutes());
                    if (diffMin > RESCHEDULE_THRESHOLD_MIN) {
                        log.info("[PRE-MATCH] startsAt shifted {}min for slipId={}", diffMin, slipId);
                        slip.setStartsAt(m.startsAt());
                        slipRepo.save(slip);
                        return m; // signal to reschedule / take immediately after tx
                    }
                }

                slip.setSnapshotExtraData(m.extraData());
                slip.setSnapshotTakenAt(OffsetDateTime.now());
                slipRepo.save(slip);
                log.info("[PRE-MATCH] snapshot saved slipId={}", slipId);
                return null; // done
            });

            // If match was rescheduled (tx returned the fresh match), decide what to do
            if (match != null) {
                BetSlipEntity refreshed = slipRepo.findById(slipId).orElse(null);
                if (refreshed == null) return;
                Instant newTrigger = refreshed.getStartsAt().minus(SNAPSHOT_OFFSET_MIN, ChronoUnit.MINUTES);
                if (newTrigger.isAfter(Instant.now())) {
                    scheduleFor(refreshed); // future window — reschedule
                } else if (refreshed.getStartsAt().isAfter(Instant.now())) {
                    // Match moved earlier, we're inside the window — snapshot immediately
                    tx.execute(status -> {
                        BetSlipEntity s = slipRepo.findById(slipId).orElse(null);
                        if (s == null || s.getSnapshotTakenAt() != null) return null;
                        s.setSnapshotExtraData(match.extraData());
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

        ParseResult<List<ParsedMatchDto>> result = parser.fetchMatches(ids.tournamentId());
        if (!result.success() || result.data() == null) return null;

        String matchId = ids.matchId();
        return result.data().stream()
                .filter(m -> matchId == null || matchId.equals(m.id()))
                .findFirst()
                .orElse(null);
    }
}
