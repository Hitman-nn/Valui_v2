package com.valui.user.service;

import com.valui.common.domain.BookmakerType;
import com.valui.common.entity.ControllerEntity;
import com.valui.user.api.ControllerPortService;
import com.valui.user.repository.ControllerRepository;
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

    @Override
    public List<ControllerEntity> findAllActive() {
        return repository.findAllByIsActiveTrue();
    }

    @Override
    public List<ControllerEntity> findAllActiveByUserId(UUID userId) {
        return repository.findAllByUserIdAndIsActiveTrue(userId);
    }

    @Override
    public Optional<ControllerEntity> findById(UUID controllerId) {
        return repository.findById(controllerId);
    }

    @Override
    public Optional<ControllerEntity> findByIdAndUserId(UUID controllerId, UUID userId) {
        return repository.findByIdAndUserId(controllerId, userId);
    }

    @Override
    @Transactional
    public ControllerEntity save(ControllerEntity entity) {
        return repository.save(entity);
    }

    @Override
    @Transactional
    public void updateLastCheckedAt(UUID controllerId, OffsetDateTime checkedAt) {
        repository.updateLastCheckedAt(controllerId, checkedAt);
    }

    @Override
    @Transactional
    public void updateIsActive(UUID controllerId, boolean active) {
        repository.updateIsActive(controllerId, active);
    }

    @Override
    public boolean existsByUserAndBookmakerAndUrl(UUID userId, BookmakerType bookmaker, String url) {
        return repository.existsByUserIdAndBookmakerAndUrlAndIsActiveTrue(userId, bookmaker, url);
    }

    @Override
    public Page<ControllerEntity> findByUserIdPageable(UUID userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Override
    public List<ControllerEntity> findAllActiveByNotificationChatId(Long chatId) {
        return repository.findAllByNotificationChatIdAndIsActiveTrue(chatId);
    }
}
