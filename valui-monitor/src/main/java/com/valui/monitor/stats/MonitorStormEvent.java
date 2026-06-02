package com.valui.monitor.stats;

import org.springframework.context.ApplicationEvent;

public class MonitorStormEvent extends ApplicationEvent {

    private final long errors;
    private final long total;
    private final int  consecutiveWindows;

    public MonitorStormEvent(Object source, long errors, long total, int consecutiveWindows) {
        super(source);
        this.errors             = errors;
        this.total              = total;
        this.consecutiveWindows = consecutiveWindows;
    }

    public long getErrors()             { return errors; }
    public long getTotal()              { return total; }
    public int  getConsecutiveWindows() { return consecutiveWindows; }
    public double getRate()             { return total > 0 ? errors * 100.0 / total : 0; }
}
