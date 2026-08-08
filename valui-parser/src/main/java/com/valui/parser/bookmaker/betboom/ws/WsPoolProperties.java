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
    private Warmup warmup = new Warmup();

    @Data
    public static class Warmup {
        private boolean enabled = true;
        private int minReady = 2;
        private Duration timeout = Duration.ofSeconds(15);
    }
}
