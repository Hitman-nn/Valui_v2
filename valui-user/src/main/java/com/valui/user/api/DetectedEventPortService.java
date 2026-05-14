package com.valui.user.api;

import com.valui.common.entity.DetectedEventEntity;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
    boolean insertIfAbsent(UUID id, UUID controllerId, String externalId, String title, String url, String extraData);

    long countByControllerId(UUID controllerId);

    /**
     * Looks up the detected-event ID by its natural key.
     * Returns empty if the event hasn't been persisted yet (rare replay scenario).
     */
    java.util.Optional<UUID> findIdByControllerIdAndExternalId(UUID controllerId, String externalId);

    /**
     * Returns a JPA proxy reference. Must only be used to establish a FK association
     * on a new entity within an active transaction — never dereference the proxy.
     */
    DetectedEventEntity getReferenceById(UUID id);

    /** Returns a map of controllerId → event count for all given IDs in a single query. */
    Map<UUID, Long> countByControllerIdIn(Collection<UUID> controllerIds);

    List<String> findExternalIdsByControllerIdSince(UUID controllerId, OffsetDateTime cutoff);

    /** Returns ALL known external event IDs for the controller (no time limit). Used by dedup sync. */
    List<String> findAllExternalIdsByControllerId(UUID controllerId);
}
