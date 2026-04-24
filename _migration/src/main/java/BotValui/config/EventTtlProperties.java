package BotValui.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Конфигурация TTL-очистки событий.
 * <p>
 * Читается из {@code config.properties} (он подключается через
 * {@link BotConfig#BotConfig() @PropertySource("config.properties")}).
 * Все поля имеют разумные значения по умолчанию — при отсутствии ключей в
 * конфиге приложение стартует.
 *
 * <h3>Расписание: два взаимоисключающих режима</h3>
 * <ol>
 *     <li><b>Fixed-rate</b> (по умолчанию) — запуск каждые
 *         {@link #getCleanupPeriod()} начиная через {@link #getInitialDelay()}
 *         после старта приложения.</li>
 *     <li><b>Weekly</b> — запуск в конкретный день недели в указанное время
 *         локальной зоны: {@link #getCleanupDay()} + {@link #getCleanupTime()}
 *         + {@link #getZone()}. Если {@code cleanupDay} задан — используется
 *         weekly-режим, а {@code cleanupPeriod} и {@code initialDelay}
 *         игнорируются.</li>
 * </ol>
 *
 * @see BotValui.Service.EventTtlCleanupService
 */
@Data
@Component
@ConfigurationProperties(prefix = "event.ttl")
public class EventTtlProperties {

    /** Сколько хранить событие в кэше (addDate + duration = момент истечения). */
    private Duration duration = Duration.ofDays(30);

    // ----- Fixed-rate режим -----

    /** Периодичность фоновой задачи очистки (используется, если {@link #cleanupDay} не задан). */
    private Duration cleanupPeriod = Duration.ofDays(7);

    /** Задержка перед первым запуском после старта приложения (только для fixed-rate режима). */
    private Duration initialDelay = Duration.ofMinutes(5);

    // ----- Weekly режим -----

    /**
     * День недели для weekly-режима. Значения — имена {@link DayOfWeek}:
     * {@code MONDAY, TUESDAY, ..., SUNDAY}.
     * <p>
     * Если задан — расписание переключается в weekly-режим и игнорирует
     * {@link #cleanupPeriod} / {@link #initialDelay}. Если {@code null} —
     * работает fixed-rate.
     */
    private DayOfWeek cleanupDay;

    /**
     * Время запуска в weekly-режиме (локальное по {@link #zone}).
     * Формат в конфиге — {@code HH:mm} или {@code HH:mm:ss} (ISO-8601 local time).
     * По умолчанию — 03:00 ночи (время минимальной нагрузки).
     */
    private LocalTime cleanupTime = LocalTime.of(3, 0);

    /**
     * Часовой пояс для интерпретации {@link #cleanupTime}.
     * По умолчанию — системная зона JVM.
     */
    private ZoneId zone = ZoneId.systemDefault();

    /** Удобный флаг: true — работаем в weekly-режиме, false — в fixed-rate. */
    public boolean isWeeklyMode() {
        return cleanupDay != null;
    }
}
