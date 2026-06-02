package com.valui.user.repository;

import com.valui.common.entity.MarketWatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MarketWatchRepository extends JpaRepository<MarketWatchEntity, UUID> {

    boolean existsByChatIdAndExternalEventIdAndBookmakerAndMarketTypeAndStatus(
            long chatId, String externalEventId, String bookmaker, String marketType, String status);

    /** All active watches for a controller covering any of the given externalEventIds. */
    @Query("""
            SELECT w FROM MarketWatchEntity w
            WHERE w.controllerId = :controllerId
              AND w.status = 'ACTIVE'
              AND w.externalEventId IN :eventIds
            """)
    List<MarketWatchEntity> findActiveByControllerAndEvents(
            @Param("controllerId") UUID controllerId,
            @Param("eventIds") List<String> eventIds);

    @Modifying
    @Query("UPDATE MarketWatchEntity w SET w.status = :status WHERE w.id = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") String status);

    /** Expire watches whose match has already started and are still ACTIVE. */
    @Modifying
    @Query("UPDATE MarketWatchEntity w SET w.status = 'EXPIRED' WHERE w.status = 'ACTIVE' AND w.startEpoch IS NOT NULL AND w.startEpoch < :nowEpoch")
    int expireStarted(@Param("nowEpoch") long nowEpoch);
}
