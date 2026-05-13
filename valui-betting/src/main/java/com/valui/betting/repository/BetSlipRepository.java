package com.valui.betting.repository;

import com.valui.common.entity.BetSlipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface BetSlipRepository extends JpaRepository<BetSlipEntity, UUID> {

    List<BetSlipEntity> findAllByBetIdOrderBySortOrderAsc(UUID betId);

    /** Slips with a known start time that haven't been snapshotted and whose match hasn't started yet. */
    @Query("SELECT s FROM BetSlipEntity s WHERE s.startsAt IS NOT NULL AND s.snapshotTakenAt IS NULL AND s.startsAt > :now")
    List<BetSlipEntity> findPendingSnapshots(@Param("now") Instant now);
}
