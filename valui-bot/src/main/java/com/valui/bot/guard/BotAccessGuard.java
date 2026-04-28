package com.valui.bot.guard;

import com.valui.bot.keyboard.MenuMessage;
import com.valui.bot.keyboard.menu.UpgradePromptBuilder;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.service.PlanLimitChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Checks subscription limits before bot operations.
 * On limit violation, sends an upgrade prompt to the user and re-throws so the caller can abort.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotAccessGuard {

    private final PlanLimitChecker planLimitChecker;

    /**
     * Checks controller limit. Sends upgrade prompt and throws if exceeded.
     * Callers should catch {@link SubscriptionLimitExceededException} and return early.
     *
     * @param fromId  personal Telegram ID of the user (for plan lookup)
     * @param chatId  destination chat ID (where to send the upgrade prompt — may be a group)
     */
    public void guardAddController(Long fromId, Long chatId, AbsSender sender) {
        guard(fromId, chatId, sender, () -> planLimitChecker.checkControllerLimit(fromId), "controllers");
    }

    /** Checks whether the bookmaker is included in the user's plan. */
    public void guardBookmakerAccess(Long fromId, Long chatId, String bookmaker, AbsSender sender) {
        guard(fromId, chatId, sender,
            () -> planLimitChecker.checkBookmakerAccess(fromId, bookmaker), "bookmaker");
    }

    /** Checks filter limit. */
    public void guardAddFilter(Long fromId, Long chatId, AbsSender sender) {
        guard(fromId, chatId, sender, () -> planLimitChecker.checkFilterLimit(fromId), "filters");
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private void guard(Long fromId, Long chatId, AbsSender sender, Runnable check, String limitType) {
        try {
            check.run();
        } catch (SubscriptionLimitExceededException e) {
            log.info("Limit exceeded: fromId={} chatId={} limitType={}", fromId, chatId, limitType);
            sendUpgradePrompt(fromId, chatId, sender, limitType);
            throw e;
        }
    }

    private void sendUpgradePrompt(Long fromId, Long chatId, AbsSender sender, String limitType) {
        try {
            LimitInfoDto limits = planLimitChecker.getLimitInfo(fromId);
            MenuMessage prompt = UpgradePromptBuilder.build(limits, limitType);
            sender.execute(SendMessage.builder()
                .chatId(chatId)
                .text(prompt.text())
                .replyMarkup(prompt.keyboard())
                .build());
        } catch (TelegramApiException te) {
            log.error("Failed to send upgrade prompt to chatId={}", chatId, te);
        }
    }
}
