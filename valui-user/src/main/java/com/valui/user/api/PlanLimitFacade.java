package com.valui.user.api;

import com.valui.user.dto.LimitInfoDto;

/**
 * Public API of valui-user for subscription-plan limit checks.
 * External modules (valui-bot, valui-monitor) must depend on this interface,
 * not on the internal {@code PlanLimitChecker} component.
 */
public interface PlanLimitFacade {

    void checkControllerLimit(Long telegramId);

    void checkGroupCapacity(Long notificationChatId);

    void checkBookmakerAccess(Long telegramId, String bookmaker);

    void checkFilterLimit(Long telegramId);

    LimitInfoDto getLimitInfo(Long telegramId);
}
