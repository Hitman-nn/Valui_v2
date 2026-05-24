package com.valui.notify.util;

import java.util.UUID;

/** Shared utilities for Kafka notification consumers. */
public final class KafkaNotifyUtil {

    private KafkaNotifyUtil() {}

    /**
     * Parses a nullable/blank UUID string without throwing.
     * Returns {@code null} if the input is null, blank, or not a valid UUID.
     */
    public static UUID parseLogId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }

    /**
     * Derives a deterministic VK {@code random_id} from a notification log UUID.
     * VK deduplicates messages with the same {@code random_id} within a conversation,
     * so retries of the same notification are silently ignored by VK itself.
     *
     * @return a stable non-zero long, or 0 if {@code logIdStr} is absent/invalid
     *         (0 tells the VK API to skip deduplication)
     */
    public static long vkRandomId(String logIdStr) {
        if (logIdStr == null || logIdStr.isBlank()) return 0;
        try {
            UUID id = UUID.fromString(logIdStr);
            long v = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
            return v == 0 ? 1 : v;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}
