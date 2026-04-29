package com.valui.bot.handler.message;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.BotUpdateHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.command.AddControllerHandler;
import com.valui.bot.handler.command.HelpCommandHandler;
import com.valui.bot.handler.command.InfoCommandHandler;
import com.valui.bot.handler.command.LanguageCommandHandler;
import com.valui.bot.handler.command.ListCommandHandler;
import com.valui.bot.handler.command.ListFilterCommandHandler;
import com.valui.bot.handler.command.StopCommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.service.BotSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Set;

/**
 * Intercepts presses of the ReplyKeyboard main-menu buttons.
 *
 * Button texts contain a unique emoji prefix; dispatch is emoji-based (locale-independent).
 *
 *   📡  →  AddControllerHandler
 *   📋  →  ListCommandHandler
 *   🔍  →  ListFilterCommandHandler
 *   🛑  →  StopCommandHandler
 *   ℹ️  →  InfoCommandHandler
 *   ❓  →  HelpCommandHandler
 *   🌍  →  LanguageCommandHandler
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuButtonHandler implements BotUpdateHandler {

    static final String BTN_ADD      = "📡";
    static final String BTN_LIST     = "📋";
    static final String BTN_FILTER   = "🔍";
    static final String BTN_STOP     = "🛑";
    static final String BTN_INFO     = "ℹ️";
    static final String BTN_HELP     = "❓";
    static final String BTN_LANGUAGE = "🌍";

    private static final Set<String> BUTTON_EMOJIS =
        Set.of(BTN_ADD, BTN_LIST, BTN_FILTER, BTN_STOP, BTN_INFO, BTN_HELP, BTN_LANGUAGE);

    private final BotMessageSource messageSource;
    private final BotSessionService sessionService;

    private final AddControllerHandler     addControllerHandler;
    private final ListCommandHandler       listCommandHandler;
    private final ListFilterCommandHandler listFilterCommandHandler;
    private final StopCommandHandler       stopCommandHandler;
    private final InfoCommandHandler       infoCommandHandler;
    private final HelpCommandHandler       helpCommandHandler;
    private final LanguageCommandHandler   languageCommandHandler;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage()) return false;
        String text = update.getMessage().getText();
        if (text == null || text.isBlank()) return false;
        return BUTTON_EMOJIS.stream().anyMatch(text::startsWith);
    }

    @Override
    public int order() { return 5; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.userInfo() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.user_not_registered", ctx.fromId()));
            return;
        }

        String text = ctx.update().getMessage().getText();

        if (text.startsWith(BTN_ADD))      { addControllerHandler.handle(ctx);      return; }
        if (text.startsWith(BTN_LIST))     { listCommandHandler.handle(ctx);         return; }
        if (text.startsWith(BTN_FILTER))   { listFilterCommandHandler.handle(ctx);  return; }
        if (text.startsWith(BTN_STOP))     { stopCommandHandler.handle(ctx);         return; }
        if (text.startsWith(BTN_INFO))     { infoCommandHandler.handle(ctx);         return; }
        if (text.startsWith(BTN_HELP))     { helpCommandHandler.handle(ctx);         return; }
        if (text.startsWith(BTN_LANGUAGE)) { languageCommandHandler.handle(ctx); }
    }
}
