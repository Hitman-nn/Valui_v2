package com.valui.bot.handler;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
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

    public static void textMarkdown(AbsSender sender, long chatId, String text) {
        try {
            sender.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown")
                .build());
        } catch (TelegramApiException e) {
            log.error("Send failed chatId={}: {}", chatId, e.getMessage());
        }
    }

    public static void textMarkdownWithKeyboard(AbsSender sender, long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            sender.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build());
        } catch (TelegramApiException e) {
            log.error("Send failed chatId={}: {}", chatId, e.getMessage());
        }
    }

    public static void textMarkdownWithKeyboard(AbsSender sender, long chatId, String text,
                                                 org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup keyboard) {
        try {
            sender.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build());
        } catch (TelegramApiException e) {
            log.error("Send failed chatId={}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Edits a message in-place.
     * Use for transient states (e.g. "⏳ Loading…") where in-place update is intentional.
     * Falls back to send-new + delete-old if the message can no longer be edited.
     */
    public static void editTextWithKeyboard(AbsSender sender, long chatId, int messageId,
                                             String text, InlineKeyboardMarkup keyboard) {
        try {
            sender.execute(EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text)
                .replyMarkup(keyboard)
                .build());
        } catch (TelegramApiException e) {
            String reason = e.getMessage() != null ? e.getMessage() : "";
            if (reason.contains("message is not modified")) return;
            log.debug("Edit not possible chatId={} msgId={} ({}), falling back to replace", chatId, messageId, reason);
            replaceWithKeyboard(sender, chatId, messageId, text, keyboard);
        }
    }

    /**
     * Sends a new message at the bottom of the chat, then deletes the old one.
     * Use for wizard navigation steps so the active menu is always visible to the user,
     * even if chat notifications pushed the previous wizard message off screen.
     */
    public static void replaceWithKeyboard(AbsSender sender, long chatId, int oldMessageId,
                                            String text, InlineKeyboardMarkup keyboard) {
        textWithInlineKeyboard(sender, chatId, text, keyboard);
        tryDelete(sender, chatId, oldMessageId);
    }

    private static void textWithInlineKeyboard(AbsSender sender, long chatId,
                                                String text, InlineKeyboardMarkup keyboard) {
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

    private static void tryDelete(AbsSender sender, long chatId, int messageId) {
        try {
            sender.execute(DeleteMessage.builder()
                .chatId(String.valueOf(chatId))
                .messageId(messageId)
                .build());
        } catch (TelegramApiException e) {
            log.debug("Delete failed chatId={} msgId={}: {}", chatId, messageId, e.getMessage());
        }
    }

    public static void answerCallback(AbsSender sender, String callbackQueryId) {
        try {
            sender.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .build());
        } catch (TelegramApiException e) {
            log.error("AnswerCallback failed: {}", e.getMessage());
        }
    }

    public static void answerCallbackWithAlert(AbsSender sender, String callbackQueryId, String text) {
        try {
            sender.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text(text)
                .showAlert(false)
                .build());
        } catch (TelegramApiException e) {
            log.error("AnswerCallback failed: {}", e.getMessage());
        }
    }
}
