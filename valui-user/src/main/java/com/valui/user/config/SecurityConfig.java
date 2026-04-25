package com.valui.user.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Activates Spring Security method-level annotations (@PreAuthorize, @PostAuthorize).
 * Required for banUser/unbanUser to enforce ADMIN-only access.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
}
