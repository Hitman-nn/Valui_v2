package com.valui.betting.repository;

import com.valui.common.entity.BetDmLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BetDmLinkRepository extends JpaRepository<BetDmLinkEntity, BetDmLinkEntity.LinkId> {

    List<BetDmLinkEntity> findAllByTelegramIdOrderByChatTitleAsc(Long telegramId);

    Optional<BetDmLinkEntity> findByChatIdAndTelegramId(Long chatId, Long telegramId);

    boolean existsByChatIdAndTelegramId(Long chatId, Long telegramId);

    void deleteByChatIdAndTelegramId(Long chatId, Long telegramId);
}
