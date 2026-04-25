package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LanguageSelectHandler implements CallbackHandler {

    private final UserService userService;
    private final BotMessageSource messageSource;

    @Override
    public String callbackPrefix() { return CallbackData.LANG_SET_PREFIX; }

    @Override
    public int order() { return 20; }

    @Override
    public void handle(BotUpdateContext ctx) {
        String langCode = ctx.update().getCallbackQuery().getData()
            .substring(CallbackData.LANG_SET_PREFIX.length());

        userService.updateLanguage(ctx.chatId(), langCode);
        messageSource.invalidateLocaleCache(ctx.chatId());

        log.info("Language changed: chatId={} lang={}", ctx.chatId(), langCode);
        MessageSend.text(ctx.sender(), ctx.chatId(),
            messageSource.getMessage("bot.language_changed", ctx.chatId()));
    }
}
