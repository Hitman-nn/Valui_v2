package com.valui.notify.stats;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process counters reset every summary interval.
 * All methods are thread-safe via AtomicLong / ConcurrentHashMap.
 */
@Component
public class NotificationStats {

    private final AtomicLong sent             = new AtomicLong();
    private final AtomicLong dlqRetry         = new AtomicLong();
    private final AtomicLong dlqFinal         = new AtomicLong();
    private final AtomicLong rateLimitBackoff = new AtomicLong();
    private final AtomicLong vkSent           = new AtomicLong();
    private final AtomicLong vkSkipped        = new AtomicLong();
    private final AtomicLong vkDlqRetry       = new AtomicLong();
    private final AtomicLong vkDlqFinal       = new AtomicLong();

    private final ConcurrentHashMap<String, AtomicLong> sentByBookmaker = new ConcurrentHashMap<>();

    public void incSent() { sent.incrementAndGet(); }

    public void incSent(String bookmaker) {
        sent.incrementAndGet();
        if (bookmaker != null) {
            sentByBookmaker.computeIfAbsent(bookmaker, k -> new AtomicLong()).incrementAndGet();
        }
    }

    public void incDlqRetry()         { dlqRetry.incrementAndGet(); }
    public void incDlqFinal()         { dlqFinal.incrementAndGet(); }
    public void incRateLimitBackoff() { rateLimitBackoff.incrementAndGet(); }
    public void incVkSent()           { vkSent.incrementAndGet(); }
    public void incVkSkipped()        { vkSkipped.incrementAndGet(); }
    public void incVkDlqRetry()       { vkDlqRetry.incrementAndGet(); }
    public void incVkDlqFinal()       { vkDlqFinal.incrementAndGet(); }

    /** Returns value and resets counter to 0. */
    public long drainSent()             { return sent.getAndSet(0); }
    public long drainDlqRetry()         { return dlqRetry.getAndSet(0); }
    public long drainDlqFinal()         { return dlqFinal.getAndSet(0); }
    public long drainRateLimitBackoff() { return rateLimitBackoff.getAndSet(0); }
    public long drainVkSent()           { return vkSent.getAndSet(0); }
    public long drainVkSkipped()        { return vkSkipped.getAndSet(0); }
    public long drainVkDlqRetry()       { return vkDlqRetry.getAndSet(0); }
    public long drainVkDlqFinal()       { return vkDlqFinal.getAndSet(0); }

    /** Returns per-bookmaker sent counts and resets them. Sorted by count descending. */
    public Map<String, Long> drainSentByBookmaker() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        sentByBookmaker.forEach((bk, counter) -> {
            long val = counter.getAndSet(0);
            if (val > 0) snapshot.put(bk, val);
        });
        return snapshot.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }
}
