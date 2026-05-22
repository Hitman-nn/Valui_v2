package com.valui.bot.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonCommands;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

/**
 * Registers the bot's visible command list with Telegram on startup.
 * These commands appear when the user types "/" in the chat.
 * Works in both long-polling and webhook modes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotCommandsRegistrar {

    private final AbsSender bot;

    @EventListener(ApplicationReadyEvent.class)
    public void registerCommands() {
        List<BotCommand> commands = List.of(
            new BotCommand("/menu",  "🏠 Главное меню"),
            new BotCommand("/help",  "❓ Справка"),
            new BotCommand("/start", "🔄 Перезапустить бота")
        );

        try {
            bot.execute(SetMyCommands.builder().commands(commands).build());
            log.info("✅ Команды бота зарегистрированы в Telegram ({} команд)", commands.size());
        } catch (TelegramApiException e) {
            log.warn("⚠️  Не удалось зарегистрировать команды бота: {}", e.getMessage());
        }

        try {
            bot.execute(SetChatMenuButton.builder().menuButton(MenuButtonCommands.builder().build()).build());
            log.info("✅ Menu button установлен (MenuButtonCommands)");
        } catch (TelegramApiException e) {
            log.warn("⚠️  Не удалось установить menu button: {}", e.getMessage());
        }
    }
}
