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
    boolean insertIfAbsent(UUID id, UUID controllerId, String externalId, String title, String url, String extraData,
                           java.time.OffsetDateTime expiresAt);

    long countByControllerId(UUID controllerId);

    /**
     * Looks up the detected-event ID by its natural key.
     * Returns empty if the event hasn't been persisted yet (rare replay scenario).
     */
    java.util.Optional<UUID> findIdByControllerIdAndExternalId(UUID controllerId, String externalId);

    /** Returns the latest extraData JSON for the event, for race-condition checks at watch-button tap time. */
    java.util.Optional<String> findExtraDataByControllerIdAndExternalId(UUID controllerId, String externalId);

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

    /**
     * Deletes up to {@code batchSize} expired detected_events rows (expires_at < threshold).
     * Returns the number of deleted rows; 0 means no more expired rows remain.
     */
    int deleteExpiredBatch(OffsetDateTime threshold, int batchSize);

    org.springframework.data.domain.Page<DetectedEventEntity> findRecentByControllerId(
            UUID controllerId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<DetectedEventEntity> searchByControllerIdAndTitle(
            UUID controllerId, String query, org.springframework.data.domain.Pageable pageable);

    java.util.Optional<DetectedEventEntity> findByIdWithController(UUID eventId);
}
