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

    /**
     * Inserts the event if it doesn't already exist (controller_id, event_external_id unique).
     * Uses ON CONFLICT DO NOTHING — no exception, transaction stays clean on duplicate.
     *
     * @return true if actually inserted, false if the row already existed
     */
    boolean insertIfAbsent(UUID id, UUID controllerId, String externalId, String title, String url);

    long countByControllerId(UUID controllerId);

    List<String> findExternalIdsByControllerIdSince(UUID controllerId, OffsetDateTime cutoff);

    /** Returns ALL known external event IDs for the controller (no time limit). Used by dedup sync. */
    List<String> findAllExternalIdsByControllerId(UUID controllerId);
}
