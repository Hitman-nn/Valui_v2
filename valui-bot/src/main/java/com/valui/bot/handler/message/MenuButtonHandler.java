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
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import com.valui.user.service.PlanLimitChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Set;

/**
 * Intercepts presses of the ReplyKeyboard main-menu buttons.
 *
 * Button texts (messages.properties) now contain NO command prefix — just emoji + label.
 * Each button starts with a unique emoji; we dispatch on that prefix so the mapping is
 * locale-independent and canHandle() never needs an i18n lookup.
 *
 *   ➕  →  AddControllerHandler
 *   📋  →  ListCommandHandler
 *   🔍  →  ListFilterCommandHandler
 *   🛑  →  StopCommandHandler
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
    static final String BTN_BOOST    = "🚀";

    private static final Set<String> BUTTON_EMOJIS =
        Set.of(BTN_ADD, BTN_LIST, BTN_FILTER, BTN_STOP, BTN_INFO, BTN_HELP, BTN_LANGUAGE, BTN_BOOST);

    private final BotMessageSource messageSource;
    private final BotSessionService sessionService;
    private final PlanLimitChecker planLimitChecker;

    // Delegate to the real command handlers — no logic duplication
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
    public int order() { return 5; }   // before commands (10) and text handler (50)

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.userInfo() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.user_not_registered", ctx.fromId()));
            return;
        }

        String text = ctx.update().getMessage().getText();

        if (text.startsWith(BTN_ADD))      { addControllerHandler.handle(ctx);       return; }
        if (text.startsWith(BTN_LIST))     { listCommandHandler.handle(ctx);          return; }
        if (text.startsWith(BTN_FILTER))   { listFilterCommandHandler.handle(ctx);   return; }
        if (text.startsWith(BTN_STOP))     { stopCommandHandler.handle(ctx);          return; }
        if (text.startsWith(BTN_INFO))     { infoCommandHandler.handle(ctx);          return; }
        if (text.startsWith(BTN_HELP))     { helpCommandHandler.handle(ctx);          return; }
        if (text.startsWith(BTN_LANGUAGE)) { languageCommandHandler.handle(ctx);      return; }
        if (text.startsWith(BTN_BOOST))    { handleBoost(ctx); }
    }

    private void handleBoost(BotUpdateContext ctx) {
        if (!ctx.isGroupChat()) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("boost.private_only", ctx.fromId()));
            return;
        }

        int tokenBalance;
        try {
            tokenBalance = planLimitChecker.getLimitInfo(ctx.fromId()).tokenBalance();
        } catch (Exception e) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("error.general", ctx.fromId()));
            return;
        }

        if (tokenBalance == 0) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("boost.no_tokens", ctx.fromId()));
            return;
        }

        sessionService.setStateWithContext(ctx.fromId(), BotState.WAITING_BOOST_AMOUNT,
            java.util.Map.of(UserBotSession.CTX_BOOST_CHAT_ID, String.valueOf(ctx.chatId())));

        var keyboard = InlineKeyboardBuilder.create()
            .button(messageSource.getMessage("menu.cancel", ctx.fromId()), CallbackData.CANCEL)
            .build();

        MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("boost.prompt", ctx.fromId(), tokenBalance),
            keyboard);
    }
}
