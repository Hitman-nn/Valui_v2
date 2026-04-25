package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.BotUpdateHandler;
import com.valui.bot.i18n.BotMessageSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Fallback handler: catches every update not matched by any other handler.
 * Replies only to text messages; silently ignores callbacks and other update types.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnknownUpdateHandler implements BotUpdateHandler {

    private final BotMessageSource messageSource;

    @Override
    public boolean canHandle(Update update) { return true; }

    @Override
    public int order() { return 999; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (!ctx.update().hasMessage()) {
            log.debug("UnknownUpdateHandler: non-message update for chatId={} — ignored", ctx.chatId());
            return;
        }
        log.debug("Unknown command from chatId={}", ctx.chatId());
        MessageSend.text(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("bot.unknown_command", ctx.chatId()));
    }
}
