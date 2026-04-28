package com.valui.notify.retry;

import com.valui.notify.exception.RetryableNotificationException;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies exceptions thrown during notification dispatch into
 * {@link RetryableNotificationException} with the appropriate retry flag.
 *
 * Retryable:
 *   - Network / IO errors
 *   - Telegram 429 (rate-limited) — extracts retry-after hint
 *   - Telegram 5xx (server error)
 *   - Any other unknown error (fail-open: retry rather than drop)
 *
 * Non-retryable:
 *   - Telegram 400 (bad request, e.g. malformed message)
 *   - Telegram 403 (bot blocked by user or chat not found)
 */
@Component
public class NotificationRetryPolicy {

    private static final Pattern RETRY_AFTER_PATTERN =
            Pattern.compile("retry after (\\d+)", Pattern.CASE_INSENSITIVE);

    public RetryableNotificationException classify(Exception ex) {
        if (ex instanceof TelegramApiRequestException tare) {
            return classifyTelegram(tare);
        }
        if (ex instanceof IOException || isCausedByIo(ex)) {
            return retryable(ex, 0L);
        }
        // Unknown errors — retryable by default to avoid silent message loss
        return retryable(ex, 0L);
    }

    // ── private ───────────────────────────────────────────────────────────────

    private RetryableNotificationException classifyTelegram(TelegramApiRequestException ex) {
        Integer code = ex.getErrorCode();
        if (code == null) return retryable(ex, 0L);

        return switch (code) {
            case 429 -> retryable(ex, parseRetryAfterMs(ex.getApiResponse()));
            case 400 -> nonRetryable(ex);
            case 403 -> nonRetryable(ex);
            default  -> code >= 500 ? retryable(ex, 0L) : nonRetryable(ex);
        };
    }

    private static boolean isCausedByIo(Exception ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof IOException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private static long parseRetryAfterMs(String apiResponse) {
        if (apiResponse == null) return 2_000L;
        Matcher m = RETRY_AFTER_PATTERN.matcher(apiResponse);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)) * 1_000L; }
            catch (NumberFormatException e) { /* fall through */ }
        }
        return 2_000L;
    }

    private static RetryableNotificationException retryable(Exception ex, long retryAfterMs) {
        return new RetryableNotificationException(ex.getMessage(), ex, true, retryAfterMs);
    }

    private static RetryableNotificationException nonRetryable(Exception ex) {
        return new RetryableNotificationException(ex.getMessage(), ex, false, 0L);
    }
}
