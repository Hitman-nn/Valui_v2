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

    /**
     * Every send failure here used to log at ERROR uniformly regardless of cause. Telegram's
     * "Forbidden: bot was blocked by the user" / "Bad Request: chat not found" / "user is
     * deactivated" happen constantly in normal operation (users block bots, delete accounts) and
     * aren't actionable — logging them at ERROR (which typically pages/alerts) drowns out actual
     * transport/auth incidents. Only genuinely unexpected failures stay at ERROR with a stack trace.
     */
    private static void logSendFailure(long chatId, TelegramApiException e) {
        if (isExpectedFailure(e.getMessage())) {
            log.warn("Send failed chatId={}: {}", chatId, e.getMessage());
        } else {
            log.error("Send failed chatId={}: {}", chatId, e.getMessage(), e);
        }
    }

    private static boolean isExpectedFailure(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("bot was blocked") || lower.contains("chat not found")
                || lower.contains("user is deactivated") || lower.contains("bot can't initiate conversation")
                || lower.contains("chat_write_forbidden");
    }

    public static void text(AbsSender sender, long chatId, String text) {
        try {
            sender.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build());
        } catch (TelegramApiException e) {
            logSendFailure(chatId, e);
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
            logSendFailure(chatId, e);
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
            logSendFailure(chatId, e);
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
            logSendFailure(chatId, e);
        }
    }

    /**
     * Sends a new Markdown message and returns its message ID (0 on failure).
     * Use when the caller needs to track the new wizard message position.
     */
    public static int sendMarkdownGetId(AbsSender sender, long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            org.telegram.telegrambots.meta.api.objects.Message msg = sender.execute(
                SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("Markdown")
                    .replyMarkup(keyboard)
                    .build());
            return msg != null ? msg.getMessageId() : 0;
        } catch (TelegramApiException e) {
            logSendFailure(chatId, e);
            return 0;
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
            logSendFailure(chatId, e);
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

    /**
     * Same as {@link #replaceWithKeyboard} but returns the new message ID (0 on failure).
     * Use when the caller needs to update a message tracker with the new active message.
     */
    public static int replaceWithKeyboardGetId(AbsSender sender, long chatId, int oldMessageId,
                                               String text, InlineKeyboardMarkup keyboard) {
        int newId = sendInlineKeyboardGetId(sender, chatId, text, keyboard);
        tryDelete(sender, chatId, oldMessageId);
        return newId;
    }

    /** Sends a plain text message and returns its ID (0 on failure). */
    public static int sendGetId(AbsSender sender, long chatId, String text) {
        try {
            org.telegram.telegrambots.meta.api.objects.Message msg = sender.execute(
                SendMessage.builder().chatId(chatId).text(text).build());
            return msg != null ? msg.getMessageId() : 0;
        } catch (TelegramApiException e) {
            logSendFailure(chatId, e);
            return 0;
        }
    }

    /** Sends a plain text message with an inline keyboard and returns its ID (0 on failure). */
    public static int sendGetId(AbsSender sender, long chatId, String text, InlineKeyboardMarkup keyboard) {
        return sendInlineKeyboardGetId(sender, chatId, text, keyboard);
    }

    private static int sendInlineKeyboardGetId(AbsSender sender, long chatId,
                                               String text, InlineKeyboardMarkup keyboard) {
        try {
            org.telegram.telegrambots.meta.api.objects.Message msg = sender.execute(
                SendMessage.builder().chatId(chatId).text(text).replyMarkup(keyboard).build());
            return msg != null ? msg.getMessageId() : 0;
        } catch (TelegramApiException e) {
            logSendFailure(chatId, e);
            return 0;
        }
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
            logSendFailure(chatId, e);
        }
    }

    public static void deleteMessage(AbsSender sender, long chatId, int messageId) {
        tryDelete(sender, chatId, messageId);
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

    /**
     * Tries to edit the existing message in-place with Markdown.
     * Falls back to send-new + delete-old if the message can no longer be edited.
     */
    public static void editMarkdownWithKeyboard(AbsSender sender, long chatId, int messageId,
                                                String text, InlineKeyboardMarkup keyboard) {
        try {
            sender.execute(EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build());
        } catch (TelegramApiException e) {
            String reason = e.getMessage() != null ? e.getMessage() : "";
            if (reason.contains("message is not modified")) return;
            log.debug("Edit not possible chatId={} msgId={} ({}), falling back to replace", chatId, messageId, reason);
            try {
                sender.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("Markdown")
                    .replyMarkup(keyboard)
                    .build());
            } catch (TelegramApiException ex) {
                logSendFailure(chatId, ex);
                return;
            }
            tryDelete(sender, chatId, messageId);
        }
    }

    /**
     * Edits the existing message if messageId > 0, otherwise sends a new message.
     * Returns the effective message ID (existing or newly sent).
     */
    public static int editOrSendMarkdown(AbsSender sender, long chatId, int messageId,
                                         String text, InlineKeyboardMarkup keyboard) {
        if (messageId > 0) {
            editMarkdownWithKeyboard(sender, chatId, messageId, text, keyboard);
            return messageId;
        }
        return sendMarkdownGetId(sender, chatId, text, keyboard);
    }

    public static void answerCallback(AbsSender sender, String callbackQueryId) {
        try {
            sender.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .build());
        } catch (TelegramApiException e) {
            // "query is too old" / "query ID is invalid" happen routinely (user tapped a button
            // on a stale message, or the 15s answer window expired) — not an incident.
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("query")) {
                log.debug("AnswerCallback failed: {}", msg);
            } else {
                log.warn("AnswerCallback failed: {}", msg, e);
            }
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
            // "query is too old" / "query ID is invalid" happen routinely (user tapped a button
            // on a stale message, or the 15s answer window expired) — not an incident.
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("query")) {
                log.debug("AnswerCallback failed: {}", msg);
            } else {
                log.warn("AnswerCallback failed: {}", msg, e);
            }
        }
    }

    public static void answerCallbackWithModal(AbsSender sender, String callbackQueryId, String text) {
        try {
            sender.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text(text)
                .showAlert(true)
                .build());
        } catch (TelegramApiException e) {
            // "query is too old" / "query ID is invalid" happen routinely (user tapped a button
            // on a stale message, or the 15s answer window expired) — not an incident.
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("query")) {
                log.debug("AnswerCallback failed: {}", msg);
            } else {
                log.warn("AnswerCallback failed: {}", msg, e);
            }
        }
    }
}
