package BotValui.TelegramBotConnect;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Клиентский rate limiter для Telegram Bot API.
 * <p>
 * Telegram декларирует:
 * <ul>
 *     <li>30 сообщений в секунду глобально на одного бота,</li>
 *     <li>1 сообщение в секунду в один и тот же личный чат,</li>
 *     <li>20 сообщений в минуту в групповой чат.</li>
 * </ul>
 * Мы работаем с небольшим запасом, чтобы не упираться в лимит и не ловить 429:
 * {@link #DEFAULT_GLOBAL_PER_SECOND} глобально и {@link #DEFAULT_PER_CHAT_INTERVAL_MS}
 * как минимальный промежуток между сообщениями в один чат.
 * <p>
 * Реализация:
 * <ul>
 *     <li>глобальный лимит — {@link Semaphore} с периодическим пополнением
 *         раз в секунду;</li>
 *     <li>per-chat лимит — {@code Map<chatId, lastSentAt>} и {@code Thread.sleep}
 *         до истечения окна.</li>
 * </ul>
 * Класс потокобезопасен. Контракт: {@link #acquire(long)} блокирует вызывающий поток
 * ровно настолько, насколько нужно, чтобы следующая отправка не нарушала лимиты.
 */
@Slf4j
public final class TelegramRateLimiter {

    /** Запас под глобальный лимит 30/сек. */
    public static final int DEFAULT_GLOBAL_PER_SECOND = 25;
    /** Запас под per-chat лимит 1 msg/sec (1100 ms ≈ 0.91 msg/sec). */
    public static final long DEFAULT_PER_CHAT_INTERVAL_MS = 1100L;

    private final int globalPerSecond;
    private final long perChatMinIntervalMs;
    private final Semaphore globalPermits;
    private final ScheduledExecutorService refill;
    private final ConcurrentHashMap<Long, Long> lastSentPerChat = new ConcurrentHashMap<>();
    /**
     * Per-chat мьютекс: гарантирует, что проверка "когда последний раз слали"
     * и сам sleep сериализованы по одному chatId. Без этого возможна гонка,
     * когда два потока одновременно решают, что пора слать.
     */
    private final ConcurrentHashMap<Long, Object> chatLocks = new ConcurrentHashMap<>();

    public TelegramRateLimiter() {
        this(DEFAULT_GLOBAL_PER_SECOND, DEFAULT_PER_CHAT_INTERVAL_MS);
    }

    public TelegramRateLimiter(int globalPerSecond, long perChatMinIntervalMs) {
        if (globalPerSecond <= 0) throw new IllegalArgumentException("globalPerSecond must be > 0");
        if (perChatMinIntervalMs < 0) throw new IllegalArgumentException("perChatMinIntervalMs must be >= 0");
        this.globalPerSecond = globalPerSecond;
        this.perChatMinIntervalMs = perChatMinIntervalMs;
        this.globalPermits = new Semaphore(globalPerSecond, true);
        this.refill = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tg-rate-refill");
            t.setDaemon(true);
            return t;
        });
        this.refill.scheduleAtFixedRate(this::refillPermits, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Блокирующе «забирает» право на одну отправку в указанный чат.
     * <p>
     * Сначала выдерживает per-chat окно (чтобы не было &gt; 1 msg/sec в один чат),
     * затем ждёт permit глобального бакета.
     *
     * @throws InterruptedException если поток прервали во время ожидания
     */
    public void acquire(long chatId) throws InterruptedException {
        if (perChatMinIntervalMs > 0) {
            // Сериализуем per-chat решение "сколько ждать" с самим ожиданием.
            Object lock = chatLocks.computeIfAbsent(chatId, k -> new Object());
            synchronized (lock) {
                Long last = lastSentPerChat.get(chatId);
                long now = System.currentTimeMillis();
                if (last != null) {
                    long waitMs = perChatMinIntervalMs - (now - last);
                    if (waitMs > 0) {
                        Thread.sleep(waitMs);
                        now = System.currentTimeMillis();
                    }
                }
                lastSentPerChat.put(chatId, now);
            }
        }
        // Глобальный лимит применяем после per-chat ожидания.
        globalPermits.acquire();
    }

    /**
     * Применяется для вызовов без chatId (например, {@code SetMyCommands}).
     * Действует только глобальный лимит.
     */
    public void acquireGlobal() throws InterruptedException {
        globalPermits.acquire();
    }

    /**
     * Аккуратно останавливает шедулер пополнения. Вызывать при shutdown приложения.
     */
    public void shutdown() {
        refill.shutdownNow();
    }

    private void refillPermits() {
        try {
            int missing = globalPerSecond - globalPermits.availablePermits();
            if (missing > 0) {
                globalPermits.release(missing);
            }
        } catch (Throwable t) {
            // Поток пополнения не должен умирать ни при каких обстоятельствах.
            log.error("TelegramRateLimiter refill error (suppressed)", t);
        }
    }
}
