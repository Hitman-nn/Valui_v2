package BotValui.TelegramBotConnect;

import BotValui.Commands.Commands;
import BotValui.config.BotConfig;
import BotValui.config.TelegramRateProperties;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static BotValui.Commands.Commands.COMMANDS_NONE_ARG;

@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {
    /** Максимум ретраев на 429 для одного сообщения. */
    private static final int MAX_RETRIES_ON_429 = 3;
    /** Fallback, если Telegram прислал 429 без параметра retry_after (не должно случаться). */
    private static final int FALLBACK_RETRY_AFTER_SEC = 30;
    /** Парсер retry_after из текста ошибки на случай старых версий SDK. */
    private static final Pattern RETRY_AFTER_MSG_PATTERN =
            Pattern.compile("retry after (\\d+)", Pattern.CASE_INSENSITIVE);

    private final BotConfig config;
    private final Commands commands;
    private final TelegramRateLimiter rateLimiter;

    public TelegramBot(BotConfig config,
                       DefaultBotOptions telegramBotOptions,
                       TelegramRateProperties rateProps) {
        super(telegramBotOptions, config.getToken());
        this.config = config;
        this.commands = new Commands(this);

        // Нормализуем интервал: null или отрицательное значение → 0 (per-chat лимит отключён).
        long perChatMs = (rateProps.getPerChatMinInterval() == null
                || rateProps.getPerChatMinInterval().isNegative())
                ? 0L
                : rateProps.getPerChatMinInterval().toMillis();
        this.rateLimiter = new TelegramRateLimiter(rateProps.getGlobalPerSecond(), perChatMs);
        log.info("Telegram rate limiter configured: global={}/sec, per-chat min interval={} ms",
                rateProps.getGlobalPerSecond(), perChatMs);
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public void onUpdateReceived(@NotNull Update update) {
        commands.executeCommand(update);
    }

    @Override
    public void clearWebhook() throws TelegramApiRequestException {
        log.info("Webhook already absent, skip clearWebhook()");
    }

    public Long getChatAdmin() {
        return config.getChatAdmin();
    }

    public void setBaseCommand() {
        try {
            executeThrottledGlobal(new SetMyCommands(COMMANDS_NONE_ARG, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            log.error("Error setting base commands: {}", e.getMessage(), e);
        }
    }

    public List<Message> buildAndSendMessage(Long chatId, String textMessage) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        List<Message> responseMessage = new ArrayList<>();
        for (String text : splitString(textMessage, 4096)) {
            message.setText(text);
            try {
                responseMessage.add(executeThrottled(message, chatId));
            } catch (TelegramApiException e) {
                log.error("Send failed to chat {}: {}", chatId, e.getMessage());
            }
        }
        return responseMessage;
    }

    public static List<String> splitString(String text, int n) {
        List<String> results = new ArrayList<>();
        int length = text.length();
        for (int i = 0; i < length; i += n) {
            results.add(text.substring(i, Math.min(length, i + n)));
        }
        return results;
    }

    public List<Message> buildAndSendMessage(Long chatId, String textMessage,
                                             List<InlineKeyboardMarkup> inlineKeyboardMarkupList) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(textMessage);
        List<Message> responseMessage = new ArrayList<>();
        for (InlineKeyboardMarkup inlineKeyboardMarkup : inlineKeyboardMarkupList) {
            message.setReplyMarkup(inlineKeyboardMarkup);
            try {
                responseMessage.add(executeThrottled(message, chatId));
            } catch (TelegramApiException e) {
                log.error("Send failed to chat {}: {}", chatId, e.getMessage());
            }
        }
        return responseMessage;
    }

    public void deleteMessage(Long chatId, Integer messageId) {
        DeleteMessage message = new DeleteMessage(chatId.toString(), messageId);
        try {
            executeThrottled(message, chatId);
        } catch (TelegramApiException e) {
            log.error("Delete message failed in chat {}: {}", chatId, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdownRateLimiter() {
        rateLimiter.shutdown();
    }

    // ---------------------------------------------------------------------
    // Throttled execute: per-chat 1/sec + global 25/sec + retry on 429
    // ---------------------------------------------------------------------

    /**
     * Выполняет любой Bot API-метод с соблюдением rate limit для конкретного чата
     * и автоматическим ретраем на 429.
     */
    private <T extends Serializable, M extends BotApiMethod<T>> T executeThrottled(M method, long chatId)
            throws TelegramApiException {
        for (int attempt = 0; attempt <= MAX_RETRIES_ON_429; attempt++) {
            try {
                rateLimiter.acquire(chatId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TelegramApiException("Interrupted while waiting for rate limiter", ie);
            }
            try {
                return execute(method);
            } catch (TelegramApiRequestException e) {
                if (e.getErrorCode() == 429 && attempt < MAX_RETRIES_ON_429) {
                    sleepForRetryAfter(e, chatId, attempt);
                    continue;
                }
                throw e;
            }
        }
        // Теоретически недостижимо: либо вернёмся из try, либо пробросим.
        throw new TelegramApiException("Exceeded max retries on 429 for chat " + chatId);
    }

    /** Вариант для глобальных вызовов без chatId (например, SetMyCommands). */
    private <T extends Serializable, M extends BotApiMethod<T>> T executeThrottledGlobal(M method)
            throws TelegramApiException {
        for (int attempt = 0; attempt <= MAX_RETRIES_ON_429; attempt++) {
            try {
                rateLimiter.acquireGlobal();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TelegramApiException("Interrupted while waiting for global rate limiter", ie);
            }
            try {
                return execute(method);
            } catch (TelegramApiRequestException e) {
                if (e.getErrorCode() == 429 && attempt < MAX_RETRIES_ON_429) {
                    sleepForRetryAfter(e, null, attempt);
                    continue;
                }
                throw e;
            }
        }
        throw new TelegramApiException("Exceeded max retries on 429 for global call");
    }

    /**
     * Засыпает на {@code retry_after + 1} секунд. Значение берёт из {@code ResponseParameters},
     * а если там пусто — парсит из текста ошибки (на случай несоответствия версии SDK).
     */
    private void sleepForRetryAfter(TelegramApiRequestException e, Long chatId, int attempt) {
        int retryAfter = extractRetryAfter(e);
        log.warn("Telegram 429 for chat={} attempt={} retry_after={}s. Backing off.",
                chatId, attempt + 1, retryAfter);
        try {
            Thread.sleep((retryAfter + 1) * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static int extractRetryAfter(TelegramApiRequestException e) {
        try {
            if (e.getParameters() != null && e.getParameters().getRetryAfter() != null) {
                return e.getParameters().getRetryAfter();
            }
        } catch (Throwable ignored) {
            // Некоторые версии SDK могут не иметь этого геттера — падаем в парсинг строки.
        }
        String msg = e.getMessage();
        if (msg != null) {
            Matcher m = RETRY_AFTER_MSG_PATTERN.matcher(msg);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return FALLBACK_RETRY_AFTER_SEC;
    }
}
