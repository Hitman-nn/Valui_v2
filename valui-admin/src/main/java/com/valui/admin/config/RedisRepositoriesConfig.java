package com.valui.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

/**
 * Explicitly scoped to the admin auth package to avoid interfering with
 * JPA repository scanning in valui-user (com.valui.user.repository).
 */
@Configuration
@EnableRedisRepositories(basePackages = "com.valui.admin.auth.redis")
public class RedisRepositoriesConfig {}
