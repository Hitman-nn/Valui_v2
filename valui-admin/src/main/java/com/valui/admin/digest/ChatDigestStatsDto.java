package com.valui.admin.digest;

/** Per-chat digest numbers for one group chat with ≥1 active controller. */
public record ChatDigestStatsDto(
        Long chatId,
        long activeControllers,
        long activeBookmakers,
        long mutedControllers,
        long pausedByTokensControllers,
        long notificationsThisWeek,
        long notificationsLastWeek,
        long staleControllers,
        /** Sum of token spend by every user with ≥1 active controller in this chat — see
         *  {@code ControllerSubscriptionRepository.sumTokensSpentByChat} for why this is an
         *  approximation (per-user, not per-chat, spend), not exact chat-scoped billing. */
        long tokensSpent
) {}
