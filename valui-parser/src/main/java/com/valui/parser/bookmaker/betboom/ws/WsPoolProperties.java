package com.valui.parser.bookmaker.betboom.ws;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "ws.pool")
public class WsPoolProperties {
    private String url;
    private Map<String, String> headers = new LinkedHashMap<>();
    private int minSize = 2;
    private int maxSize = 6;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private int initialBuffer = 65536;
    private Duration readyTimeout = Duration.ofSeconds(10);
    private Duration backoffBase = Duration.ofMillis(300);
    private Duration backoffMax = Duration.ofSeconds(10);
    // Hygiene recycle: BetBoom keeps server-side subscriptions alive on a connection
    // indefinitely, so a long-lived slot accumulates more and more background push traffic
    // the longer it goes without a fresh connection. Recycling well before that becomes
    // noticeable bounds both the subscription set and the backlog any one connection has to
    // carry — same idea as HttpClientConfig's Reactor Netty maxLifeTime/maxIdleTime.
    private int recycleAfterUses = 500;
    private Duration maxConnectionAge = Duration.ofMinutes(20);
    // Idle drain: with 6 connections serving ~60+ tournaments' worth of live subscriptions,
    // most slots sit idle in the free pool most of the time between borrows — but BetBoom
    // keeps pushing odds updates for every subscription regardless of whether anyone's
    // reading. Left alone, that backlog is only ever cleared reactively (at the next borrow,
    // or at hygiene recycle) — in production this meant the per-connection 2000-frame cap
    // (see WsClient.MAX_INBOX) stayed permanently saturated with frames nobody will ever
    // read, chronically dropping the newest of them. Proactively draining idle slots' inboxes
    // keeps this near-empty in steady state, so a nonzero backlog again means something
    // (active contention), not "the pool has been idle for a few seconds".
    private Duration idleDrainInterval = Duration.ofSeconds(15);
    private Warmup warmup = new Warmup();

    @Data
    public static class Warmup {
        private boolean enabled = true;
        private int minReady = 2;
        private Duration timeout = Duration.ofSeconds(15);
    }
}
