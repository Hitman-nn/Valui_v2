package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.command.SettingsCommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.user.api.ControllerPortService;
import com.valui.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettingsCallback implements CallbackHandler {

    private final UserService           userService;
    private final BotMessageSource      messageSource;
    private final ControllerPortService controllerPort;
    private final SettingsCommandHandler settingsHandler;

    @Override
    public String callbackPrefix() { return "SETT:"; }

    @Override
    public int order() { return 25; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) return;

        String data       = ctx.update().getCallbackQuery().getData();
        String callbackId = ctx.update().getCallbackQuery().getId();
        int    messageId  = ctx.update().getCallbackQuery().getMessage().getMessageId();

        if (data.startsWith(CallbackData.SETT_LANG_PREFIX)) {
            String langCode = data.substring(CallbackData.SETT_LANG_PREFIX.length());
            userService.updateLanguage(ctx.fromId(), langCode);
            messageSource.invalidateLocaleCache(ctx.chatId());
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                messageSource.getMessage("settings.lang_changed", ctx.fromId()));
            rebuildSettings(ctx, messageId, langCode, ctx.user().getTokenLowThreshold());

        } else if (data.startsWith(CallbackData.SETT_TOK_PREFIX)) {
            Integer threshold;
            if (CallbackData.SETT_TOK_OFF.equals(data)) {
                threshold = null;
            } else {
                try {
                    threshold = Integer.parseInt(data.substring(CallbackData.SETT_TOK_PREFIX.length()));
                } catch (NumberFormatException e) {
                    MessageSend.answerCallback(ctx.sender(), callbackId);
                    return;
                }
            }
            userService.setTokenLowThreshold(ctx.user().getId(), threshold);
            MessageSend.answerCallbackWithAlert(ctx.sender(), callbackId,
                messageSource.getMessage("settings.saved", ctx.fromId()));
            rebuildSettings(ctx, messageId, ctx.user().getLanguageCode(), threshold);

        } else {
            MessageSend.answerCallback(ctx.sender(), callbackId);
        }
    }

    private void rebuildSettings(BotUpdateContext ctx, int messageId, String langCode, Integer threshold) {
        boolean vkLinked = controllerPort.hasVkLinked(ctx.user().getId(), ctx.chatId());
        String  text     = settingsHandler.buildText(vkLinked, threshold, langCode, ctx.fromId());
        var     keyboard = settingsHandler.buildKeyboard(vkLinked, threshold, ctx.fromId());
        MessageSend.editMarkdownWithKeyboard(ctx.sender(), ctx.chatId(), messageId, text, keyboard);
    }
}
