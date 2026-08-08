package com.valui.betting.service.impl;

import com.valui.betting.dto.BetDmLinkDto;
import com.valui.betting.repository.BetDmLinkRepository;
import com.valui.betting.service.BetDmLinkService;
import com.valui.common.entity.BetDmLinkEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BetDmLinkServiceImpl implements BetDmLinkService {

    private final BetDmLinkRepository repo;

    @Override
    @Transactional
    public void link(long chatId, long telegramId, String chatTitle) {
        BetDmLinkEntity entity = repo.findByChatIdAndTelegramId(chatId, telegramId)
                .orElseGet(() -> BetDmLinkEntity.builder()
                        .chatId(chatId)
                        .telegramId(telegramId)
                        .linkedAt(OffsetDateTime.now())
                        .build());
        entity.setChatTitle(chatTitle);
        repo.save(entity);
        log.info("[BET-DM-LINK] Linked: chatId={} telegramId={} chatTitle={}", chatId, telegramId, chatTitle);
    }

    @Override
    @Transactional
    public void unlink(long chatId, long telegramId) {
        repo.deleteByChatIdAndTelegramId(chatId, telegramId);
        log.info("[BET-DM-LINK] Unlinked: chatId={} telegramId={}", chatId, telegramId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isLinked(long chatId, long telegramId) {
        return repo.existsByChatIdAndTelegramId(chatId, telegramId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BetDmLinkDto> listLinks(long telegramId) {
        return repo.findAllByTelegramIdOrderByChatTitleAsc(telegramId)
                .stream().map(BetDmLinkDto::from).toList();
    }
}
