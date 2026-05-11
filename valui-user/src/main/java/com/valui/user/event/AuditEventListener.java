package com.valui.user.event;

import com.valui.common.entity.AuditLogEntity;
import com.valui.user.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditLogRepository auditLogRepository;

    @Async
    @EventListener
    public void onUserBan(UserBanEvent event) {
        try {
            AuditLogEntity entry = AuditLogEntity.builder()
                .action(event.action())
                .entityType("User")
                .entityId(event.targetUserId())
                .details("{\"performedBy\":\"" + event.performedByUserId() + "\"}")
                .build();
            auditLogRepository.save(entry);
            log.debug("Audit recorded: action={} targetUser={}", event.action(), event.targetUserId());
        } catch (Exception e) {
            log.error("Failed to persist audit entry for action={} user={}", event.action(), event.targetUserId(), e);
        }
    }
}
