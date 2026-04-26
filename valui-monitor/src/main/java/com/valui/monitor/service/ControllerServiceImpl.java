package com.valui.monitor.service;

import com.valui.common.domain.BookmakerType;
import com.valui.common.domain.ControllerType;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.ControllerAccessException;
import com.valui.common.exception.ControllerNotFoundException;
import com.valui.common.exception.UserNotFoundException;
import com.valui.common.exception.ValuiException;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.dto.CreateControllerRequest;
import com.valui.monitor.event.ControllerAddedEvent;
import com.valui.monitor.event.ControllerRemovedEvent;
import com.valui.parser.util.ParsedUrlIds;
import com.valui.parser.util.UrlParser;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.DetectedEventRepository;
import com.valui.user.repository.UserRepository;
import com.valui.user.service.PlanLimitChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ControllerServiceImpl implements ControllerService {

    private final ControllerRepository controllerRepository;
    private final DetectedEventRepository detectedEventRepository;
    private final UserRepository userRepository;
    private final PlanLimitChecker planLimitChecker;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public ControllerDto addController(CreateControllerRequest req, Long telegramId) {
        UserEntity user = requireUser(telegramId);

        planLimitChecker.checkControllerLimit(telegramId);

        BookmakerType bookmaker = resolveBookmaker(req);
        planLimitChecker.checkBookmakerAccess(telegramId, bookmaker.name());

        if (controllerRepository.existsByUserIdAndBookmakerAndUrlAndIsActiveTrue(user.getId(), bookmaker, req.url())) {
            throw new ValuiException("Controller already exists for this URL", 409);
        }

        ControllerType type = resolveType(req.url(), bookmaker);
        int pollIntervalSec = planLimitChecker.getLimitInfo(telegramId).pollIntervalSec();

        ControllerEntity saved = controllerRepository.save(
                ControllerEntity.builder()
                        .user(user)
                        .bookmaker(bookmaker)
                        .url(req.url())
                        .title(req.title())
                        .type(type)
                        .isMuted(req.isMuted())
                        .isActive(true)
                        .pollIntervalSec(pollIntervalSec)
                        .build()
        );
        log.info("✅ Контроллер добавлен: id={} букмекер={} telegramId={}", saved.getId(), bookmaker, telegramId);
        // Publish after commit so MonitorScheduler sees the persisted row
        eventPublisher.publishEvent(new ControllerAddedEvent(
                saved.getId(), user.getId(), telegramId, bookmaker, pollIntervalSec));
        return toDto(saved);
    }

    @Override
    @Transactional
    public void removeController(UUID controllerId, Long telegramId) {
        UserEntity user = requireUser(telegramId);
        ControllerEntity entity = requireOwned(controllerId, user.getId());
        entity.setIsActive(false);
        controllerRepository.save(entity);
        log.info("🗑  Контроллер удалён: id={} telegramId={}", controllerId, telegramId);
        eventPublisher.publishEvent(new ControllerRemovedEvent(controllerId, user.getId()));
    }

    @Override
    public ControllerDto getController(UUID controllerId) {
        return toDto(controllerRepository.findById(controllerId)
                .orElseThrow(() -> new ControllerNotFoundException(controllerId)));
    }

    @Override
    public List<ControllerDto> getUserControllers(Long telegramId) {
        UserEntity user = requireUser(telegramId);
        return controllerRepository.findAllByUserIdAndIsActiveTrue(user.getId())
                .stream().map(this::toDto).toList();
    }

    @Override
    public Page<ControllerDto> getUserControllers(Long telegramId, Pageable pageable) {
        UserEntity user = requireUser(telegramId);
        return controllerRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), pageable)
                .map(this::toDto);
    }

    @Override
    @Transactional
    public void muteController(UUID controllerId, Long telegramId) {
        UserEntity user = requireUser(telegramId);
        ControllerEntity entity = requireOwned(controllerId, user.getId());
        entity.setIsMuted(true);
        controllerRepository.save(entity);
    }

    @Override
    @Transactional
    public void unmuteController(UUID controllerId, Long telegramId) {
        UserEntity user = requireUser(telegramId);
        ControllerEntity entity = requireOwned(controllerId, user.getId());
        entity.setIsMuted(false);
        controllerRepository.save(entity);
    }

    @Override
    @Transactional
    public ControllerDto updateFilterRule(UUID controllerId, Long telegramId, String rule) {
        UserEntity user = requireUser(telegramId);
        ControllerEntity entity = requireOwned(controllerId, user.getId());

        // Count against limit only when a new filter is being added (was null before)
        if (entity.getFilterRule() == null && rule != null && !rule.isBlank()) {
            planLimitChecker.checkFilterLimit(telegramId);
        }

        entity.setFilterRule(rule != null && rule.isBlank() ? null : rule);
        ControllerEntity saved = controllerRepository.save(entity);
        return toDto(saved);
    }

    @Override
    @Transactional
    public void deactivateController(UUID controllerId) {
        controllerRepository.findById(controllerId)
                .orElseThrow(() -> new ControllerNotFoundException(controllerId));
        controllerRepository.updateIsActive(controllerId, false);
        log.info("🔒 Контроллер деактивирован (admin): id={}", controllerId);
        eventPublisher.publishEvent(new ControllerRemovedEvent(controllerId, null));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UserEntity requireUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new UserNotFoundException(telegramId));
    }

    private ControllerEntity requireOwned(UUID controllerId, UUID userId) {
        return controllerRepository.findByIdAndUserId(controllerId, userId)
                .orElseThrow(() -> new ControllerAccessException(controllerId));
    }

    private ControllerDto toDto(ControllerEntity e) {
        long eventCount = detectedEventRepository.countByControllerId(e.getId());
        return new ControllerDto(
                e.getId(),
                e.getBookmaker().name(),
                e.getUrl(),
                e.getTitle(),
                e.getFilterRule(),
                Boolean.TRUE.equals(e.getIsMuted()),
                Boolean.TRUE.equals(e.getIsActive()),
                e.getLastCheckedAt() != null ? e.getLastCheckedAt().toInstant() : null,
                e.getLastEventAt()   != null ? e.getLastEventAt().toInstant()   : null,
                (int) eventCount
        );
    }

    private static BookmakerType resolveBookmaker(CreateControllerRequest req) {
        if (req.bookmaker() != null && !req.bookmaker().isBlank()) {
            try {
                return BookmakerType.valueOf(req.bookmaker().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ValuiException("Unknown bookmaker: " + req.bookmaker(), 400);
            }
        }
        try {
            return UrlParser.parseBookmaker(req.url());
        } catch (IllegalArgumentException e) {
            throw new ValuiException("Cannot detect bookmaker from URL: " + req.url(), 400);
        }
    }

    private static ControllerType resolveType(String url, BookmakerType bookmaker) {
        try {
            ParsedUrlIds ids = UrlParser.extractIds(url, bookmaker);
            return ids.matchId() != null ? ControllerType.MATCH : ControllerType.TOURNAMENT;
        } catch (Exception e) {
            return ControllerType.TOURNAMENT;
        }
    }
}
