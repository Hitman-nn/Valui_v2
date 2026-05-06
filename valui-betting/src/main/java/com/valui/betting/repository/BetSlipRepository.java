package com.valui.betting.repository;

import com.valui.common.entity.BetSlipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BetSlipRepository extends JpaRepository<BetSlipEntity, UUID> {

    List<BetSlipEntity> findAllByBetIdOrderBySortOrderAsc(UUID betId);
}
