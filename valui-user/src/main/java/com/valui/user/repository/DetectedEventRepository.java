package com.valui.user.repository;

import com.valui.common.entity.DetectedEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DetectedEventRepository extends JpaRepository<DetectedEventEntity, UUID> {

    Optional<DetectedEventEntity> findByControllerIdAndEventExternalId(UUID controllerId, String eventExternalId);

    boolean existsByControllerIdAndEventExternalId(UUID controllerId, String eventExternalId);

    List<DetectedEventEntity> findAllByControllerIdOrderByDetectedAtDesc(UUID controllerId);

    @Query("SELECT e FROM DetectedEventEntity e WHERE e.expiresAt IS NOT NULL AND e.expiresAt < :threshold")
    List<DetectedEventEntity> findExpiredBefore(@Param("threshold") OffsetDateTime threshold);

    long countByControllerId(UUID controllerId);

    /**
     * Inserts a new detected event row. Silently skips if (controller_id, event_external_id)
     * already exists — no exception, transaction stays clean.
     * Returns 1 if inserted, 0 if the row already existed.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO detected_events (id, controller_id, event_external_id, title, url, extra_data, detected_at)
            VALUES (:id, :controllerId, :externalId, :title, :url, :extraData, now())
            ON CONFLICT (controller_id, event_external_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("controllerId") UUID controllerId,
                       @Param("externalId") String externalId,
                       @Param("title") String title,
                       @Param("url") String url,
                       @Param("extraData") String extraData);

    @Query("SELECT e.eventExternalId FROM DetectedEventEntity e WHERE e.controller.id = :controllerId AND e.detectedAt > :cutoff")
    List<String> findExternalIdsByControllerIdAndDetectedAtAfter(
            @Param("controllerId") UUID controllerId,
            @Param("cutoff") OffsetDateTime cutoff);

    @Query("SELECT e.eventExternalId FROM DetectedEventEntity e WHERE e.controller.id = :controllerId")
    List<String> findAllExternalIdsByControllerId(@Param("controllerId") UUID controllerId);

    @Modifying
    @Query("DELETE FROM DetectedEventEntity e WHERE e.expiresAt IS NOT NULL AND e.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") OffsetDateTime threshold);

    @Modifying
    @Query(value = """
            DELETE FROM detected_events
            WHERE id IN (
                SELECT id FROM detected_events
                WHERE expires_at IS NOT NULL AND expires_at < :threshold
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("threshold") OffsetDateTime threshold,
                           @Param("batchSize") int batchSize);

    @Query("SELECT e.controller.id as controllerId, COUNT(e) as eventCount FROM DetectedEventEntity e WHERE e.controller.id IN :ids GROUP BY e.controller.id")
    List<com.valui.user.repository.DetectedEventRepository.ControllerEventCount> countByControllerIdIn(@Param("ids") java.util.Collection<UUID> ids);

    interface ControllerEventCount {
        UUID getControllerId();
        long getEventCount();
    }

    long countByDetectedAtAfter(OffsetDateTime since);

    long countByDetectedAtBetween(OffsetDateTime from, OffsetDateTime to);

    Page<DetectedEventEntity> findByControllerIdOrderByDetectedAtDesc(UUID controllerId, Pageable pageable);
}
