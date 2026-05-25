package com.valui.admin.auth;

import org.springframework.context.ApplicationEvent;

/**
 * Published when the admin login rate limiter blocks an IP after too many failed attempts.
 * Consumed by IncidentAlertListener in valui-app to send a Telegram alert.
 */
public class BruteForceAlertEvent extends ApplicationEvent {

    private final String ip;
    private final long   attempts;
    private final String path;

    public BruteForceAlertEvent(Object source, String ip, long attempts, String path) {
        super(source);
        this.ip       = ip;
        this.attempts = attempts;
        this.path     = path;
    }

    public String getIp()       { return ip; }
    public long   getAttempts() { return attempts; }
    public String getPath()     { return path; }
}
