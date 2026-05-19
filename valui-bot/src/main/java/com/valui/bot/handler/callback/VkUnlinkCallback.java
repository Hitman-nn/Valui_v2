package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.handler.command.SettingsCommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.user.api.ControllerPortService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VkUnlinkCallback implements CallbackHandler {

    private final ControllerPortService controllerPort;
    private final SettingsCommandHandler settingsCommandHandler;
    private final BotMessageSource msg;

    @Override
    public String callbackPrefix() { return CallbackData.VK_UNLINK; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) {
            MessageSend.answerCallbackWithAlert(ctx.sender(),
                ctx.update().getCallbackQuery().getId(),
                "Сначала зарегистрируйтесь: /start");
            return;
        }

        controllerPort.unlinkVkForChat(ctx.user().getId(), ctx.chatId());

        MessageSend.answerCallbackWithAlert(ctx.sender(),
            ctx.update().getCallbackQuery().getId(),
            msg.getMessage("settings.vk_unlinked", ctx.fromId()));

        int messageId = ctx.update().getCallbackQuery().getMessage().getMessageId();
        MessageSend.editTextWithKeyboard(ctx.sender(), ctx.chatId(), messageId,
            settingsCommandHandler.buildText(false, ctx.fromId()),
            settingsCommandHandler.buildKeyboard(false, ctx.fromId()));
    }
}
