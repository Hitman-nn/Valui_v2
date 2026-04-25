package com.valui.admin.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "valui.auth")
public record AuthProperties(
    /**
     * Shared secret between the Telegram bot and this API.
     * The bot passes it in POST /api/v1/auth/token as an extra authentication layer.
     */
    String botSecret
) {}
