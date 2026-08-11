package com.valui.app.health;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.valui.notify.service.AdminNotificationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logs a WARN whenever a single GC pause exceeds {@link #WARN_THRESHOLD_MS}, and pushes a
 * Telegram alert to the admin chat for genuinely severe pauses ({@link #ALERT_THRESHOLD_MS}) —
 * the "about to crash" early-warning signal: a JVM sitting in a long stop-the-world pause is
 * exactly the state that precedes an OOM-kill or an
 * {@code -XX:+ExitOnOutOfMemoryError} exit, so catching it here means an alert can go out before
 * the process actually dies rather than only after (which {@link AdminNotificationService}'s
 * other callers, and any external health-check watchdog, can only observe in hindsight).
 *
 * <p>Root cause of a recurring, previously-unexplained pattern in production: 20-30 controllers
 * across the same bookmaker would all hit "Fetch budget exceeded" at the exact same millisecond
 * timestamp, every ~30-60 minutes. That signature — many unrelated controllers timing out
 * simultaneously rather than individually under load — is the classic symptom of a JVM
 * stop-the-world pause freezing every thread at once, not organic per-request slowness. There
 * was no way to confirm this from the application log alone; {@code -Xlog:gc} would show it but
 * writes to a separate file nobody was correlating against app-level timestamps. This surfaces
 * the same signal directly in the main log stream, at the same timestamp granularity as
 * everything else, so the next time a "Fetch budget exceeded" burst happens, the GC pause that
 * caused it (if any) is right there in the same log, not something to infer after the fact.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GcPauseWatchdog {

    private static final long WARN_THRESHOLD_MS = 1_000;

    /** Pauses at or above this are the "danger" tier — proactively alert, not just log. */
    private static final long ALERT_THRESHOLD_MS = 3_000;

    /** Minimum gap between two Telegram alerts, so a storm of long pauses (e.g. sustained heap
     *  pressure triggering back-to-back full GCs) sends one alert, not one per pause. */
    private static final Duration ALERT_COOLDOWN = Duration.ofMinutes(5);

    private final AdminNotificationService adminNotificationService;

    // Sentinel for "never alerted yet" — deliberately not 0: real System.currentTimeMillis()
    // values are always huge positive numbers so 0 would happen to work too, but that's an
    // accident of what "now" looks like in production, not a real invariant (a test driving
    // nowMs from 0 exposed exactly this — see GcPauseWatchdogTest). Halved MIN_VALUE, not
    // MIN_VALUE itself, so `nowMs - previous` below can never overflow for any real nowMs.
    private static final long NEVER_ALERTED = Long.MIN_VALUE / 2;

    private final AtomicLong lastAlertAtMs = new AtomicLong(NEVER_ALERTED);

    @PostConstruct
    void register() {
        int attached = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gcBean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(this::onNotification, null, null);
                attached++;
            }
        }
        log.info("[GC] Pause watchdog attached to {} collector(s), warn-threshold={}ms alert-threshold={}ms",
                attached, WARN_THRESHOLD_MS, ALERT_THRESHOLD_MS);
    }

    private void onNotification(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
            return;
        }
        GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        handlePause(info.getGcName(), info.getGcAction(), info.getGcInfo().getDuration(), System.currentTimeMillis());
    }

    /**
     * The actual WARN/alert decision, split out from JMX event parsing above so it's testable
     * without needing to construct a real {@link GarbageCollectionNotificationInfo} (which
     * requires a live {@link CompositeData} from an actual GC notification — impractical to
     * fabricate in a unit test). {@code nowMs} is a parameter rather than read internally so
     * cooldown behavior can be tested deterministically instead of racing the system clock.
     */
    void handlePause(String gcName, String gcAction, long durationMs, long nowMs) {
        if (durationMs >= WARN_THRESHOLD_MS) {
            log.warn("[GC] Long pause: {} ({}) took {}ms — likely cause of any 'Fetch budget exceeded' " +
                    "burst at this timestamp", gcName, gcAction, durationMs);
        } else {
            log.trace("[GC] {} ({}) took {}ms", gcName, gcAction, durationMs);
            return;
        }

        if (durationMs >= ALERT_THRESHOLD_MS) {
            maybeAlert(gcName, gcAction, durationMs, nowMs);
        }
    }

    private void maybeAlert(String gcName, String gcAction, long durationMs, long nowMs) {
        long previous = lastAlertAtMs.get();
        if (nowMs - previous < ALERT_COOLDOWN.toMillis()) {
            log.debug("[GC] Alert suppressed (cooldown): {} ({}) took {}ms", gcName, gcAction, durationMs);
            return;
        }
        // CAS, not a plain set: under a genuine GC storm multiple long pauses can be reported
        // in quick succession from the notification thread; only the first to win the race
        // actually sends, the rest just observe the cooldown on their next check.
        if (lastAlertAtMs.compareAndSet(previous, nowMs)) {
            adminNotificationService.alertAdmin(
                    "⚠️ *Долгая пауза GC*\n" +
                    gcName + " (" + gcAction + ") — *" + durationMs + "ms*\n\n" +
                    "Это состояние обычно предшествует OOM/падению — стоит проверить heap " +
                    "(`/actuator/metrics/jvm.memory.used`) и, если проблема повторяется, " +
                    "нагрузку на JVM.");
        }
    }
}
