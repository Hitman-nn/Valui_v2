package com.valui.user.repository;

import com.valui.common.domain.SubscriptionStatus;
import com.valui.common.entity.SubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, UUID> {

    Optional<SubscriptionEntity> findTopByUserIdAndStatusOrderByStartedAtDesc(UUID userId, SubscriptionStatus status);

    List<SubscriptionEntity> findAllByUserId(UUID userId);

    @Query("""
            SELECT s FROM SubscriptionEntity s
            WHERE s.status = 'ACTIVE'
              AND s.expiresAt IS NOT NULL
              AND s.expiresAt < :threshold
            """)
    List<SubscriptionEntity> findExpiredBefore(@Param("threshold") OffsetDateTime threshold);
}
