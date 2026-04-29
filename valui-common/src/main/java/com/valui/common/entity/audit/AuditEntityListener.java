package com.valui.common.entity.audit;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.OffsetDateTime;

public class AuditEntityListener {

    @PrePersist
    public void prePersist(Object entity) {
        OffsetDateTime now = OffsetDateTime.now();
        if (entity instanceof HasCreatedAt e && e.getCreatedAt() == null) {
            e.setCreatedAt(now);
        }
        if (entity instanceof HasUpdatedAt e && e.getUpdatedAt() == null) {
            e.setUpdatedAt(now);
        }
    }

    @PreUpdate
    public void preUpdate(Object entity) {
        if (entity instanceof HasUpdatedAt e) {
            e.setUpdatedAt(OffsetDateTime.now());
        }
    }
}
