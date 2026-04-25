package com.valui.admin.security;

import java.util.UUID;

/**
 * Immutable security principal stored in {@link org.springframework.security.core.context.SecurityContext}.
 * Injected into controller parameters via {@link CurrentUser} + {@link CurrentUserArgumentResolver}.
 */
public record ValuiPrincipal(
    UUID userId,
    Long telegramId,
    String role,
    String plan
) {}
