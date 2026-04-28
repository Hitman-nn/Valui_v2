package com.valui.user.service;

import com.valui.common.annotation.Audit;
import com.valui.common.domain.SubscriptionStatus;
import com.valui.common.entity.SubscriptionEntity;
import com.valui.common.entity.SubscriptionPlanEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.dto.SubscriptionPlanDto;
import com.valui.user.event.SubscriptionExpiredEvent;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.SubscriptionPlanRepository;
import com.valui.user.repository.SubscriptionRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private static final String FREE_PLAN      = "FREE";
    private static final String PLANS_CACHE    = "plans";
    private static final int    DEFAULT_POLL_S = 120;
    private static final int    PAID_DAYS      = 30;

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final ControllerRepository controllerRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final GroupQuotaService groupQuotaService;

    @Override
    @Cacheable(value = PLANS_CACHE, key = "#telegramId", unless = "#result == null")
    public SubscriptionPlanDto getUserPlan(Long telegramId) {
        UserEntity user = requireUser(telegramId);
        SubscriptionEntity sub = requireActiveSub(user.getId());
        return SubscriptionPlanDto.from(sub.getPlan());
    }

    @Override
    public boolean canAddController(Long telegramId) {
        try {
            SubscriptionPlanDto plan = getUserPlan(telegramId);
            int used = controllerRepository.countByUserIdAndIsActiveTrue(requireUser(telegramId).getId());
            return used < plan.maxControllers();
        } catch (Exception e) {
            log.warn("canAddController check failed for telegramId={}: {}", telegramId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean canUseBookmaker(Long telegramId, String bookmaker) {
        try {
            return getUserPlan(telegramId).allowedBookmakers().stream()
                .anyMatch(b -> b.equalsIgnoreCase(bookmaker));
        } catch (Exception e) {
            log.warn("canUseBookmaker check failed for telegramId={}: {}", telegramId, e.getMessage());
            return false;
        }
    }

    @Override
    public int getPollInterval(Long telegramId) {
        try {
            return getUserPlan(telegramId).pollIntervalSec();
        } catch (Exception e) {
            return DEFAULT_POLL_S;
        }
    }

    @Override
    @Cacheable(value = PLANS_CACHE, key = "'all_active'")
    public List<SubscriptionPlanDto> getAllActivePlans() {
        return subscriptionPlanRepository.findAllByIsActiveTrue()
            .stream()
            .map(SubscriptionPlanDto::from)
            .sorted(Comparator.comparing(p -> p.priceRub() == null ? java.math.BigDecimal.ZERO : p.priceRub()))
            .toList();
    }

    @Override
    @Transactional
    @CacheEvict(value = PLANS_CACHE, allEntries = true)
    @Audit(action = "CHANGE_PLAN", entityType = "Subscription")
    public SubscriptionEntity activatePlan(UUID userId, String planCode, String paymentRef) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));

        SubscriptionPlanEntity plan = subscriptionPlanRepository.findByCode(planCode)
            .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planCode));

        // Cancel current active subscription
        subscriptionRepository
            .findTopByUserIdAndStatusOrderByStartedAtDesc(userId, SubscriptionStatus.ACTIVE)
            .ifPresent(old -> {
                old.setStatus(SubscriptionStatus.CANCELLED);
                subscriptionRepository.save(old);
            });

        boolean isPaid = plan.getPriceRub() != null && plan.getPriceRub().compareTo(BigDecimal.ZERO) > 0;
        OffsetDateTime expiresAt = isPaid ? OffsetDateTime.now().plusDays(PAID_DAYS) : null;

        // Grant tokens for this plan (one-time, additive to existing balance)
        int reward = plan.getTokenReward() != null ? plan.getTokenReward() : 0;
        if (reward > 0) {
            user.setTokenBalance((user.getTokenBalance() != null ? user.getTokenBalance() : 0) + reward);
            userRepository.save(user);
            log.info("🪙 Токены начислены: userId={} план={} reward={} newBalance={}",
                    userId, planCode, reward, user.getTokenBalance());
        }

        SubscriptionEntity newSub = SubscriptionEntity.builder()
            .user(user)
            .plan(plan)
            .status(SubscriptionStatus.ACTIVE)
            .paymentRef(paymentRef)
            .expiresAt(expiresAt)
            .build();
        SubscriptionEntity saved = subscriptionRepository.save(newSub);

        log.info("✅ Подписка активирована: userId={} план={} до={}", userId, planCode, expiresAt);
        return saved;
    }

    @Override
    @Transactional
    @CacheEvict(value = PLANS_CACHE, allEntries = true)
    public void expireSubscription(UUID subscriptionId) {
        SubscriptionEntity sub = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));

        String oldPlanCode = sub.getPlan().getCode();
        UserEntity user    = sub.getUser();

        sub.setStatus(SubscriptionStatus.EXPIRED);
        subscriptionRepository.save(sub);

        SubscriptionPlanEntity freePlan = subscriptionPlanRepository.findByCode(FREE_PLAN)
            .orElseThrow(() -> new IllegalStateException("FREE plan not found — check V2__seed_plans.sql"));

        SubscriptionEntity freeSub = SubscriptionEntity.builder()
            .user(user)
            .plan(freePlan)
            .status(SubscriptionStatus.ACTIVE)
            .build();
        subscriptionRepository.save(freeSub);

        // Revoke any group token contributions — shrinks group quotas and deactivates overflow
        groupQuotaService.revokeAllContributions(user.getId());

        eventPublisher.publishEvent(
            new SubscriptionExpiredEvent(user.getId(), user.getTelegramId(), oldPlanCode));

        log.info("⏰ Подписка истекла: subscriptionId={} userId={} план={} → FREE",
            subscriptionId, user.getId(), oldPlanCode);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private UserEntity requireUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));
    }

    private SubscriptionEntity requireActiveSub(UUID userId) {
        return subscriptionRepository
            .findTopByUserIdAndStatusOrderByStartedAtDesc(userId, SubscriptionStatus.ACTIVE)
            .orElseThrow(() -> new IllegalStateException(
                "No active subscription for userId=" + userId));
    }
}
