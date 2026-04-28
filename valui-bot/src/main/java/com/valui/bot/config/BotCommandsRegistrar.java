package com.valui.bot.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import com.valui.bot.ValuiTelegramBot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

/**
 * Registers the bot's visible command list with Telegram on startup.
 * These commands appear when the user types "/" in the chat.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(ValuiTelegramBot.class)   // only in long-polling mode
public class BotCommandsRegistrar {

    private final ValuiTelegramBot bot;

    @EventListener(ApplicationReadyEvent.class)
    public void registerCommands() {
        List<BotCommand> commands = List.of(
            new BotCommand("/menu",       "🏠 Главное меню"),
            new BotCommand("/add",        "➕ Добавить контроллер"),
            new BotCommand("/list",       "📋 Мои контроллеры"),
            new BotCommand("/info",       "ℹ️ Мой тариф / статус группы"),
            new BotCommand("/listfilter", "🔍 Фильтры"),
            new BotCommand("/stop",       "🛑 Остановить всё"),
            new BotCommand("/deleteall",  "🗑 Удалить все контроллеры"),
            new BotCommand("/language",   "🌍 Сменить язык"),
            new BotCommand("/help",       "❓ Справка"),
            new BotCommand("/start",      "🔄 Перезапустить бота")
        );

        try {
            bot.execute(SetMyCommands.builder().commands(commands).build());
            log.info("✅ Команды бота зарегистрированы в Telegram ({} команд)", commands.size());
        } catch (TelegramApiException e) {
            log.warn("⚠️  Не удалось зарегистрировать команды бота: {}", e.getMessage());
        }
    }
}
