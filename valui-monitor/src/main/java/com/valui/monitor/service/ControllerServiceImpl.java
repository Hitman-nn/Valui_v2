package com.valui.monitor.service;

import com.valui.common.annotation.Audit;
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
import com.valui.user.api.ControllerPortService;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.api.PlanLimitFacade;
import com.valui.user.service.UserService;
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

    private final ControllerPortService controllerPort;
    private final DetectedEventPortService detectedEventPort;
    private final UserService userService;
    private final PlanLimitFacade planLimitFacade;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    @Audit(action = "ADD_CONTROLLER", entityType = "Controller")
    public ControllerDto addController(CreateControllerRequest req, Long telegramId, Long notificationChatId) {
        UserEntity user = requireUser(telegramId);

        planLimitFacade.checkControllerLimit(telegramId);

        // Group chat: also check that the group hasn't hit its own ceiling
        if (notificationChatId != null && notificationChatId < 0) {
            planLimitFacade.checkGroupCapacity(notificationChatId);
        }

        BookmakerType bookmaker = resolveBookmaker(req);
        planLimitFacade.checkBookmakerAccess(telegramId, bookmaker.name());

        if (controllerPort.existsByUserAndBookmakerAndUrl(user.getId(), bookmaker, req.url())) {
            throw new ValuiException("Controller already exists for this URL", 409);
        }

        ControllerType type = (req.typeHint() != null) ? req.typeHint() : resolveType(req.url(), bookmaker);
        int pollIntervalSec = planLimitFacade.getLimitInfo(telegramId).pollIntervalSec();

        ControllerEntity saved = controllerPort.save(
                ControllerEntity.builder()
                        .user(user)
                        .bookmaker(bookmaker)
                        .url(req.url())
                        .title(req.title())
                        .type(type)
                        .isMuted(req.isMuted())
                        .isActive(true)
                        .pollIntervalSec(pollIntervalSec)
                        .notificationChatId(notificationChatId)
                        .build()
        );
        log.info("✅ Контроллер добавлен: id={} букмекер={} telegramId={} notifChat={}",
                saved.getId(), bookmaker, telegramId, notificationChatId);
        eventPublisher.publishEvent(new ControllerAddedEvent(
                saved.getId(), user.getId(), telegramId, bookmaker, pollIntervalSec));
        return toDto(saved);
    }

    @Override
    @Transactional
    @Audit(action = "REMOVE_CONTROLLER", entityType = "Controller")
    public void removeController(UUID controllerId, Long telegramId) {
        UserEntity user = requireUser(telegramId);
        ControllerEntity entity = requireOwned(controllerId, user.getId());
        entity.setIsActive(false);
        controllerPort.save(entity);
        log.info("🗑  Контроллер удалён: id={} telegramId={}", controllerId, telegramId);
        eventPublisher.publishEvent(new ControllerRemovedEvent(controllerId, user.getId()));
    }

    @Override
    public ControllerDto getController(UUID controllerId) {
        return toDto(controllerPort.findById(controllerId)
                .orElseThrow(() -> new ControllerNotFoundException(controllerId)));
    }

    @Override
    public List<ControllerDto> getUserControllers(Long telegramId) {
        UserEntity user = requireUser(telegramId);
        return controllerPort.findAllActiveByUserId(user.getId())
                .stream().map(this::toDto).toList();
    }

    @Override
    public List<ControllerDto> getGroupControllers(Long notificationChatId) {
        return controllerPort.findAllActiveByNotificationChatId(notificationChatId)
                .stream().map(this::toDto).toList();
    }

    @Override
    public Page<ControllerDto> getUserControllers(Long telegramId, Pageable pageable) {
        UserEntity user = requireUser(telegramId);
        return controllerPort.findByUserIdPageable(user.getId(), pageable)
                .map(this::toDto);
    }

    @Override
    @Transactional
    public void muteController(UUID controllerId, Long telegramId) {
        UserEntity user = requireUser(telegramId);
        ControllerEntity entity = requireOwned(controllerId, user.getId());
        entity.setIsMuted(true);
        controllerPort.save(entity);
    }

    @Override
    @Transactional
    public void unmuteController(UUID controllerId, Long telegramId) {
        UserEntity user = requireUser(telegramId);
        ControllerEntity entity = requireOwned(controllerId, user.getId());
        entity.setIsMuted(false);
        controllerPort.save(entity);
    }

    @Override
    @Transactional
    @Audit(action = "UPDATE_FILTER", entityType = "Controller")
    public ControllerDto updateFilterRule(UUID controllerId, Long telegramId, String rule) {
        UserEntity user = requireUser(telegramId);
        ControllerEntity entity = requireOwned(controllerId, user.getId());

        // Count against limit only when a new filter is being added (was null before)
        if (entity.getFilterRule() == null && rule != null && !rule.isBlank()) {
            planLimitFacade.checkFilterLimit(telegramId);
        }

        entity.setFilterRule(rule != null && rule.isBlank() ? null : rule);
        ControllerEntity saved = controllerPort.save(entity);
        return toDto(saved);
    }

    @Override
    @Transactional
    public void deactivateController(UUID controllerId) {
        controllerPort.findById(controllerId)
                .orElseThrow(() -> new ControllerNotFoundException(controllerId));
        controllerPort.updateIsActive(controllerId, false);
        log.info("🔒 Контроллер деактивирован (admin): id={}", controllerId);
        eventPublisher.publishEvent(new ControllerRemovedEvent(controllerId, null));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UserEntity requireUser(Long telegramId) {
        return userService.findByTelegramId(telegramId)
                .orElseThrow(() -> new UserNotFoundException(telegramId));
    }

    private ControllerEntity requireOwned(UUID controllerId, UUID userId) {
        return controllerPort.findByIdAndUserId(controllerId, userId)
                .orElseThrow(() -> new ControllerAccessException(controllerId));
    }

    private ControllerDto toDto(ControllerEntity e) {
        long eventCount = detectedEventPort.countByControllerId(e.getId());
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
                (int) eventCount,
                e.getType(),
                e.getNotificationChatId()
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
