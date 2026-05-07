package com.valui.user.service;

import com.valui.common.entity.AuditLogEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.kafka.AuditEntryMessage;
import com.valui.user.api.AuditLogPortService;
import com.valui.user.repository.AuditLogRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditLogPortServiceImpl implements AuditLogPortService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void persistBatch(List<AuditEntryMessage> messages) {
        List<AuditLogEntity> entries = messages.stream()
                .map(this::toEntity)
                .toList();
        auditLogRepository.saveAll(entries);
    }

    private AuditLogEntity toEntity(AuditEntryMessage msg) {
        UUID userId = parseUuid(msg.userId());
        UserEntity userRef = userId != null ? userRepository.getReferenceById(userId) : null;
        return AuditLogEntity.builder()
                .user(userRef)
                .action(msg.action())
                .entityType(msg.entityType())
                .entityId(parseUuid(msg.entityId()))
                .details(msg.details())
                .ipAddress(msg.ipAddress())
                .build();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
