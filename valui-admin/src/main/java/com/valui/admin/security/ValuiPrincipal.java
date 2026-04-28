package com.valui.admin.security;

import com.valui.user.security.SecurityPrincipal;

import java.util.UUID;

/**
 * Immutable security principal stored in {@link org.springframework.security.core.context.SecurityContext}.
 * Injected into controller parameters via {@link CurrentUser} + {@link CurrentUserArgumentResolver}.
 * Implements {@link SecurityPrincipal} so cross-cutting concerns (e.g. AuditAspect) can read
 * identity fields without a hard dep on the admin module.
 */
public record ValuiPrincipal(
    UUID userId,
    Long telegramId,
    String role,
    String plan
) implements SecurityPrincipal {}
