package com.valui.bot.handler.command;

import com.valui.bot.handler.BotUpdateContext;
import com.valui.bot.handler.CommandHandler;
import com.valui.bot.handler.MessageSend;
import com.valui.bot.i18n.BotMessageSource;
import com.valui.bot.keyboard.CallbackData;
import com.valui.bot.keyboard.InlineKeyboardBuilder;
import com.valui.bot.vk.VkLinkService;
import com.valui.user.api.ControllerPortService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

@Component
@RequiredArgsConstructor
public class SettingsCommandHandler implements CommandHandler {

    private final ControllerPortService controllerPort;
    private final VkLinkService         vkLinkService;
    private final BotMessageSource      msg;

    static final int[] TOK_PRESETS = {50, 100, 200, 500};

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
        boolean vkLinked  = controllerPort.hasVkLinked(ctx.user().getId(), ctx.chatId());
        Integer threshold = ctx.user().getTokenLowThreshold();
        String  langCode  = ctx.user().getLanguageCode();

        int id = MessageSend.sendMarkdownGetId(ctx.sender(), ctx.chatId(),
            buildText(vkLinked, threshold, langCode, ctx.fromId()),
            buildKeyboard(vkLinked, threshold, ctx.fromId()));
        if (id > 0) ctx.tracker().track(ctx.chatId(), id);
    }

    public String buildText(boolean vkLinked, Integer threshold, String langCode, long fromId) {
        String langLabel = "ru".equals(langCode) ? "🇷🇺 RU" : "🇬🇧 EN";
        String tokLabel  = threshold != null
            ? threshold + " 🪙"
            : msg.getMessage("settings.tok_off", fromId);
        String vkKey = !vkLinkService.isEnabled() ? "settings.vk_not_enabled"
            : vkLinked ? "settings.vk_linked" : "settings.vk_not_linked";

        return msg.getMessage("settings.title", fromId) + "\n\n"
            + "🌍 " + msg.getMessage("settings.lang_section", fromId) + ": " + langLabel + "\n"
            + "🔔 " + msg.getMessage("settings.tok_section", fromId) + ": " + tokLabel + "\n\n"
            + msg.getMessage(vkKey, fromId);
    }

    public InlineKeyboardMarkup buildKeyboard(boolean vkLinked, Integer threshold, long fromId) {
        InlineKeyboardBuilder kb = InlineKeyboardBuilder.create();

        // Language row
        kb.button("🇷🇺 Русский", CallbackData.settLang("ru"))
          .button("🇬🇧 English", CallbackData.settLang("en")).row();

        // Token threshold presets
        for (int t : TOK_PRESETS) {
            boolean active = threshold != null && threshold == t;
            kb.button(active ? "✅ " + t : String.valueOf(t), CallbackData.settTok(t));
        }
        kb.button(threshold == null ? "✅ Откл." : "Откл.", CallbackData.SETT_TOK_OFF).row();

        // VK row
        if (vkLinkService.isEnabled()) {
            String btnText = msg.getMessage(
                vkLinked ? "settings.btn_vk_unlink" : "settings.btn_vk_link", fromId);
            kb.button(btnText, vkLinked ? CallbackData.VK_UNLINK : CallbackData.VK_LINK).row();
        }

        return kb.build();
    }
}
