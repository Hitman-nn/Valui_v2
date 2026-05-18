package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.vk.VkLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VkLinkCommandHandler implements CommandHandler {

    private final VkLinkService vkLinkService;

    @Override
    public String command() { return "/vk"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(), "Сначала зарегистрируйтесь: /start");
            return;
        }
        if (!vkLinkService.isEnabled()) {
            MessageSend.text(ctx.sender(), ctx.chatId(), "VK-уведомления пока не настроены.");
            return;
        }
        String code = vkLinkService.generateCode(ctx.user().getId());
        MessageSend.text(ctx.sender(), ctx.chatId(),
                "Чтобы получать уведомления в VK:\n\n" +
                "1. Откройте наше VK-сообщество\n" +
                "2. Напишите в сообщения: " + code + "\n\n" +
                "Код действует 15 минут.");
    }
}
