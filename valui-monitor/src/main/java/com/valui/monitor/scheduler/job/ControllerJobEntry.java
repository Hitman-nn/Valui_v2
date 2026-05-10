package com.valui.monitor.scheduler.job;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * Thin {@link Delayed} wrapper over a controller ID + scheduled run time.
 * Placed in the global {@link java.util.concurrent.DelayQueue}; becomes available
 * to the dispatcher exactly when {@code nextRunAt} is reached.
 */
public record ControllerJobEntry(UUID controllerId, Instant nextRunAt) implements Delayed {

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(Duration.between(Instant.now(), nextRunAt));
    }

    @Override
    public int compareTo(Delayed other) {
        if (other instanceof ControllerJobEntry o) {
            return this.nextRunAt.compareTo(o.nextRunAt);
        }
        long diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
        return Long.signum(diff);
    }
}
