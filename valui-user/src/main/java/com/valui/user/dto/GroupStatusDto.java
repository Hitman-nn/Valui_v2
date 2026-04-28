package com.valui.user.dto;

import java.util.List;

/**
 * Snapshot of a group chat's controller quota for display in the bot.
 */
public record GroupStatusDto(
    long chatId,
    int activeControllers,
    int maxControllers,
    int freeSlots,
    List<ContributorDto> contributors
) {
    public record ContributorDto(String username, Long telegramId, int tokensCommitted) {}
}
