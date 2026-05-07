package com.valui.notify.stats;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process counters reset every summary interval.
 * All methods are thread-safe via AtomicLong.
 */
@Component
public class NotificationStats {

    private final AtomicLong sent            = new AtomicLong();
    private final AtomicLong dlqRetry        = new AtomicLong();
    private final AtomicLong dlqFinal        = new AtomicLong();
    private final AtomicLong rateLimitBackoff = new AtomicLong();

    public void incSent()            { sent.incrementAndGet(); }
    public void incDlqRetry()        { dlqRetry.incrementAndGet(); }
    public void incDlqFinal()        { dlqFinal.incrementAndGet(); }
    public void incRateLimitBackoff() { rateLimitBackoff.incrementAndGet(); }

    /** Returns value and resets counter to 0. */
    public long drainSent()             { return sent.getAndSet(0); }
    public long drainDlqRetry()         { return dlqRetry.getAndSet(0); }
    public long drainDlqFinal()         { return dlqFinal.getAndSet(0); }
    public long drainRateLimitBackoff() { return rateLimitBackoff.getAndSet(0); }
}
