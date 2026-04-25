package com.valui.admin.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "valui.jwt")
public record JwtProperties(
    String secret,
    long accessTokenTtlSeconds,
    long refreshTokenTtlSeconds
) {}
