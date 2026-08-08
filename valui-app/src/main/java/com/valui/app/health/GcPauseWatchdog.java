package com.valui.app.health;

import com.sun.management.GarbageCollectionNotificationInfo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * Logs a WARN whenever a single GC pause exceeds {@link #WARN_THRESHOLD_MS}.
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
public class GcPauseWatchdog {

    private static final long WARN_THRESHOLD_MS = 1_000;

    @PostConstruct
    void register() {
        int attached = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gcBean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(this::onNotification, null, null);
                attached++;
            }
        }
        log.info("[GC] Pause watchdog attached to {} collector(s), warn-threshold={}ms", attached, WARN_THRESHOLD_MS);
    }

    private void onNotification(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
            return;
        }
        GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        long durationMs = info.getGcInfo().getDuration();
        if (durationMs >= WARN_THRESHOLD_MS) {
            log.warn("[GC] Long pause: {} ({}) took {}ms — likely cause of any 'Fetch budget exceeded' " +
                    "burst at this timestamp", info.getGcName(), info.getGcAction(), durationMs);
        } else {
            log.trace("[GC] {} ({}) took {}ms", info.getGcName(), info.getGcAction(), durationMs);
        }
    }
}
