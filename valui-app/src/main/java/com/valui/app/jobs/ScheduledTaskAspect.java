package com.valui.app.jobs;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Intercepts every {@code @Scheduled} method and records its last execution
 * (start time, duration, success/error) into {@link ScheduledTaskTracker}.
 *
 * Key format: {@code ClassName.methodName} — e.g. {@code DedupSyncScheduler.sync}.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class ScheduledTaskAspect {

    private final ScheduledTaskTracker tracker;

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object track(ProceedingJoinPoint pjp) throws Throwable {
        String key = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            tracker.record(key, System.currentTimeMillis() - start, true);
            return result;
        } catch (Throwable t) {
            tracker.record(key, System.currentTimeMillis() - start, false);
            throw t;
        }
    }
}
