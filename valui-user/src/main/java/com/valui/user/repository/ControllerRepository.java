package com.valui.user.repository;

import com.valui.common.domain.BookmakerType;
import com.valui.common.entity.ControllerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ControllerRepository extends JpaRepository<ControllerEntity, UUID> {

    List<ControllerEntity> findAllByUserIdAndIsActiveTrue(UUID userId);

    List<ControllerEntity> findAllByIsActiveTrue();

    List<ControllerEntity> findAllByUserIdAndBookmaker(UUID userId, BookmakerType bookmaker);

    int countByUserIdAndIsActiveTrue(UUID userId);

    boolean existsByUserIdAndBookmakerAndUrl(UUID userId, BookmakerType bookmaker, String url);

    @Modifying
    @Query("UPDATE ControllerEntity c SET c.lastCheckedAt = :checkedAt WHERE c.id = :id")
    int updateLastCheckedAt(@Param("id") UUID id, @Param("checkedAt") OffsetDateTime checkedAt);

    @Modifying
    @Query("UPDATE ControllerEntity c SET c.isActive = :active WHERE c.id = :id")
    int updateIsActive(@Param("id") UUID id, @Param("active") Boolean active);

    @Query("SELECT COUNT(c) FROM ControllerEntity c WHERE c.user.id = :userId AND c.isActive = true AND c.filterRule IS NOT NULL")
    long countActiveFiltersUsedByUserId(@Param("userId") UUID userId);
}
