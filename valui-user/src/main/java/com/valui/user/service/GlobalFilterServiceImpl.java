package com.valui.user.service;

import com.valui.common.domain.TokenReasonCode;
import com.valui.common.entity.GlobalFilterEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.repository.GlobalFilterRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GlobalFilterServiceImpl implements GlobalFilterService {

    private final GlobalFilterRepository globalFilterRepository;
    private final UserRepository         userRepository;
    private final TokenLedgerService     tokenLedgerService;
    private final CacheManager           cacheManager;

    @Override
    public List<GlobalFilterEntity> getFilters(Long telegramId, Long chatId) {
        UserEntity user = requireUser(telegramId);
        // Personal chat: show all own filters (including those created in groups)
        if (telegramId.equals(chatId)) {
            return globalFilterRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId());
        }
        // Group chat: show all filters for this chat regardless of who created them
        return globalFilterRepository.findAllByChatIdOrderByCreatedAtAsc(chatId);
    }

    @Override
    @Cacheable(value = "globalFilters", key = "#chatId")
    public List<String> findByChatId(Long chatId) {
        return globalFilterRepository.findAllByChatIdAndPausedByTokensFalseOrderByCreatedAtAsc(chatId)
                .stream()
                .map(GlobalFilterEntity::getFilterRule)
                .toList();
    }

    @Override
    @Transactional
    public void addFilter(Long telegramId, Long chatId, String rule) {
        UserEntity user = requireUser(telegramId);
        int cost = tokenLedgerService.getCost("FILTER_MONTHLY");
        // Бросает InsufficientTokensException если токенов нет
        tokenLedgerService.debit(user.getId(), cost, TokenReasonCode.FILTER_CHARGE, null);

        globalFilterRepository.save(GlobalFilterEntity.builder()
            .user(user)
            .chatId(chatId)
            .filterRule(rule)
            .createdAt(OffsetDateTime.now())
            .build());
        evictFilterCache(chatId);
    }

    @Override
    @Transactional
    public void deleteFilter(Long telegramId, Long chatId, UUID filterId) {
        UserEntity user = requireUser(telegramId);
        // Group member can delete by chatId; owner can delete any of their own filters (e.g. from personal chat)
        GlobalFilterEntity f = globalFilterRepository.findByIdAndChatId(filterId, chatId)
                .or(() -> globalFilterRepository.findByIdAndUserId(filterId, user.getId()))
                .orElse(null);
        if (f != null) {
            Long filterChatId = f.getChatId();
            globalFilterRepository.delete(f);
            evictFilterCache(filterChatId);
        }
    }

    @Override
    @Transactional
    public void updateFilter(Long telegramId, Long chatId, UUID filterId, String newRule) {
        UserEntity user = requireUser(telegramId);
        globalFilterRepository.findByIdAndChatId(filterId, chatId)
                .or(() -> globalFilterRepository.findByIdAndUserId(filterId, user.getId()))
                .ifPresent(f -> {
                    Long filterChatId = f.getChatId();
                    f.setFilterRule(newRule);
                    globalFilterRepository.save(f);
                    evictFilterCache(filterChatId);
                });
    }

    private UserEntity requireUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
    }

    private void evictFilterCache(Long chatId) {
        var cache = cacheManager.getCache("globalFilters");
        if (cache != null) cache.evict(chatId);
    }
}
