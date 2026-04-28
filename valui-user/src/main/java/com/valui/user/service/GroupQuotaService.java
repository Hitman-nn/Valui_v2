package com.valui.user.service;

import com.valui.common.entity.GroupChatQuotaEntity;
import com.valui.common.entity.GroupTokenContributionEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.common.exception.UserNotFoundException;
import com.valui.common.exception.ValuiException;
import com.valui.user.dto.GroupStatusDto;
import com.valui.user.repository.ControllerRepository;
import com.valui.user.repository.GroupChatQuotaRepository;
import com.valui.user.repository.GroupTokenContributionRepository;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages the controller quota for Telegram group chats.
 *
 * Model:
 *   - Base quota: 3 free slots per group (no paid plan required)
 *   - Tokens: paid-plan users can commit token_balance tokens → each token = +1 group slot
 *   - Personal quota (plan max_controllers) is SEPARATE — both checks must pass to add a controller
 *   - On subscription expiry: user's committed tokens are revoked and overflow deactivated
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupQuotaService {

    static final int FREE_GROUP_SLOTS = 3;

    private final GroupChatQuotaRepository quotaRepository;
    private final GroupTokenContributionRepository contributionRepository;
    private final ControllerRepository controllerRepository;
    private final UserRepository userRepository;

    /** Returns max allowed controllers in the group (free baseline + committed tokens). */
    @Transactional(readOnly = true)
    public int getMaxControllers(Long chatId) {
        return quotaRepository.findById(chatId)
                .map(GroupChatQuotaEntity::getMaxControllers)
                .orElse(FREE_GROUP_SLOTS);
    }

    /** Returns the current active controller count for this group. */
    @Transactional(readOnly = true)
    public int getActiveControllerCount(Long chatId) {
        return controllerRepository.countByNotificationChatIdAndIsActiveTrue(chatId);
    }

    /** Returns true if the group can accept one more controller. */
    @Transactional(readOnly = true)
    public boolean hasCapacity(Long chatId) {
        return getActiveControllerCount(chatId) < getMaxControllers(chatId);
    }

    /**
     * Throws {@link SubscriptionLimitExceededException} if the group is at capacity.
     * Call this only when the notification target is a group (chatId < 0).
     */
    @Transactional(readOnly = true)
    public void checkGroupCapacity(Long chatId) {
        if (!hasCapacity(chatId)) {
            throw new SubscriptionLimitExceededException("group controllers", getMaxControllers(chatId));
        }
    }

    /**
     * Returns a status snapshot for the given group chat: quota, active count, contributor list.
     */
    @Transactional(readOnly = true)
    public GroupStatusDto getGroupStatus(Long chatId) {
        int active = getActiveControllerCount(chatId);
        int max    = getMaxControllers(chatId);

        List<GroupStatusDto.ContributorDto> contributors = contributionRepository
                .findAllByChatIdAndTokensCommittedGreaterThan(chatId, 0)
                .stream()
                .map(c -> new GroupStatusDto.ContributorDto(
                        c.getUser().getUsername(),
                        c.getUser().getTelegramId(),
                        c.getTokensCommitted()))
                .toList();

        return new GroupStatusDto(chatId, active, max, max - active, contributors);
    }

    /**
     * Spends {@code tokens} from the user's balance and commits them to the group quota.
     * Adds to any existing contribution. Group's max_controllers increases by the same amount.
     *
     * @throws ValuiException 402 if the user doesn't have enough token balance
     */
    @Transactional
    public void contributeTokens(Long telegramId, Long chatId, int tokens) {
        if (tokens <= 0) throw new ValuiException("Tokens must be positive", 400);

        UserEntity user = requireUser(telegramId);
        if (user.getTokenBalance() < tokens) {
            throw new ValuiException("Недостаточно токенов. Доступно: " + user.getTokenBalance(), 402);
        }

        // Spend tokens from user balance
        user.setTokenBalance(user.getTokenBalance() - tokens);
        userRepository.save(user);

        // Record contribution (upsert)
        GroupTokenContributionEntity contribution = contributionRepository
                .findByChatIdAndUserId(chatId, user.getId())
                .orElseGet(() -> GroupTokenContributionEntity.builder()
                        .chatId(chatId)
                        .user(user)
                        .tokensCommitted(0)
                        .build());
        contribution.setTokensCommitted(contribution.getTokensCommitted() + tokens);
        contributionRepository.save(contribution);

        // Expand group quota
        GroupChatQuotaEntity quota = quotaRepository.findById(chatId)
                .orElseGet(() -> GroupChatQuotaEntity.builder()
                        .chatId(chatId)
                        .maxControllers(FREE_GROUP_SLOTS)
                        .build());
        quota.setMaxControllers(quota.getMaxControllers() + tokens);
        quotaRepository.save(quota);

        log.info("[GROUP-QUOTA] chatId={} telegramId={} contributed {} tokens → group max={}",
                chatId, telegramId, tokens, quota.getMaxControllers());
    }

    /**
     * Called on subscription expiry: revokes all tokens the user committed to groups,
     * shrinks those groups' quotas, and deactivates controllers that exceed the new limit.
     * Returns the user's token balance to 0.
     */
    @Transactional
    public void revokeAllContributions(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException((Long) null));

        List<GroupTokenContributionEntity> contributions = contributionRepository.findAllByUserId(userId);

        for (GroupTokenContributionEntity contribution : contributions) {
            if (contribution.getTokensCommitted() == 0) continue;

            Long chatId = contribution.getChatId();
            int revoked = contribution.getTokensCommitted();

            quotaRepository.findById(chatId).ifPresent(quota -> {
                int newMax = Math.max(FREE_GROUP_SLOTS, quota.getMaxControllers() - revoked);
                quota.setMaxControllers(newMax);
                quotaRepository.save(quota);

                deactivateOverflow(chatId, newMax, userId);

                log.info("[GROUP-QUOTA] Revoked {} tokens from chatId={} → new max={}",
                        revoked, chatId, newMax);
            });

            contribution.setTokensCommitted(0);
            contributionRepository.save(contribution);
        }

        user.setTokenBalance(0);
        userRepository.save(user);
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void deactivateOverflow(Long chatId, int newMax) {
        var active = controllerRepository.findAllByNotificationChatIdAndIsActiveTrue(chatId);
        if (active.size() <= newMax) return;

        // Per spec: deactivate the expired user's own controllers first, then others.
        // We don't know the userId here so pass it from the caller via the contribution loop.
        int toDeactivate = active.size() - newMax;
        active.stream()
                .limit(toDeactivate)
                .forEach(c -> {
                    c.setIsActive(false);
                    controllerRepository.save(c);
                    log.info("[GROUP-QUOTA] Deactivated overflow controller id={} owner={} chatId={}",
                            c.getId(), c.getUser().getId(), chatId);
                });
    }

    private void deactivateOverflow(Long chatId, int newMax, UUID expiredUserId) {
        var active = controllerRepository.findAllByNotificationChatIdAndIsActiveTrue(chatId);
        if (active.size() <= newMax) return;

        // Expired user's own controllers first, then others by insertion order
        var sorted = active.stream()
                .sorted((a, b) -> {
                    boolean aOwned = expiredUserId.equals(a.getUser().getId());
                    boolean bOwned = expiredUserId.equals(b.getUser().getId());
                    return Boolean.compare(bOwned, aOwned); // owned = lower index = deactivated first
                })
                .toList();

        int toDeactivate = sorted.size() - newMax;
        sorted.stream()
                .limit(toDeactivate)
                .forEach(c -> {
                    c.setIsActive(false);
                    controllerRepository.save(c);
                    log.info("[GROUP-QUOTA] Deactivated overflow controller id={} owner={} chatId={}",
                            c.getId(), c.getUser().getId(), chatId);
                });
    }

    private UserEntity requireUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new UserNotFoundException(telegramId));
    }
}
