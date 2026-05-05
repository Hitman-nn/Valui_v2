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
import com.valui.monitor.dedup.EventDeduplicationService;
import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.dto.CreateControllerRequest;
import com.valui.monitor.event.ControllerAddedEvent;
import com.valui.monitor.event.ControllerRemovedEvent;
import com.valui.monitor.scheduler.MonitorScheduler;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ControllerServiceImpl implements ControllerService {

    private final ControllerPortService      controllerPort;
    private final DetectedEventPortService   detectedEventPort;
    private final UserService                userService;
    private final PlanLimitFacade            planLimitFacade;
    private final ApplicationEventPublisher  eventPublisher;
    private final EventDeduplicationService  dedup;
    private final MonitorScheduler           monitorScheduler;

    @Override
    @Transactional
    @Audit(action = "ADD_CONTROLLER", entityType = "Controller")
    public ControllerDto addController(CreateControllerRequest req, Long telegramId, Long notificationChatId) {
        UserEntity user = requireUser(telegramId);

        BookmakerType bookmaker = resolveBookmaker(req);

        if (controllerPort.existsByUserAndBookmakerAndUrl(user.getId(), bookmaker, req.url())) {
            throw new ValuiException("Controller already exists for this URL", 409);
        }

        // Списываем токены если это первый контроллер данной БК у пользователя
        planLimitFacade.debitForBkSlotIfNew(telegramId, bookmaker.name());

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
        Long effectiveChatId = notificationChatId != null ? notificationChatId : user.getTelegramId();
        controllerPort.createSubscription(saved.getId(), effectiveChatId, user.getId(), user.getTelegramId());
        log.info("[CONTROLLER] Добавлен: id={} бк={} telegramId={}", saved.getId(), bookmaker, telegramId);
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
        log.info("[CONTROLLER] Удалён: id={} telegramId={}", controllerId, telegramId);
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
    public List<ControllerDto> getUserControllersForChat(Long telegramId, Long chatId) {
        UserEntity user = requireUser(telegramId);
        return controllerPort.findAllActiveByUserId(user.getId())
            .stream().map(e -> toDtoForChat(e, chatId)).toList();
    }

    @Override
    public List<ControllerDto> getGroupControllers(Long notificationChatId) {
        return controllerPort.findAllActiveByNotificationChatId(notificationChatId)
            .stream().map(e -> toDtoForChat(e, notificationChatId)).toList();
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
        muteForChat(controllerId, telegramId, telegramId);
    }

    @Override
    @Transactional
    public void unmuteController(UUID controllerId, Long telegramId) {
        unmuteForChat(controllerId, telegramId, telegramId);
    }

    @Override
    @Transactional
    public void muteForChat(UUID controllerId, Long telegramId, Long chatId) {
        UserEntity user = requireUser(telegramId);
        requireOwned(controllerId, user.getId());
        controllerPort.muteSubscription(controllerId, chatId);
        if (!controllerPort.hasActiveSubscriptions(controllerId)) {
            dedup.clearController(controllerId);
            controllerPort.updateLastCheckedAt(controllerId, null);
            monitorScheduler.unscheduleController(controllerId);
        }
        log.info("[CTRL] Замьючен chatId={} controllerId={}", chatId, controllerId);
    }

    @Override
    @Transactional
    public void unmuteForChat(UUID controllerId, Long telegramId, Long chatId) {
        UserEntity user = requireUser(telegramId);
        requireOwned(controllerId, user.getId());
        controllerPort.unmuteSubscription(controllerId, chatId);
        if (!monitorScheduler.getScheduledControllerIds().contains(controllerId)) {
            controllerPort.findById(controllerId).ifPresent(c -> {
                int interval = c.getPollIntervalSec() != null ? c.getPollIntervalSec() : 60;
                monitorScheduler.scheduleController(controllerId, c.getUser().getId(), interval);
            });
        }
        log.info("[CTRL] Размьючен chatId={} controllerId={}", chatId, controllerId);
    }

    @Override
    @Transactional
    @Audit(action = "UPDATE_FILTER", entityType = "Controller")
    public ControllerDto updateFilterRule(UUID controllerId, Long telegramId, String rule) {
        UserEntity user = requireUser(telegramId);
        ControllerEntity entity = requireOwned(controllerId, user.getId());

        // Списываем токены только при добавлении нового фильтра (не при обновлении или удалении)
        if (entity.getFilterRule() == null && rule != null && !rule.isBlank()) {
            planLimitFacade.debitForControllerFilter(telegramId);
            entity.setFilterSetAt(OffsetDateTime.now());
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
        log.info("[CONTROLLER] Деактивирован (admin): id={}", controllerId);
        eventPublisher.publishEvent(new ControllerRemovedEvent(controllerId, null));
    }

    @Override
    public Page<ControllerDto> getAllControllers(Pageable pageable) {
        return controllerPort.findAllPageable(pageable).map(this::toDto);
    }

    @Override
    @Transactional
    public void activateController(UUID controllerId) {
        ControllerEntity e = controllerPort.findById(controllerId)
            .orElseThrow(() -> new ControllerNotFoundException(controllerId));
        controllerPort.updateIsActive(controllerId, true);
        log.info("[CONTROLLER] Активирован (admin): id={}", controllerId);
        eventPublisher.publishEvent(new ControllerAddedEvent(
            controllerId, e.getUser() != null ? e.getUser().getId() : null,
            e.getUser() != null ? e.getUser().getTelegramId() : null,
            e.getBookmaker(),
            e.getPollIntervalSec() != null ? e.getPollIntervalSec() : 60));
    }

    @Override
    @Transactional
    public void muteAdmin(UUID controllerId) {
        ControllerEntity e = controllerPort.findById(controllerId)
            .orElseThrow(() -> new ControllerNotFoundException(controllerId));
        e.setIsMuted(true);
        controllerPort.save(e);
        log.info("[CONTROLLER] Muted (admin): id={}", controllerId);
    }

    @Override
    @Transactional
    public void unmuteAdmin(UUID controllerId) {
        ControllerEntity e = controllerPort.findById(controllerId)
            .orElseThrow(() -> new ControllerNotFoundException(controllerId));
        e.setIsMuted(false);
        controllerPort.save(e);
        log.info("[CONTROLLER] Unmuted (admin): id={}", controllerId);
    }

    @Override
    @Transactional
    public ControllerDto updateAdmin(UUID controllerId, String title, String filterRule, Integer pollIntervalSec) {
        ControllerEntity e = controllerPort.findById(controllerId)
            .orElseThrow(() -> new ControllerNotFoundException(controllerId));
        if (title != null)          e.setTitle(title);
        if (filterRule != null)     e.setFilterRule(filterRule.isBlank() ? null : filterRule);
        if (pollIntervalSec != null) e.setPollIntervalSec(pollIntervalSec);
        ControllerEntity saved = controllerPort.save(e);
        log.info("[CONTROLLER] Updated (admin): id={}", controllerId);
        return toDto(saved);
    }

    @Override
    @Transactional
    public void stopForChat(UUID controllerId, Long telegramId, Long chatId) {
        UserEntity user = requireUser(telegramId);
        requireOwned(controllerId, user.getId());
        controllerPort.removeSubscription(controllerId, chatId);
        if (!controllerPort.hasActiveSubscriptions(controllerId)) {
            controllerPort.updateIsActive(controllerId, false);
            dedup.clearController(controllerId);
            controllerPort.updateLastCheckedAt(controllerId, null);
            monitorScheduler.unscheduleController(controllerId);
        }
        log.info("[CTRL] Остановлен chatId={} controllerId={}", chatId, controllerId);
    }

    @Override
    public ControllerDto getControllerForChat(UUID controllerId, Long chatId) {
        ControllerEntity e = controllerPort.findById(controllerId)
            .orElseThrow(() -> new ControllerNotFoundException(controllerId));
        boolean isMuted = controllerPort.findSubscription(controllerId, chatId)
            .map(s -> s.isMuted() || s.isPausedByTokens())
            .orElse(false);
        long eventCount = detectedEventPort.countByControllerId(e.getId());
        Long ownerTelegramId = e.getUser() != null ? e.getUser().getTelegramId() : null;
        return new ControllerDto(
            e.getId(), e.getBookmaker().name(), e.getUrl(), e.getTitle(),
            e.getFilterRule(), isMuted, Boolean.TRUE.equals(e.getIsActive()),
            e.getLastCheckedAt() != null ? e.getLastCheckedAt().toInstant() : null,
            e.getLastEventAt()   != null ? e.getLastEventAt().toInstant()   : null,
            (int) eventCount, e.getType(), e.getNotificationChatId(), ownerTelegramId
        );
    }

    @Override
    @Transactional
    public int stopAllForUserInChat(Long telegramId, Long groupChatId) {
        List<ControllerEntity> candidates = controllerPort.findAllActiveByNotificationChatId(groupChatId);
        UserEntity user = requireUser(telegramId);
        int count = 0;
        for (ControllerEntity c : candidates) {
            if (!user.getId().equals(c.getUser().getId())) continue;
            controllerPort.removeSubscription(c.getId(), groupChatId);
            if (!controllerPort.hasActiveSubscriptions(c.getId())) {
                controllerPort.updateIsActive(c.getId(), false);
                dedup.clearController(c.getId());
                controllerPort.updateLastCheckedAt(c.getId(), null);
                monitorScheduler.unscheduleController(c.getId());
            }
            log.info("[CTRL] stopAllForUserInChat: controllerId={} chatId={}", c.getId(), groupChatId);
            count++;
        }
        return count;
    }

    @Override
    @Transactional
    public int stopAllForUser(Long telegramId) {
        UserEntity user = requireUser(telegramId);
        List<ControllerEntity> all = controllerPort.findAllActiveByUserId(user.getId());
        for (ControllerEntity c : all) {
            Long chatId = c.getNotificationChatId();
            controllerPort.removeSubscription(c.getId(), chatId);
            if (!controllerPort.hasActiveSubscriptions(c.getId())) {
                controllerPort.updateIsActive(c.getId(), false);
                dedup.clearController(c.getId());
                controllerPort.updateLastCheckedAt(c.getId(), null);
                monitorScheduler.unscheduleController(c.getId());
            }
            log.info("[CTRL] stopAllForUser: controllerId={} chatId={}", c.getId(), chatId);
        }
        return all.size();
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

    private ControllerDto toDtoForChat(ControllerEntity e, Long chatId) {
        boolean isMuted = controllerPort.findSubscription(e.getId(), chatId)
            .map(s -> s.isMuted() || s.isPausedByTokens())
            .orElse(false);
        long eventCount = detectedEventPort.countByControllerId(e.getId());
        Long ownerTelegramId = e.getUser() != null ? e.getUser().getTelegramId() : null;
        return new ControllerDto(
            e.getId(), e.getBookmaker().name(), e.getUrl(), e.getTitle(),
            e.getFilterRule(), isMuted, Boolean.TRUE.equals(e.getIsActive()),
            e.getLastCheckedAt() != null ? e.getLastCheckedAt().toInstant() : null,
            e.getLastEventAt()   != null ? e.getLastEventAt().toInstant()   : null,
            (int) eventCount, e.getType(), e.getNotificationChatId(), ownerTelegramId
        );
    }

    private ControllerDto toDto(ControllerEntity e) {
        long eventCount = detectedEventPort.countByControllerId(e.getId());
        Long ownerTelegramId = e.getUser() != null ? e.getUser().getTelegramId() : null;
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
            e.getNotificationChatId(),
            ownerTelegramId
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
