package com.valui.bot.handler.callback;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CallbackHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.vk.VkLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VkLinkCallback implements CallbackHandler {

    private final VkLinkService vkLinkService;

    @Override
    public String callbackPrefix() { return CallbackData.VK_LINK; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        MessageSend.answerCallback(ctx.sender(),
            ctx.update().getCallbackQuery().getId());

        if (ctx.user() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(), "Сначала зарегистрируйтесь: /start");
            return;
        }
        if (!vkLinkService.isEnabled()) {
            MessageSend.text(ctx.sender(), ctx.chatId(), "VK-уведомления пока не настроены.");
            return;
        }
        String code = vkLinkService.generateCode(ctx.user().getId(), ctx.chatId());
        String where = ctx.isGroupChat()
            ? "в беседу VK-сообщества, куда хотите получать уведомления"
            : "в личные сообщения VK-сообщества";
        MessageSend.text(ctx.sender(), ctx.chatId(),
            "Чтобы получать уведомления в VK:\n\n" +
            "1. Напишите " + where + ":\n" +
            code + "\n\n" +
            "Уведомления придут туда, откуда отправите код.\n" +
            "Код действует 15 минут.");
    }
}
