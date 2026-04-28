package com.valui.user.security;

import java.util.UUID;

/**
 * Common identity contract stored in the Spring SecurityContext.
 * Implemented by {@code ValuiPrincipal} (REST/JWT) so cross-cutting concerns
 * like {@code AuditAspect} can read userId/telegramId without depending on
 * the admin module.
 */
public interface SecurityPrincipal {

    UUID userId();

    Long telegramId();
}
