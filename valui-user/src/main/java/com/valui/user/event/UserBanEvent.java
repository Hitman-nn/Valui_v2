package com.valui.user.event;

import java.util.UUID;

/**
 * Published via ApplicationEventPublisher when an admin bans or unbans a user.
 * Handled by AuditEventListener to persist the action to audit_log.
 */
public record UserBanEvent(
    UUID targetUserId,
    String action,          // "BAN" or "UNBAN"
    UUID performedByUserId  // null if performed by system / not yet authenticated
) {}
