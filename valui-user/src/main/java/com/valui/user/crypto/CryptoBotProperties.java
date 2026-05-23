package com.valui.user.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cryptobot")
public record CryptoBotProperties(
    String apiToken,
    String apiUrl,
    int invoiceExpiresInSeconds
) {
    public CryptoBotProperties {
        if (apiUrl == null || apiUrl.isBlank()) apiUrl = "https://pay.crypt.bot/api";
        if (invoiceExpiresInSeconds <= 0) invoiceExpiresInSeconds = 3600;
    }
}
