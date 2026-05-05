package com.valui.user.repository;

import com.valui.common.domain.BookmakerType;
import com.valui.common.entity.ControllerEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
public interface ControllerRepository extends JpaRepository<ControllerEntity, UUID> {

    List<ControllerEntity> findAllByUserIdAndIsActiveTrue(UUID userId);

    List<ControllerEntity> findAllByIsActiveTrue();

    List<ControllerEntity> findAllByUserIdAndBookmaker(UUID userId, BookmakerType bookmaker);

    int countByUserIdAndIsActiveTrue(UUID userId);

    boolean existsByUserIdAndBookmakerAndUrlAndIsActiveTrue(UUID userId, BookmakerType bookmaker, String url);

    @Modifying
    @Query("UPDATE ControllerEntity c SET c.lastCheckedAt = :checkedAt WHERE c.id = :id")
    int updateLastCheckedAt(@Param("id") UUID id, @Param("checkedAt") OffsetDateTime checkedAt);

    @Modifying
    @Query("UPDATE ControllerEntity c SET c.isActive = :active WHERE c.id = :id")
    int updateIsActive(@Param("id") UUID id, @Param("active") Boolean active);

    Optional<ControllerEntity> findByIdAndUserId(UUID id, UUID userId);

    Page<ControllerEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT COUNT(c) FROM ControllerEntity c WHERE c.user.id = :userId AND c.isActive = true AND c.filterRule IS NOT NULL")
    long countActiveFiltersUsedByUserId(@Param("userId") UUID userId);

    List<ControllerEntity> findAllByNotificationChatIdAndIsActiveTrue(Long notificationChatId);

    int countByNotificationChatIdAndIsActiveTrue(Long notificationChatId);

    @Query("SELECT c FROM ControllerEntity c WHERE c.notificationChatId = :chatId AND c.isActive = true ORDER BY c.createdAt DESC")
    Page<ControllerEntity> findByNotificationChatIdOrderByCreatedAtDesc(@Param("chatId") Long chatId, Pageable pageable);

    int countByUserIdAndBookmakerAndIsActiveTrue(UUID userId, BookmakerType bookmaker);

    List<ControllerEntity> findAllByUserIdAndBookmakerAndIsActiveTrue(UUID userId, BookmakerType bookmaker);

    List<ControllerEntity> findAllByUserIdAndPausedByTokensTrue(UUID userId);

    @Modifying
    @Query("UPDATE ControllerEntity c SET c.pausedByTokens = :paused, c.isActive = :active, c.isMuted = :muted WHERE c.id = :id")
    int updateTokenPauseState(@Param("id") UUID id, @Param("paused") boolean paused, @Param("active") boolean active, @Param("muted") boolean muted);

    long countByUserId(UUID userId);

    long countByIsActiveTrue();

    long countByIsMutedTrue();

    long countByIsActiveFalse();

    long countByIsActiveTrueAndLastEventAtBefore(OffsetDateTime cutoff);

    @Query("SELECT COUNT(c) FROM ControllerEntity c WHERE c.bookmaker = :bm")
    long countByBookmakerType(@Param("bm") BookmakerType bm);

    @Query(value = """
            SELECT c FROM ControllerEntity c
            WHERE (:bookmaker IS NULL OR c.bookmaker = :bookmaker)
              AND (:isActive  IS NULL OR c.isActive  = :isActive)
              AND (:isMuted   IS NULL OR c.isMuted   = :isMuted)
            """,
           countQuery = """
            SELECT COUNT(c) FROM ControllerEntity c
            WHERE (:bookmaker IS NULL OR c.bookmaker = :bookmaker)
              AND (:isActive  IS NULL OR c.isActive  = :isActive)
              AND (:isMuted   IS NULL OR c.isMuted   = :isMuted)
            """)
    Page<ControllerEntity> findAllFiltered(
            @Param("bookmaker") BookmakerType bookmaker,
            @Param("isActive")  Boolean isActive,
            @Param("isMuted")   Boolean isMuted,
            Pageable pageable);
}
