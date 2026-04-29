package com.valui.user.service;

import com.valui.common.domain.TokenReasonCode;
import com.valui.common.entity.GlobalFilterEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.repository.GlobalFilterRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
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

    @Override
    public List<GlobalFilterEntity> getFilters(Long telegramId) {
        UserEntity user = requireUser(telegramId);
        return globalFilterRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId());
    }

    @Override
    @Transactional
    public void addFilter(Long telegramId, String rule) {
        UserEntity user = requireUser(telegramId);
        int cost = tokenLedgerService.getCost("FILTER_MONTHLY");
        // Бросает InsufficientTokensException если токенов нет
        tokenLedgerService.debit(user.getId(), cost, TokenReasonCode.FILTER_CHARGE, null);

        globalFilterRepository.save(GlobalFilterEntity.builder()
            .user(user)
            .filterRule(rule)
            .createdAt(OffsetDateTime.now())
            .build());
    }

    @Override
    @Transactional
    public void deleteFilter(Long telegramId, UUID filterId) {
        UserEntity user = requireUser(telegramId);
        globalFilterRepository.deleteByIdAndUserId(filterId, user.getId());
    }

    @Override
    @Transactional
    public void updateFilter(Long telegramId, UUID filterId, String newRule) {
        UserEntity user = requireUser(telegramId);
        globalFilterRepository.findByIdAndUserId(filterId, user.getId()).ifPresent(f -> {
            f.setFilterRule(newRule);
            globalFilterRepository.save(f);
        });
    }

    private UserEntity requireUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
    }
}
