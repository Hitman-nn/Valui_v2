package com.valui.user.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cryptobot")
public record CryptoBotProperties(
    String apiToken,
    String apiUrl,
    int invoiceExpiresInSeconds
) {}
