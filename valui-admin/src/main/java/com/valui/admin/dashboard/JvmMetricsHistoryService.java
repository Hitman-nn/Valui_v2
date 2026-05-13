package com.valui.admin.dashboard;

import com.valui.admin.dashboard.dto.JvmDataPointDto;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Collects JVM metrics every minute and keeps rolling history.
 *
 * minuteBuffer — 1-min resolution, capacity 1 440 (24 h)
 * hourBuffer   — 1-hour resolution, capacity 720  (30 d)
 */
@Service
@RequiredArgsConstructor
public class JvmMetricsHistoryService {

    private static final int MINUTE_CAP = 1_440;
    private static final int HOUR_CAP   = 720;

    private final MeterRegistry meterRegistry;

    private final ReentrantLock lock = new ReentrantLock();

    private final Deque<JvmDataPointDto> minuteBuffer = new ArrayDeque<>();
    private final Deque<JvmDataPointDto> hourBuffer   = new ArrayDeque<>();
    private int minutesInCurrentHour = 0;

    @PostConstruct
    public void init() {
        collectAndStore();
    }

    @Scheduled(fixedRate = 60_000)
    public void collectAndStore() {
        JvmDataPointDto point = snapshot();

        lock.lock();
        try {
            minuteBuffer.addLast(point);
            if (minuteBuffer.size() > MINUTE_CAP) minuteBuffer.pollFirst();

            minutesInCurrentHour++;
            if (minutesInCurrentHour >= 60) {
                minutesInCurrentHour = 0;
                hourBuffer.addLast(point);
                if (hourBuffer.size() > HOUR_CAP) hourBuffer.pollFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    public List<JvmDataPointDto> getHistory(String range) {
        return switch (range) {
            case "1h"  -> lockedTail(minuteBuffer, 60);
            case "24h" -> lockedTail(minuteBuffer, MINUTE_CAP);
            case "7d"  -> lockedTail(hourBuffer, 168);
            case "30d" -> lockedTail(hourBuffer, HOUR_CAP);
            default    -> lockedTail(minuteBuffer, 60);
        };
    }

    private JvmDataPointDto snapshot() {
        long heapUsed  = (long) gauge("jvm.memory.used",  "area", "heap");
        long heapMax   = (long) gauge("jvm.memory.max",   "area", "heap");
        long nonHeap   = (long) gauge("jvm.memory.used",  "area", "nonheap");
        int  threads   = (int)  gauge("jvm.threads.live");
        double cpu     = gauge("system.cpu.usage") * 100;

        return new JvmDataPointDto(
                Instant.now().toEpochMilli(),
                heapUsed / 1_048_576,
                heapMax  / 1_048_576,
                nonHeap  / 1_048_576,
                threads,
                Math.round(cpu * 10.0) / 10.0
        );
    }

    private <T> List<T> lockedTail(Deque<T> deque, int n) {
        lock.lock();
        try {
            List<T> list = new ArrayList<>(deque);
            int from = Math.max(0, list.size() - n);
            return new ArrayList<>(list.subList(from, list.size()));
        } finally {
            lock.unlock();
        }
    }

    private double gauge(String name) {
        try { return meterRegistry.get(name).gauge().value(); } catch (Exception e) { return 0; }
    }

    private double gauge(String name, String tagKey, String tagValue) {
        try { return meterRegistry.get(name).tag(tagKey, tagValue).gauge().value(); } catch (Exception e) { return 0; }
    }
}
