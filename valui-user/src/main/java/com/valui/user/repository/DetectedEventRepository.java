package com.valui.user.repository;

import com.valui.common.entity.DetectedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    @Query("SELECT e.eventExternalId FROM DetectedEventEntity e WHERE e.controller.id = :controllerId AND e.detectedAt > :cutoff")
    List<String> findExternalIdsByControllerIdAndDetectedAtAfter(
            @Param("controllerId") UUID controllerId,
            @Param("cutoff") OffsetDateTime cutoff);

    @Modifying
    @Query("DELETE FROM DetectedEventEntity e WHERE e.expiresAt IS NOT NULL AND e.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") OffsetDateTime threshold);
}
