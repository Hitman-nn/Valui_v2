package com.valui.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "valui.bot")
public record BotProperties(String token, String username) {}
