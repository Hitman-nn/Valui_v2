package com.valui.bot.guard;

import com.valui.bot.keyboard.MenuMessage;
import com.valui.bot.keyboard.menu.UpgradePromptBuilder;
import com.valui.common.exception.InsufficientTokensException;
import com.valui.user.dto.LimitInfoDto;
import com.valui.user.api.PlanLimitFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Проверяет токенный баланс перед операциями бота.
 * При нехватке токенов — отправляет подсказку пользователю.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotAccessGuard {

    private final PlanLimitFacade planLimitFacade;

    /**
     * Проверяет наличие токенов для нового BK-слота.
     * При нехватке отправляет сообщение и бросает {@link InsufficientTokensException}.
     */
    public void guardAddController(Long fromId, Long chatId, String bookmaker, AbsSender sender) {
        guard(fromId, chatId, sender,
            () -> planLimitFacade.debitForBkSlotIfNew(fromId, bookmaker),
            "tokens");
    }

    /** Проверяет наличие токенов для добавления фильтра на контроллер. */
    public void guardAddFilter(Long fromId, Long chatId, AbsSender sender) {
        guard(fromId, chatId, sender,
            () -> planLimitFacade.debitForControllerFilter(fromId),
            "tokens");
    }

    // ─── private ─────────────────────────────────────────────────────────────

    private void guard(Long fromId, Long chatId, AbsSender sender, Runnable check, String limitType) {
        try {
            check.run();
        } catch (InsufficientTokensException e) {
            log.info("[GUARD] Нехватка токенов: fromId={} chatId={}", fromId, chatId);
            sendUpgradePrompt(fromId, chatId, sender, limitType);
            throw e;
        }
    }

    private void sendUpgradePrompt(Long fromId, Long chatId, AbsSender sender, String limitType) {
        try {
            LimitInfoDto limits = planLimitFacade.getLimitInfo(fromId);
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
