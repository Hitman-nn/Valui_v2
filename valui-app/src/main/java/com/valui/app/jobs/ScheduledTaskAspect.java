package com.valui.app.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
            tracker.record(key, System.currentTimeMillis() - start, true, null);
            return result;
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            // Without this, the only log trace of a @Scheduled failure was Spring's default
            // TaskUtils.LoggingErrorHandler — which logs the stack trace but not which task
            // (there's no taskKey field on it), making it impossible to tell which of the many
            // @Scheduled methods in this codebase actually failed without reading the trace.
            log.error("[SCHEDULED-TASK] {} failed after {}ms: {}", key, System.currentTimeMillis() - start, msg, t);
            tracker.record(key, System.currentTimeMillis() - start, false, msg);
            throw t;
        }
    }
}
