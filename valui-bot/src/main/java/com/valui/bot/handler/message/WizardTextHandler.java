package com.valui.bot.handler.message;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.BotUpdateHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.callback.ControllerConfirmCallback;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.service.BotSessionService;
import com.valui.bot.state.BotState;
import com.valui.bot.state.UserBotSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Captures free-text input when the wizard is waiting for an optional filter rule.
 * Validates the regex, stores it in session context, then shows the confirmation keyboard.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WizardTextHandler implements BotUpdateHandler {

    private final BotSessionService sessionService;
    private final BotMessageSource messageSource;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || update.getMessage().getText() == null) return false;
        String text = update.getMessage().getText();
        if (text.startsWith("/")) return false;  // let command handlers take over
        return true;  // state check is done in handle()
    }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        BotState state = ctx.session().getState();

        if (state != BotState.WAITING_FILTER_RULE) {
            // Not in filter step — fall through (but we matched first due to order)
            // Let UnknownUpdateHandler deal with it by not doing anything meaningful here.
            // Actually: send "unknown command" since we took priority over UnknownUpdateHandler.
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("bot.unknown_command", ctx.chatId()));
            return;
        }

        String filterText = ctx.update().getMessage().getText().trim();

        // Validate as a valid regex
        try {
            Pattern.compile(filterText);
        } catch (PatternSyntaxException e) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                messageSource.getMessage("wizard.filter_invalid_regex", ctx.chatId()));
            return;
        }

        sessionService.setStateAndMergeContext(ctx.chatId(), BotState.WAITING_CONFIRM_CREATE,
            Map.of(UserBotSession.CTX_FILTER, filterText));

        String confirmText = ControllerConfirmCallback.buildConfirmText(ctx.chatId(), sessionService, messageSource);
        var confirmKeyboard = ControllerConfirmCallback.buildConfirmKeyboard(ctx.chatId(), messageSource);

        java.util.Optional<String> wizardMsgIdOpt =
            sessionService.getContext(ctx.chatId(), UserBotSession.CTX_WIZARD_MSG_ID);
        if (wizardMsgIdOpt.isPresent()) {
            try {
                int wizardMsgId = Integer.parseInt(wizardMsgIdOpt.get());
                MessageSend.replaceWithKeyboard(ctx.sender(), ctx.chatId(), wizardMsgId,
                    confirmText, confirmKeyboard);
            } catch (NumberFormatException ex) {
                MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(), confirmText, confirmKeyboard);
            }
        } else {
            MessageSend.textWithKeyboard(ctx.sender(), ctx.chatId(), confirmText, confirmKeyboard);
        }
    }
}
