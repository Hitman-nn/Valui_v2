package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.vk.VkLinkService;
import com.valui.user.api.ControllerPortService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SettingsCommandHandler implements CommandHandler {

    private final ControllerPortService controllerPort;
    private final VkLinkService vkLinkService;
    private final BotMessageSource msg;

    @Override
    public String command() { return "/settings"; }

    @Override
    public int order() { return 50; }

    @Override
    public void handle(BotUpdateContext ctx) {
        if (ctx.user() == null) {
            MessageSend.text(ctx.sender(), ctx.chatId(),
                msg.getMessage("bot.user_not_registered", ctx.fromId()));
            return;
        }
        boolean vkLinked = controllerPort.hasVkLinked(ctx.user().getId(), ctx.chatId());
        MessageSend.textMarkdownWithKeyboard(ctx.sender(), ctx.chatId(),
            buildText(vkLinked, ctx.fromId()),
            buildKeyboard(vkLinked, ctx.fromId()));
    }

    public String buildText(boolean vkLinked, long fromId) {
        String statusKey = !vkLinkService.isEnabled()
            ? "settings.vk_not_enabled"
            : vkLinked ? "settings.vk_linked" : "settings.vk_not_linked";
        return msg.getMessage("settings.title", fromId) + "\n\n" +
            msg.getMessage(statusKey, fromId);
    }

    public InlineKeyboardMarkup buildKeyboard(boolean vkLinked, long fromId) {
        if (!vkLinkService.isEnabled()) {
            return InlineKeyboardMarkup.builder().keyboard(List.of()).build();
        }
        String btnText = msg.getMessage(vkLinked ? "settings.btn_vk_unlink" : "settings.btn_vk_link", fromId);
        String callback = vkLinked ? CallbackData.VK_UNLINK : CallbackData.VK_LINK;
        return InlineKeyboardMarkup.builder()
            .keyboardRow(List.of(InlineKeyboardButton.builder()
                .text(btnText)
                .callbackData(callback)
                .build()))
            .build();
    }
}
