package com.valui.admin.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "valui.auth")
public record AuthProperties(
    /**
     * Shared secret between the Telegram bot and this API.
     * The bot passes it in POST /api/v1/auth/token as an extra authentication layer.
     */
    String botSecret,

    /**
     * BCrypt hash of the admin panel password (POST /api/v1/auth/admin/login).
     * Set via VALUI_AUTH_ADMIN_PASSWORD env var. Generate with:
     *   htpasswd -bnBC 10 "" your_password | tr -d ':\n'
     * Keep separate from botSecret.
     */
    String adminPassword
) {}
