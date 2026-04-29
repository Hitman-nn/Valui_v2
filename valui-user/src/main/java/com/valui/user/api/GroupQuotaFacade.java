package com.valui.user.api;

import com.valui.user.dto.GroupStatusDto;

import java.util.UUID;

/**
 * Public API of valui-user for group-chat controller quota management.
 * External modules must depend on this interface, not on the internal {@code GroupQuotaService}.
 */
public interface GroupQuotaFacade {

    int getMaxControllers(Long chatId);

    int getActiveControllerCount(Long chatId);

    boolean hasCapacity(Long chatId);

    void checkGroupCapacity(Long chatId);

    GroupStatusDto getGroupStatus(Long chatId);

    void contributeTokens(Long telegramId, Long chatId, int tokens);

    void revokeAllContributions(UUID userId);
}
