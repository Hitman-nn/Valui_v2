package com.valui.user.service;

import com.valui.common.annotation.Audit;
import com.valui.common.domain.SubscriptionStatus;
import com.valui.common.domain.TokenReasonCode;
import com.valui.common.entity.SubscriptionEntity;
import com.valui.common.entity.SubscriptionPlanEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.dto.PlanStatsDto;
import com.valui.user.dto.SubscriptionPlanDto;
import com.valui.user.event.SubscriptionExpiredEvent;
import com.valui.user.repository.SubscriptionPlanRepository;
import com.valui.user.repository.SubscriptionRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private static final String FREE_PLAN   = "FREE";
    private static final String PLANS_CACHE = "plans";
    private static final int    PAID_DAYS   = 30;

    private final UserRepository             userRepository;
    private final SubscriptionRepository     subscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final ApplicationEventPublisher  eventPublisher;
    private final TokenLedgerService         tokenLedgerService;

    @Override
    @Cacheable(value = PLANS_CACHE, key = "#telegramId", unless = "#result == null")
    public SubscriptionPlanDto getUserPlan(Long telegramId) {
        UserEntity user = requireUser(telegramId);
        SubscriptionEntity sub = requireActiveSub(user.getId());
        return SubscriptionPlanDto.from(sub.getPlan());
    }

    @Override
    public int getPollInterval(Long telegramId) {
        try {
            return getUserPlan(telegramId).pollIntervalSec();
        } catch (Exception e) {
            return 120;
        }
    }

    @Override
    @Cacheable(value = PLANS_CACHE, key = "'all_active'")
    public List<SubscriptionPlanDto> getAllActivePlans() {
        return subscriptionPlanRepository.findAllByIsActiveTrue()
            .stream()
            .map(SubscriptionPlanDto::from)
            .sorted(Comparator.comparing(p -> p.priceRub() == null ? BigDecimal.ZERO : p.priceRub()))
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

        subscriptionRepository
            .findTopByUserIdAndStatusOrderByStartedAtDesc(userId, SubscriptionStatus.ACTIVE)
            .ifPresent(old -> {
                old.setStatus(SubscriptionStatus.CANCELLED);
                subscriptionRepository.save(old);
            });

        boolean isPaid = plan.getPriceRub() != null && plan.getPriceRub().compareTo(BigDecimal.ZERO) > 0;
        OffsetDateTime expiresAt = isPaid ? OffsetDateTime.now().plusDays(PAID_DAYS) : null;

        // Разовый token_reward при активации плана
        int reward = plan.getTokenReward() != null ? plan.getTokenReward() : 0;
        if (reward > 0) {
            tokenLedgerService.credit(userId, reward, TokenReasonCode.PLAN_GRANT, null);
            log.info("[PLAN] Разовый грант: userId={} план={} +{} токенов", userId, planCode, reward);
        }

        SubscriptionEntity newSub = SubscriptionEntity.builder()
            .user(user)
            .plan(plan)
            .status(SubscriptionStatus.ACTIVE)
            .paymentRef(paymentRef)
            .expiresAt(expiresAt)
            .build();
        SubscriptionEntity saved = subscriptionRepository.save(newSub);

        log.info("[PLAN] Подписка активирована: userId={} план={} до={}", userId, planCode, expiresAt);
        return saved;
    }

    @Override
    @Transactional
    @CacheEvict(value = PLANS_CACHE, allEntries = true)
    public void expireSubscription(UUID subscriptionId) {
        SubscriptionEntity sub = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));

        String oldPlanCode = sub.getPlan().getCode();
        UserEntity user = sub.getUser();

        sub.setStatus(SubscriptionStatus.EXPIRED);
        subscriptionRepository.save(sub);

        SubscriptionPlanEntity freePlan = subscriptionPlanRepository.findByCode(FREE_PLAN)
            .orElseThrow(() -> new IllegalStateException("FREE plan not found"));

        SubscriptionEntity freeSub = SubscriptionEntity.builder()
            .user(user)
            .plan(freePlan)
            .status(SubscriptionStatus.ACTIVE)
            .build();
        subscriptionRepository.save(freeSub);

        eventPublisher.publishEvent(
            new SubscriptionExpiredEvent(user.getId(), user.getTelegramId(), oldPlanCode));

        log.info("[PLAN] Подписка истекла: subscriptionId={} userId={} план={} → FREE",
            subscriptionId, user.getId(), oldPlanCode);
    }

    // ─── Admin-only operations ────────────────────────────────────────────────

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public Page<SubscriptionEntity> findAllActive(Pageable pageable) {
        return subscriptionRepository.findAllActiveWithDetails(pageable);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public List<SubscriptionEntity> findExpiringSoon(OffsetDateTime from, OffsetDateTime to) {
        return subscriptionRepository.findExpiringBetween(from, to);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public List<PlanStatsDto> getStatsByPlan() {
        return subscriptionRepository.countActiveGroupedByPlan()
            .stream()
            .map(row -> new PlanStatsDto((String) row[0], (String) row[1], (Long) row[2]))
            .toList();
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = PLANS_CACHE, allEntries = true)
    @Audit(action = "GRANT_PLAN", entityType = "Subscription")
    public void grantPlan(UUID userId, String planCode) {
        activatePlan(userId, planCode, "admin-grant");
        log.info("[ADMIN] Plan granted: userId={} plan={}", userId, planCode);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public List<Long> findActiveTelegramIdsByPlan(String planCode) {
        return subscriptionRepository.findActiveTelegramIdsByPlan(planCode);
    }

    @Override
    public java.util.Optional<SubscriptionEntity> findActiveByUserId(UUID userId) {
        return subscriptionRepository.findTopByUserIdAndStatusOrderByStartedAtDesc(userId, SubscriptionStatus.ACTIVE);
    }

    @Override
    @Transactional
    public SubscriptionEntity updateSubscriptionDates(UUID userId, java.time.OffsetDateTime startedAt, java.time.OffsetDateTime expiresAt) {
        SubscriptionEntity sub = subscriptionRepository
            .findTopByUserIdAndStatusOrderByStartedAtDesc(userId, SubscriptionStatus.ACTIVE)
            .orElseThrow(() -> new IllegalStateException("No active subscription for userId=" + userId));
        if (startedAt != null)  sub.setStartedAt(startedAt);
        if (expiresAt != null)  sub.setExpiresAt(expiresAt);
        return subscriptionRepository.save(sub);
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
