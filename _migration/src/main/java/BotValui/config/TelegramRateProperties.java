package BotValui.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Параметры клиентского rate limiter-а для Telegram Bot API.
 * <p>
 * Читается из {@code config.properties} (подключается через
 * {@link BotConfig#BotConfig() @PropertySource("config.properties")}).
 * Все поля имеют разумные значения по умолчанию — при отсутствии ключей
 * в конфиге приложение стартует без ошибок.
 * <p>
 * Telegram декларирует:
 * <ul>
 *     <li>30 сообщений/сек глобально на одного бота,</li>
 *     <li>1 сообщение/сек в один и тот же личный чат,</li>
 *     <li>20 сообщений/мин в групповой чат.</li>
 * </ul>
 * Мы работаем с небольшим запасом, чтобы не упираться в лимит и не ловить 429.
 *
 * @see BotValui.TelegramBotConnect.TelegramRateLimiter
 */
@Data
@Component
@ConfigurationProperties(prefix = "telegram.rate")
public class TelegramRateProperties {

    /**
     * Сколько сообщений/сек разрешаем глобально.
     * Telegram лимит = 30, по умолчанию берём 25 с запасом.
     */
    private int globalPerSecond = 25;

    /**
     * Минимальный интервал между сообщениями в один и тот же чат.
     * Telegram лимит = 1 msg/sec, по умолчанию 1100 ms (≈ 0.91 msg/sec).
     */
    private Duration perChatMinInterval = Duration.ofMillis(1100);
}
