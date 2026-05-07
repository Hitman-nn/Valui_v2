package com.valui.user.api;

import com.valui.common.kafka.AuditEntryMessage;

import java.util.List;

/**
 * Port interface: stable contract for persisting audit log entries, owned by valui-user.
 * Consumed by valui-notify; encapsulates AuditEntryMessage → AuditLogEntity mapping.
 */
public interface AuditLogPortService {

    void persistBatch(List<AuditEntryMessage> messages);
}
