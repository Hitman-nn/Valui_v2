package com.valui.user.service;

import com.valui.common.domain.TokenReasonCode;
import com.valui.common.entity.ControllerEntity;
import com.valui.common.entity.ControllerSubscriptionEntity;
import com.valui.common.entity.TokenActionCostEntity;
import com.valui.common.entity.TokenTransactionEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.InsufficientTokensException;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.event.ControllerResumedEvent;
import com.valui.user.event.ControllerSuspendedEvent;
import com.valui.user.event.TokenThresholdEvent;
import com.valui.user.event.UserControllersPausedEvent;
import com.valui.user.event.UserControllersResumedEvent;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.ControllerSubscriptionRepository;
import com.valui.user.repository.GlobalFilterRepository;
import com.valui.user.repository.TokenActionCostRepository;
import com.valui.user.repository.TokenTransactionRepository;
import com.valui.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenLedgerServiceImpl implements TokenLedgerService {

    // Hardcoded thresholds removed — user configures a single threshold via Settings (tokenLowThreshold)

    /** Таймаут ожидания пессимистичной блокировки (мс) — защита от deadlock */
    private static final Map<String, Object> PESSIMISTIC_LOCK_HINTS =
        Map.of("jakarta.persistence.lock.timeout", 5_000);

    private final UserRepository                  userRepository;
    private final ControllerRepository            controllerRepository;
    private final GlobalFilterRepository          globalFilterRepository;
    private final ControllerSubscriptionRepository subscriptionRepository;
    private final TokenActionCostRepository       costRepository;
    private final TokenTransactionRepository      txRepository;
    private final ApplicationEventPublisher       eventPublisher;
    private final EntityManager                   em;

    // ─── getBalance ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public int getBalance(UUID userId) {
        return userRepository.findById(userId)
            .map(u -> u.getTokenBalance() != null ? u.getTokenBalance() : 0)
            .orElse(0);
    }

    @Override
    @Transactional(readOnly = true)
    public int getBalance(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
            .map(u -> u.getTokenBalance() != null ? u.getTokenBalance() : 0)
            .orElse(0);
    }

    @Override
    @Transactional(readOnly = true)
    public int getCost(String actionCode) {
        return costRepository.findById(actionCode)
            .map(TokenActionCostEntity::getCostTokens)
            .orElse(1);
    }

    // ─── credit ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public int credit(UUID userId, int amount, TokenReasonCode reason, UUID refId) {
        UserEntity user = lockUser(userId);
        int current = user.getTokenBalance() != null ? user.getTokenBalance() : 0;
        int newBalance = current + amount;
        user.setTokenBalance(newBalance);
        userRepository.save(user);
        recordTransaction(user, amount, reason, refId, newBalance);
        log.info("[TOKEN] +{} userId={} reason={} balance={}", amount, userId, reason, newBalance);

        if (newBalance > 0) {
            restoreTokenPausedControllers(userId);
            Integer userThreshold = user.getTokenLowThreshold();
            if (userThreshold != null && newBalance >= userThreshold && user.isTokenAlertSent()) {
                user.setTokenAlertSent(false);
                userRepository.save(user);
            }
        }
        return newBalance;
    }

    @Override
    @Transactional
    public int credit(Long telegramId, int amount, TokenReasonCode reason, UUID refId) {
        UserEntity user = userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
        return credit(user.getId(), amount, reason, refId);
    }

    // ─── debit ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public int debit(UUID userId, int amount, TokenReasonCode reason, UUID refId) {
        UserEntity user = lockUser(userId);
        int balance = user.getTokenBalance() != null ? user.getTokenBalance() : 0;
        if (balance < amount) {
            throw new InsufficientTokensException(amount, balance);
        }
        int newBalance = balance - amount;
        user.setTokenBalance(newBalance);
        userRepository.save(user);
        recordTransaction(user, -amount, reason, refId, newBalance);
        log.debug("[TOKEN] -{} userId={} reason={} balance={}", amount, userId, reason, newBalance);

        checkAndNotifyThresholds(user, newBalance);
        if (newBalance == 0) {
            doPauseAllControllers(userId, user.getTelegramId());
        }
        return newBalance;
    }

    @Override
    @Transactional
    public int debit(Long telegramId, int amount, TokenReasonCode reason, UUID refId) {
        UserEntity user = userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
        return debit(user.getId(), amount, reason, refId);
    }

    // ─── tryDebit ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public boolean tryDebit(UUID userId, int amount, TokenReasonCode reason, UUID refId) {
        try {
            debit(userId, amount, reason, refId);
            return true;
        } catch (InsufficientTokensException e) {
            return false;
        }
    }

    @Override
    @Transactional
    public boolean tryDebit(Long telegramId, int amount, TokenReasonCode reason, UUID refId) {
        UserEntity user = userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
        return tryDebit(user.getId(), amount, reason, refId);
    }

    // ─── controller pause / restore ──────────────────────────────────────────

    @Override
    @Transactional
    public void pauseAllControllers(UUID userId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new com.valui.common.exception.UserNotFoundException(userId));
        doPauseAllControllers(userId, user.getTelegramId());
    }

    private void doPauseAllControllers(UUID userId, long telegramId) {
        subscriptionRepository.updatePausedByTokensForUser(userId, true);

        List<ControllerEntity> active = controllerRepository.findAllByUserIdAndIsActiveTrue(userId);
        List<Long> suspendedChatIds = new ArrayList<>();
        boolean hasPersonalChatCtrl = false;
        for (ControllerEntity c : active) {
            if (!subscriptionRepository.existsByControllerIdAndIsMutedFalseAndPausedByTokensFalse(c.getId())) {
                eventPublisher.publishEvent(new ControllerSuspendedEvent(c.getId()));
                if (c.getNotificationChatId() != null) {
                    suspendedChatIds.add(c.getNotificationChatId());
                } else {
                    hasPersonalChatCtrl = true;
                }
            }
        }

        List<Long> distinctChats = suspendedChatIds.stream().distinct().collect(Collectors.toList());
        if (hasPersonalChatCtrl) distinctChats.add(0, telegramId);
        if (!distinctChats.isEmpty()) {
            eventPublisher.publishEvent(new UserControllersPausedEvent(telegramId, distinctChats));
        }
        log.info("[TOKEN] Паузим подписки userId={}", userId);
    }

    @Override
    @Transactional
    public void restoreTokenPausedControllers(UUID userId) {
        List<ControllerSubscriptionEntity> paused = subscriptionRepository.findAllByUserIdAndPausedByTokensTrue(userId);
        subscriptionRepository.updatePausedByTokensForUser(userId, false);

        List<Long> resumedChatIds = new ArrayList<>();
        boolean hasPersonalChatCtrl = false;
        List<UUID> controllerIds = paused.stream()
            .map(ControllerSubscriptionEntity::getControllerId)
            .distinct()
            .toList();
        for (ControllerEntity c : controllerRepository.findAllById(controllerIds)) {
            int pollInterval = c.getPollIntervalSec() != null ? c.getPollIntervalSec() : 60;
            eventPublisher.publishEvent(new ControllerResumedEvent(c.getId(), userId, pollInterval));
            if (c.getNotificationChatId() != null) {
                resumedChatIds.add(c.getNotificationChatId());
            } else {
                hasPersonalChatCtrl = true;
            }
        }

        int restoredCtrlFilters = controllerRepository.restoreFilterPauseForUser(userId);
        globalFilterRepository.unpauseAllByUserId(userId);

        List<Long> distinctChats = resumedChatIds.stream().distinct().collect(Collectors.toList());
        if (hasPersonalChatCtrl) {
            UserEntity u = userRepository.findById(userId)
                .orElseThrow(() -> new com.valui.common.exception.UserNotFoundException(userId));
            distinctChats.add(0, u.getTelegramId());
            eventPublisher.publishEvent(new UserControllersResumedEvent(u.getTelegramId(), distinctChats));
        } else if (!distinctChats.isEmpty()) {
            userRepository.findById(userId).ifPresent(u ->
                eventPublisher.publishEvent(new UserControllersResumedEvent(u.getTelegramId(), distinctChats)));
        }

        log.info("[TOKEN] Восстановили подписки+фильтры userId={} ctrlFilters={}", userId, restoredCtrlFilters);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private UserEntity lockUser(UUID userId) {
        UserEntity user = em.find(UserEntity.class, userId,
            LockModeType.PESSIMISTIC_WRITE, PESSIMISTIC_LOCK_HINTS);
        if (user == null) throw new UserNotFoundException(userId);
        return user;
    }

    private void recordTransaction(UserEntity user, int delta, TokenReasonCode reason, UUID refId, int balanceAfter) {
        txRepository.save(TokenTransactionEntity.builder()
            .user(user)
            .delta(delta)
            .reasonCode(reason)
            .refId(refId)
            .balanceAfter(balanceAfter)
            .build());
    }

    private void checkAndNotifyThresholds(UserEntity user, int newBalance) {
        Integer userThreshold = user.getTokenLowThreshold();
        if (userThreshold == null) return;
        if (newBalance < userThreshold && !user.isTokenAlertSent()) {
            user.setTokenAlertSent(true);
            userRepository.save(user);
            eventPublisher.publishEvent(
                new TokenThresholdEvent(this, user.getTelegramId(), newBalance, userThreshold));
            log.info("[TOKEN] Порог {} токенов для userId={} balance={}", userThreshold, user.getId(), newBalance);
        }
    }
}
