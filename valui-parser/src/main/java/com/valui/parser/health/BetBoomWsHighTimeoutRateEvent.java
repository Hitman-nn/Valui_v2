package com.valui.parser.health;

import org.springframework.context.ApplicationEvent;

public class BetBoomWsHighTimeoutRateEvent extends ApplicationEvent {

    private final long count;
    private final long windowMinutes;

    public BetBoomWsHighTimeoutRateEvent(Object source, long count, long windowMinutes) {
        super(source);
        this.count         = count;
        this.windowMinutes = windowMinutes;
    }

    public long getCount()         { return count; }
    public long getWindowMinutes() { return windowMinutes; }
}
