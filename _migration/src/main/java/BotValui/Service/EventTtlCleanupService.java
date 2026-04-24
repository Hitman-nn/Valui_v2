package BotValui.Service;

import BotValui.Commands.Commands;
import BotValui.components.Controller;
import BotValui.config.ChatBotConfig;
import BotValui.config.EventTtlProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Периодическая TTL-очистка событий.
 * <p>
 * Удаляет из in-memory кэша каждого {@link BotValui.components.Page} события,
 * у которых {@code addDate} старше {@link EventTtlProperties#getDuration()}.
 * Если что-то было удалено — немедленно перезаписывает {@code chatConfig.json},
 * чтобы старые события не вернулись после рестарта.
 *
 * <h3>Расписание</h3>
 * Поддерживается два режима, выбираемых через {@link EventTtlProperties}:
 * <ul>
 *     <li><b>Fixed-rate</b>: запуск каждые {@code cleanup-period} начиная через
 *         {@code initial-delay} после старта.</li>
 *     <li><b>Weekly</b>: запуск в конкретный {@code cleanup-day} + {@code cleanup-time}
 *         (по таймзоне {@code zone}). Если задан {@code cleanup-day} — используется
 *         этот режим, fixed-rate настройки игнорируются.</li>
 * </ul>
 * В weekly-режиме задача сама планирует следующий запуск после выполнения —
 * это надёжнее, чем {@code scheduleAtFixedRate(delay, 7 days)}, потому что
 * корректно переживает DST-переходы и не накапливает дрейф.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventTtlCleanupService {

    private final EventTtlProperties props;

    private final ChatBotConfig chatBotConfig = ChatBotConfig.getInstance();
    private final ThreadPoolManager threadPoolManager = ThreadPoolManager.getInstance();

    @PostConstruct
    public void init() {
        if (props.isWeeklyMode()) {
            scheduleWeekly();
        } else {
            scheduleFixedRate();
        }
    }

    // ---------------------------------------------------------------------
    // Schedulers
    // ---------------------------------------------------------------------

    private void scheduleFixedRate() {
        Duration period = props.getCleanupPeriod();
        Duration delay = props.getInitialDelay();

        if (period == null || period.isNegative() || period.isZero()) {
            log.warn("EventTtlCleanupService disabled: event.ttl.cleanup-period must be > 0, got {}", period);
            return;
        }

        long delayMin = Math.max(0, delay == null ? 0 : delay.toMinutes());

        threadPoolManager.getExecutor().scheduleAtFixedRate(
                this::cleanupExpiredEvents,
                delayMin,
                period.toMinutes(),
                TimeUnit.MINUTES
        );
        log.info("EventTtlCleanupService scheduled fixed-rate: ttl={} days, period={} days, initialDelay={} min",
                props.getDuration().toDays(),
                period.toDays(),
                delayMin);
    }

    private void scheduleWeekly() {
        DayOfWeek day = props.getCleanupDay();
        LocalTime time = props.getCleanupTime() != null ? props.getCleanupTime() : LocalTime.of(3, 0);
        ZoneId zone = props.getZone() != null ? props.getZone() : ZoneId.systemDefault();

        long firstDelayMs = computeDelayMsToNext(day, time, zone, ZonedDateTime.now(zone));
        log.info("EventTtlCleanupService scheduled weekly: day={}, time={}, zone={}, " +
                        "first run in ~{} min (ttl={} days)",
                day, time, zone, firstDelayMs / 60_000, props.getDuration().toDays());

        scheduleWeeklyOnce(firstDelayMs);
    }

    /**
     * Одноразовый {@code schedule()} на рассчитанную задержку. После выполнения
     * задача планирует сама себя на следующее вхождение. Исключения внутри
     * {@link #cleanupExpiredEvents()} уже ловятся (там стоит {@code catch (Throwable)}),
     * но мы дополнительно оборачиваем в try/finally, чтобы даже при неожиданной
     * ошибке расписание не умерло.
     */
    private void scheduleWeeklyOnce(long delayMs) {
        ScheduledExecutorService executor = threadPoolManager.getExecutor();
        executor.schedule(() -> {
            try {
                cleanupExpiredEvents();
            } catch (Throwable t) {
                log.error("EventTtlCleanup: weekly run failed unexpectedly (suppressed)", t);
            } finally {
                try {
                    DayOfWeek day = props.getCleanupDay();
                    if (day == null) {
                        // Кто-то перевёл конфиг в fixed-rate на лету — weekly больше не рестартуем.
                        log.info("EventTtlCleanup: cleanup-day no longer set, stopping weekly loop");
                        return;
                    }
                    LocalTime time = props.getCleanupTime() != null
                            ? props.getCleanupTime() : LocalTime.of(3, 0);
                    ZoneId zone = props.getZone() != null
                            ? props.getZone() : ZoneId.systemDefault();
                    long next = computeDelayMsToNext(day, time, zone, ZonedDateTime.now(zone));
                    log.debug("EventTtlCleanup: next weekly run scheduled in ~{} min", next / 60_000);
                    scheduleWeeklyOnce(next);
                } catch (Throwable t) {
                    log.error("EventTtlCleanup: failed to schedule next weekly run", t);
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Вычисляет миллисекунды до ближайшего "следующего {@code day} в {@code time}"
     * в зоне {@code zone}, относительно момента {@code now}.
     * <p>
     * Если сегодня — нужный день, но время уже прошло — переносим на неделю вперёд.
     * <p>
     * Выделено в static package-private, чтобы было легко покрыть юнит-тестами
     * без старта Spring-контекста (передаём фиксированный {@code now}).
     */
    static long computeDelayMsToNext(DayOfWeek day, LocalTime time, ZoneId zone, ZonedDateTime now) {
        ZonedDateTime target = now.with(TemporalAdjusters.nextOrSame(day))
                .withHour(time.getHour())
                .withMinute(time.getMinute())
                .withSecond(0)
                .withNano(0);
        if (!target.isAfter(now)) {
            target = target.plusWeeks(1);
        }
        long ms = Duration.between(now, target).toMillis();
        return Math.max(0, ms);
    }

    // ---------------------------------------------------------------------
    // Cleanup itself
    // ---------------------------------------------------------------------

    /**
     * Основной цикл очистки. Проходит только по страницам-матчам
     * ({@link BotValui.components.Page#isMatchPage()}), вызывает
     * {@code Page.removeExpiredEventsNotInLine(TTL)} и, если что-то удалилось,
     * сразу же сохраняет состояние в {@code chatConfig.json}.
     * <p>
     * Страницы-турниры (idChamp пустой) пропускаются: их события — это сами
     * турниры, которые нельзя терять по TTL.
     * <p>
     * Метод ловит все исключения внутри, чтобы не сломать периодический
     * {@link java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate}
     * (при throw-е он отменяет дальнейшие запуски).
     */
    void cleanupExpiredEvents() {
        long startedAt = System.currentTimeMillis();
        Duration ttl = props.getDuration();
        try {
            Map<Long, List<Controller>> controllerListByChatId = Commands.getControllerListByChatId();

            int totalRemoved = 0;
            int affectedPages = 0;
            int scannedMatchPages = 0;
            int skippedTournamentPages = 0;

            for (List<Controller> controllers : controllerListByChatId.values()) {
                if (controllers == null) continue;
                for (Controller controller : controllers) {
                    if (controller == null || controller.getPage() == null) continue;

                    if (!controller.getPage().isMatchPage()) {
                        // Страница-турнир: TTL к ней не применяется.
                        skippedTournamentPages++;
                        continue;
                    }

                    scannedMatchPages++;
                    // Используем "безопасный" вариант: удаляем событие только если оно
                    // одновременно просрочено и уже не возвращается парсером. Иначе
                    // после чистки мы получим повторное уведомление при следующем
                    // addNewEvents(), потому что удалённый, но всё ещё актуальный матч
                    // снова будет воспринят как новый.
                    int removed = controller.getPage().removeExpiredEventsNotInLine(ttl);
                    if (removed > 0) {
                        affectedPages++;
                        totalRemoved += removed;
                        log.debug("TTL cleanup: removed {} events from page '{}'",
                                removed, controller.getPage().getTitle());
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - startedAt;
            if (totalRemoved > 0) {
                log.info("EventTtlCleanup: removed {} expired events from {}/{} match pages " +
                                "(skipped {} tournament pages, ttl>{} days) in {} ms. " +
                                "Persisting chatConfig.json...",
                        totalRemoved, affectedPages, scannedMatchPages,
                        skippedTournamentPages, ttl.toDays(), elapsed);
                try {
                    chatBotConfig.updateControllers(controllerListByChatId);
                    log.info("EventTtlCleanup: chatConfig.json updated after cleanup");
                } catch (Exception e) {
                    log.error("EventTtlCleanup: failed to persist chatConfig.json after cleanup", e);
                }
            } else {
                log.info("EventTtlCleanup: no expired events found in {} match pages " +
                                "(skipped {} tournament pages, ttl>{} days) in {} ms",
                        scannedMatchPages, skippedTournamentPages, ttl.toDays(), elapsed);
            }
        } catch (Throwable t) {
            log.error("EventTtlCleanup failed unexpectedly", t);
        }
    }
}
