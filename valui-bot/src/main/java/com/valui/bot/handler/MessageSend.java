package com.valui.bot.handler;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
public final class MessageSend {

    private MessageSend() {}

    public static void text(AbsSender sender, long chatId, String text) {
        try {
            sender.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build());
        } catch (TelegramApiException e) {
            log.error("Send failed chatId={}: {}", chatId, e.getMessage());
        }
    }

    public static void textWithKeyboard(AbsSender sender, long chatId, String text, ReplyKeyboard keyboard) {
        try {
            sender.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(keyboard)
                .build());
        } catch (TelegramApiException e) {
            log.error("Send failed chatId={}: {}", chatId, e.getMessage());
        }
    }
}
