package com.valui.user.service;

import com.valui.common.domain.BookmakerType;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.ControllerSubscriptionEntity;
import com.valui.user.api.ControllerPortService;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.ControllerSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ControllerPortServiceImpl implements ControllerPortService {

    private final ControllerRepository repository;
    private final ControllerSubscriptionRepository subscriptionRepository;

    @Override public List<ControllerEntity> findAllActive() { return repository.findAllByIsActiveTrue(); }
    @Override public List<ControllerEntity> findAllActiveWithUser() { return repository.findAllActiveWithUser(); }
    @Override public List<ControllerEntity> findAllActiveByUserId(UUID userId) { return repository.findAllByUserIdAndIsActiveTrue(userId); }
    @Override public List<ControllerEntity> findAllActiveByUserIdWithUser(UUID userId) { return repository.findAllActiveByUserIdWithUser(userId); }
    @Override public Optional<ControllerEntity> findById(UUID id) { return repository.findById(id); }
    @Override public Optional<ControllerEntity> findByIdAndUserId(UUID id, UUID userId) { return repository.findByIdAndUserId(id, userId); }

    @Override @Transactional
    public ControllerEntity save(ControllerEntity e) { return repository.save(e); }

    @Override @Transactional
    public void updateLastCheckedAt(UUID id, OffsetDateTime at) { repository.updateLastCheckedAt(id, at); }

    @Override @Transactional
    public void resetLastCheckedAtBatch(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return;
        repository.resetLastCheckedAtBatch(ids);
    }

    @Override @Transactional
    public void updateIsActive(UUID id, boolean active) { repository.updateIsActive(id, active); }

    @Override
    public boolean existsByUserAndBookmakerAndUrl(UUID userId, BookmakerType bk, String url) {
        return repository.existsByUserIdAndBookmakerAndUrlAndIsActiveTrue(userId, bk, url);
    }

    @Override
    public Page<ControllerEntity> findByUserIdPageable(UUID userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Override
    public List<ControllerEntity> findAllActiveByNotificationChatId(Long chatId) {
        return repository.findAllByNotificationChatIdAndIsActiveTrue(chatId);
    }

    // ── Subscription methods ──────────────────────────────────────────────────

    @Override @Transactional
    public void createSubscription(UUID controllerId, Long chatId, UUID userId, Long telegramId) {
        if (subscriptionRepository.findByControllerIdAndChatId(controllerId, chatId).isPresent()) return;
        subscriptionRepository.save(ControllerSubscriptionEntity.builder()
            .controllerId(controllerId).chatId(chatId).userId(userId).telegramId(telegramId).build());
    }

    @Override @Transactional
    public void removeSubscription(UUID controllerId, Long chatId) {
        subscriptionRepository.deleteByControllerIdAndChatId(controllerId, chatId);
    }

    @Override @Transactional
    public void muteSubscription(UUID controllerId, Long chatId) {
        subscriptionRepository.updateMuted(controllerId, chatId, true);
    }

    @Override @Transactional
    public void unmuteSubscription(UUID controllerId, Long chatId) {
        subscriptionRepository.updateMuted(controllerId, chatId, false);
    }

    @Override
    public List<ControllerSubscriptionEntity> findActiveSubscriptions(UUID controllerId) {
        return subscriptionRepository.findAllByControllerIdAndIsMutedFalseAndPausedByTokensFalse(controllerId);
    }

    @Override
    public boolean hasActiveSubscriptions(UUID controllerId) {
        return subscriptionRepository.existsByControllerIdAndIsMutedFalseAndPausedByTokensFalse(controllerId);
    }

    @Override
    public Optional<ControllerSubscriptionEntity> findSubscription(UUID controllerId, Long chatId) {
        return subscriptionRepository.findByControllerIdAndChatId(controllerId, chatId);
    }

    @Override @Transactional
    public void linkVk(UUID userId, long telegramChatId, long vkPeerId) {
        subscriptionRepository.updateVkPeerIdByUserIdAndChatId(userId, telegramChatId, vkPeerId);
    }

    @Override @Transactional
    public void unlinkVk(UUID userId) {
        subscriptionRepository.clearVkPeerIdByUserId(userId);
    }

    @Override
    public Page<ControllerEntity> findAllPageable(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Override
    public Page<ControllerEntity> findAllFiltered(BookmakerType bookmaker, Boolean isActive, Boolean isMuted, Pageable pageable) {
        return repository.findAllFiltered(bookmaker, isActive, isMuted, pageable);
    }
}
