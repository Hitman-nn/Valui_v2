package com.valui.user.api;

import com.valui.common.entity.DetectedEventEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Port interface: stable contract for detected-event data access, owned by valui-user.
 * Consumed by valui-monitor.
 */
public interface DetectedEventPortService {

    DetectedEventEntity save(DetectedEventEntity entity);

    long countByControllerId(UUID controllerId);

    List<String> findExternalIdsByControllerIdSince(UUID controllerId, OffsetDateTime cutoff);
}
