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
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.ControllerSubscriptionRepository;
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

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenLedgerServiceImpl implements TokenLedgerService {

    private static final int[] THRESHOLDS = {5, 10, 20};

    private final UserRepository                  userRepository;
    private final ControllerRepository            controllerRepository;
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
        int newBalance = user.getTokenBalance() + amount;
        user.setTokenBalance(newBalance);
        userRepository.save(user);
        recordTransaction(user, amount, reason, refId, newBalance);
        log.info("[TOKEN] +{} userId={} reason={} balance={}", amount, userId, reason, newBalance);

        if (newBalance > 0) {
            restoreTokenPausedControllers(userId);
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
        int balance = user.getTokenBalance();
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
            pauseAllControllers(userId);
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
        // Mark all subscriptions as paused
        subscriptionRepository.updatePausedByTokensForUser(userId, true);

        // For each controller that now has NO active subscriptions: publish suspend event
        List<ControllerEntity> active = controllerRepository.findAllByUserIdAndIsActiveTrue(userId);
        for (ControllerEntity c : active) {
            if (!subscriptionRepository.existsByControllerIdAndIsMutedFalseAndPausedByTokensFalse(c.getId())) {
                eventPublisher.publishEvent(new ControllerSuspendedEvent(c.getId()));
            }
        }
        log.info("[TOKEN] Паузим подписки userId={}", userId);
    }

    @Override
    @Transactional
    public void restoreTokenPausedControllers(UUID userId) {
        List<ControllerSubscriptionEntity> paused = subscriptionRepository.findAllByUserIdAndPausedByTokensTrue(userId);
        subscriptionRepository.updatePausedByTokensForUser(userId, false);

        // For each affected controller: publish resume event
        paused.stream()
            .map(ControllerSubscriptionEntity::getControllerId)
            .distinct()
            .forEach(controllerId -> controllerRepository.findById(controllerId).ifPresent(c -> {
                int pollInterval = c.getPollIntervalSec() != null ? c.getPollIntervalSec() : 60;
                eventPublisher.publishEvent(new ControllerResumedEvent(c.getId(), c.getUser().getId(), pollInterval));
            }));
        log.info("[TOKEN] Восстановили подписки userId={}", userId);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private UserEntity lockUser(UUID userId) {
        UserEntity user = em.find(UserEntity.class, userId, LockModeType.PESSIMISTIC_WRITE);
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
        int grantRef = user.getTokenMonthlyGrantRef() != null ? user.getTokenMonthlyGrantRef() : 0;
        if (grantRef <= 0) return;

        int pct = (newBalance * 100) / grantRef;
        int lastNotified = user.getTokenLowThresholdPct() != null ? user.getTokenLowThresholdPct() : 100;

        for (int threshold : THRESHOLDS) {
            if (pct <= threshold && lastNotified > threshold) {
                user.setTokenLowThresholdPct(threshold);
                userRepository.save(user);
                eventPublisher.publishEvent(
                    new TokenThresholdEvent(this, user.getTelegramId(), newBalance, threshold));
                log.info("[TOKEN] Порог {}% для userId={} balance={}", threshold, user.getId(), newBalance);
                break;
            }
        }
    }
}
