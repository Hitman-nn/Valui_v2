package com.valui.bot.handler.command;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
final class MessageSend {

    private MessageSend() {}

    // Same reasoning as com.valui.bot.handler.MessageSend (a separate, duplicate class of the
    // same name — not consolidated here since that's a larger refactor beyond a logging pass):
    // "bot was blocked"/"chat not found" are routine, not incidents worth ERROR.
    private static void logSendFailure(long chatId, TelegramApiException e) {
        String msg = e.getMessage();
        boolean expected = msg != null && (msg.toLowerCase().contains("bot was blocked")
                || msg.toLowerCase().contains("chat not found")
                || msg.toLowerCase().contains("user is deactivated"));
        if (expected) {
            log.warn("Send failed chatId={}: {}", chatId, msg);
        } else {
            log.error("Send failed chatId={}: {}", chatId, msg, e);
        }
    }

    static void text(AbsSender sender, long chatId, String text) {
        try {
            sender.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build());
        } catch (TelegramApiException e) {
            logSendFailure(chatId, e);
        }
    }

    static void textWithKeyboard(AbsSender sender, long chatId, String text, ReplyKeyboard keyboard) {
        try {
            sender.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(keyboard)
                .build());
        } catch (TelegramApiException e) {
            logSendFailure(chatId, e);
        }
    }
}
