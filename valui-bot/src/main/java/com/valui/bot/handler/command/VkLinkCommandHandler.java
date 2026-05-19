package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.vk.VkLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VkLinkCommandHandler implements CommandHandler {

    private final VkLinkService    vkLinkService;
    private final BotMessageSource msg;

    @Override
    public String command() { return "/vk"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                msg.getMessage("bot.user_not_registered", ctx.fromId()));
            return;
        }
        if (!vkLinkService.isEnabled()) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                msg.getMessage("vk.not_enabled", ctx.fromId()));
            return;
        }
        String code  = vkLinkService.generateCode(ctx.user().getId(), ctx.chatId());
        String where = msg.getMessage(ctx.isGroupChat()
                ? "vk.link_where_group" : "vk.link_where_private", ctx.fromId());
        MessageSend.text(ctx.sender(), ctx.chatId(),
                msg.getMessage("vk.link_instructions", ctx.fromId(), where, code));
    }
}
