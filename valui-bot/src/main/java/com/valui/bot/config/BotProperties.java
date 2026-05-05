package com.valui.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "valui.bot")
public record BotProperties(
    String token,
    String username,
    String paymentBaseUrl,
    BotMode mode,
    String webhookUrl,
    String secretToken,
    List<String> allowedIps,
    BotProxyProperties proxy,
    int staleThresholdDays
) {
    public BotProperties {
        if (mode == null)  mode = BotMode.LONG_POLLING;
        if (allowedIps == null || allowedIps.isEmpty())
            allowedIps = List.of("149.154.0.0/16", "91.108.0.0/16");
        if (staleThresholdDays <= 0) staleThresholdDays = 30;
    }

    public record BotProxyProperties(
        boolean enabled,
        String type,       // HTTP | SOCKS4 | SOCKS5
        String host,
        int port,
        String username,
        String password
    ) {}
}
