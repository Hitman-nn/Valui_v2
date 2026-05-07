package com.valui.betting.repository;

import com.valui.common.entity.BetAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BetAccountRepository extends JpaRepository<BetAccountEntity, UUID> {
    List<BetAccountEntity> findAllByChatIdOrderByNameAsc(Long chatId);
    boolean existsByChatIdAndNameIgnoreCase(Long chatId, String name);
}
