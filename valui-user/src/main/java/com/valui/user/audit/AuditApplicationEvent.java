package com.valui.user.audit;

import com.valui.common.event.AuditEvent;
import org.springframework.context.ApplicationEvent;

/**
 * Spring ApplicationEvent wrapping an AuditEvent.
 * Published by AuditAspect; consumed by AuditEventListener after DB commit.
 */
public class AuditApplicationEvent extends ApplicationEvent {

    private final AuditEvent auditEvent;

    public AuditApplicationEvent(Object source, AuditEvent auditEvent) {
        super(source);
        this.auditEvent = auditEvent;
    }

    public AuditEvent getAuditEvent() {
        return auditEvent;
    }
}
