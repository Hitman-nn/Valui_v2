package com.valui.betting.repository;

import com.valui.common.entity.BetPersonEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BetPersonRepository extends JpaRepository<BetPersonEntity, UUID> {
    List<BetPersonEntity> findAllByChatIdOrderByDisplayNameAsc(Long chatId);
    boolean existsByChatIdAndDisplayNameIgnoreCase(Long chatId, String displayName);
}
