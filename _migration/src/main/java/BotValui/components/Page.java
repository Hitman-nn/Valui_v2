package BotValui.components;

import BotValui.Service.Parser;
import BotValui.Service.ParserFactory;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Slf4j
public class Page {
    private final String idChamp;
    @Setter
    private String title;
    private final String link;
    private Date lastUpdate;
    private Date lastTryUpdate;
    private Date lastNewAdd;
    @Setter
    private Set<Event> events = new ConcurrentSkipListSet<>();

    /** Момент последней TTL-очистки этой страницы; {@code null} — ещё ни разу не чистилось. */
    private volatile LocalDateTime lastCleanupAt;
    /** Количество событий, удалённых в последнюю TTL-очистку; {@code -1} — ещё ни разу не чистилось. */
    private volatile int lastCleanupRemoved = -1;

    public Page(String link, String idChamp, String title) {
        this.link = link;
        this.idChamp = idChamp;
        this.title = title;
        this.lastUpdate = new Date();
        this.lastTryUpdate = new Date();
        this.lastNewAdd = new Date();
    }

    public Set<Event> addNewEvents() throws MalformedURLException {
        lastTryUpdate = new Date(); // Обновляем время последней попытки

        Parser parser = ParserFactory.createParser(link);
        Page page = parser.getForControllerPage();

        Set<Event> returnEvents = new ConcurrentSkipListSet<>();
        if (page == null || page.getEvents().isEmpty()) return returnEvents;

        returnEvents = page.getEvents();
        Set<Event> oldEvents = new HashSet<>(this.getEvents());

        lastUpdate = new Date(); // Обновляем время последнего обновления
        Iterator<Event> itr = returnEvents.iterator();

        while (itr.hasNext()) {
            Event event = itr.next();
            if (!isEventChildPage(event)) {
                itr.remove(); // Удаляем событие, если оно не относится к текущей странице
                continue;
            }

            for (Event oldEvent : oldEvents) {
                if (event.equals(oldEvent)) {
                    itr.remove(); // Удаляем событие, если оно уже есть в старых событиях
                    break;
                }
            }
        }

        // Обновляем список событий, если добавились новые
        if (!returnEvents.isEmpty()) {
            events.addAll(returnEvents);
            if (!parser.isTitleValid(title)) {
                setTitle(page.getTitle());
            }
            lastNewAdd = new Date(); // Обновляем время добавления новых событий
            logNewEvents(returnEvents, oldEvents);
        }

        return returnEvents;
    }

    /**
     * Считается ли эта страница страницей матчей конкретного турнира.
     * <p>
     * Страница со свободным {@code idChamp} — это "страница-турнир",
     * она следит за появлением новых турниров. События на ней — сами турниры,
     * их TTL-чистить нельзя (иначе потеряем историю отслеживаемых чемпионатов).
     * <p>
     * Страница с непустым {@code idChamp} — "страница-матч": она смотрит внутрь
     * одного чемпионата и копит там матчи. Именно их и чистит TTL-сервис.
     *
     * @return {@code true}, если страница содержит матчи конкретного турнира
     *         и подлежит TTL-очистке
     */
    public boolean isMatchPage() {
        return idChamp != null && !idChamp.isEmpty();
    }

    /**
     * Удаляет из кэша события, у которых {@code addDate} старше, чем {@code now() - ttl}.
     * <p>
     * Работает только для страниц-матчей ({@link #isMatchPage()} == {@code true}).
     * Для страниц-турниров метод — no-op: возвращает 0 и не обновляет
     * {@link #lastCleanupAt}/{@link #lastCleanupRemoved}, чтобы отличать
     * "чистили, но нечего было чистить" от "не применимо".
     * <p>
     * События с {@code null} в {@code addDate} не трогаем, чтобы не потерять записи
     * с некорректным timestamp.
     * <p>
     * Потокобезопасно: {@code events} — {@link ConcurrentSkipListSet}, обновление
     * {@link #lastCleanupAt}/{@link #lastCleanupRemoved} происходит атомарно в конце.
     *
     * @param ttl время жизни события; {@code null}, ноль или отрицательное значение
     *            считается "ничего не удалять"
     * @return количество фактически удалённых событий
     * @deprecated используйте {@link #removeExpiredEventsNotInLine(Duration)} —
     *             она дополнительно проверяет, что матч уже не висит в линии,
     *             и поэтому не вызывает повторных уведомлений после очистки.
     *             Оставлен для обратной совместимости и unit-тестов чистой in-memory логики.
     */
    @Deprecated
    public int removeExpiredEvents(Duration ttl) {
        if (!isMatchPage()) {
            // Страница-турнир: защищённо пропускаем, даже если кто-то вызвал метод мимо сервиса.
            return 0;
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return 0;
        }
        LocalDateTime threshold = LocalDateTime.now().minus(ttl);

        AtomicInteger removed = new AtomicInteger();
        events.removeIf(event -> {
            if (event == null || event.getAddDate() == null) return false;
            if (event.getAddDate().isBefore(threshold)) {
                removed.incrementAndGet();
                return true;
            }
            return false;
        });

        int removedCount = removed.get();
        this.lastCleanupAt = LocalDateTime.now();
        this.lastCleanupRemoved = removedCount;
        return removedCount;
    }

    /**
     * Сколько раз подряд свежий парс должен вернуть пустой результат, чтобы мы
     * поверили "турнир закончился", если при этом {@link #lastUpdate} ещё свежий.
     */
    private static final int EMPTY_LINE_CONFIRM_ATTEMPTS = 3;

    /**
     * Пауза между повторными попытками подтвердить пустую линию.
     * Достаточно коротко, чтобы не тормозить cleanup-цикл, и достаточно долго,
     * чтобы мелкий транзиентный сбой успел развалиться.
     */
    private static final long EMPTY_LINE_RETRY_DELAY_MS = 5_000L;

    /**
     * Безопасная версия {@link #removeExpiredEvents(Duration)} для реальной TTL-очистки:
     * удаляет только те просроченные события, которых УЖЕ НЕТ в текущей линии букмекера.
     * <p>
     * Зачем это нужно: если матч попал в кэш больше {@code ttl} назад, но букмекер
     * всё ещё показывает его в линии (например, турнир длится долго), то простое
     * удаление из кэша приведёт к тому, что на следующем цикле
     * {@link #addNewEvents()} матч будет снова воспринят как новый и уйдёт
     * повторное уведомление в чат. Чтобы этого избежать, перед удалением
     * мы тянем свежий снимок страницы через {@link ParserFactory} и оставляем
     * в кэше все события, которые ещё возвращает парсер.
     * <p>
     * Логика обработки "пустой линии" (сигнал "турнир закончился" vs. транзиентный сбой):
     * <ol>
     *     <li>Делаем 1-й парс. Любое исключение → consservative skip: ничего не трогаем.</li>
     *     <li>Если 1-й парс вернул непустой результат — используем его, нормальный путь.</li>
     *     <li>Если 1-й парс вернул пусто — проверяем {@link #lastUpdate}. Оно апдейтится
     *         в {@link #addNewEvents()} только при непустом результате, поэтому если
     *         оно старше {@code ttl}, регулярный polling уже давно видит линию пустой —
     *         доверяем "турнир закончился" без повторных попыток.</li>
     *     <li>Иначе делаем ещё {@link #EMPTY_LINE_CONFIRM_ATTEMPTS} - 1 попыток с паузой
     *         в {@link #EMPTY_LINE_RETRY_DELAY_MS} мс:
     *         <ul>
     *             <li>Если какая-то retry кинула исключение → skip (inconclusive).</li>
     *             <li>Если какая-то retry вернула непустой набор — используем его.</li>
     *             <li>Если все попытки подряд пустые — считаем "турнир закончился".</li>
     *         </ul>
     *     </li>
     *     <li>Финальный шаг: удаляем события, которые (a) просрочены И
     *         (b) отсутствуют в {@code currentLine}. Для пустой линии это значит —
     *         удалить все просроченные.</li>
     * </ol>
     * Для страниц-турниров ({@link #isMatchPage()} == {@code false}) метод — no-op, 0.
     * <p>
     * Сравнение со "свежей линией" идёт через {@link Set#contains(Object)} —
     * а {@code events} у нас {@link ConcurrentSkipListSet}, поэтому проверка
     * выполняется через {@link Event#compareTo(Event)} (сравнение по {@code eventId}).
     * Этого достаточно: если бук всё ещё показывает матч — он идёт с тем же
     * {@code eventId}.
     * <p>
     * Потокобезопасно: {@code events} — concurrent set, счётчики — {@link AtomicInteger},
     * диагностические поля {@code volatile}.
     *
     * @param ttl время жизни события; {@code null}, ноль или отрицательное значение
     *            считается "ничего не удалять"
     * @return количество фактически удалённых событий
     */
    public int removeExpiredEventsNotInLine(Duration ttl) {
        if (!isMatchPage()) {
            return 0;
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return 0;
        }

        // 1. Первая попытка. Исключение здесь трактуем как сигнал о сбое — skip.
        Set<Event> currentLine = fetchLineOrNullOnFailure();
        if (currentLine == null) {
            log.warn("TTL cleanup skipped for page '{}' (link={}): parser failed on first attempt. " +
                    "Keeping cache intact until next cycle.", title, link);
            return 0;
        }

        // 2. Если пусто — нужно убедиться, что это "турнир закончился", а не транзиентный глюк.
        if (currentLine.isEmpty()) {
            if (isLastUpdateOlderThan(ttl)) {
                log.info("TTL cleanup: page '{}' treated as ended tournament " +
                        "(first parse empty AND lastUpdate older than TTL={})", title, ttl);
                // currentLine остаётся пустым → ниже удалятся все просроченные.
            } else {
                int emptyInARow = 1;
                boolean gotContentOnRetry = false;
                for (int attempt = 2; attempt <= EMPTY_LINE_CONFIRM_ATTEMPTS; attempt++) {
                    try {
                        Thread.sleep(EMPTY_LINE_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("TTL cleanup interrupted for page '{}', skipping this cycle", title);
                        return 0;
                    }
                    Set<Event> retry = fetchLineOrNullOnFailure();
                    if (retry == null) {
                        log.warn("TTL cleanup skipped for page '{}' (link={}): parser failed on retry {}/{}. " +
                                "Inconclusive, keeping cache intact.",
                                title, link, attempt, EMPTY_LINE_CONFIRM_ATTEMPTS);
                        return 0;
                    }
                    if (!retry.isEmpty()) {
                        log.debug("TTL cleanup: page '{}' retry {}/{} returned non-empty line, " +
                                "using it for in-line check",
                                title, attempt, EMPTY_LINE_CONFIRM_ATTEMPTS);
                        currentLine = retry;
                        gotContentOnRetry = true;
                        break;
                    }
                    emptyInARow++;
                }
                if (!gotContentOnRetry && emptyInARow == EMPTY_LINE_CONFIRM_ATTEMPTS) {
                    log.info("TTL cleanup: page '{}' treated as ended tournament " +
                            "({} empty parses in a row)", title, EMPTY_LINE_CONFIRM_ATTEMPTS);
                    // currentLine остаётся пустым.
                }
            }
        }

        // 3. Основная проверка: удаляем просроченное И отсутствующее в линии.
        LocalDateTime threshold = LocalDateTime.now().minus(ttl);
        AtomicInteger removed = new AtomicInteger();
        AtomicInteger keptBecauseInLine = new AtomicInteger();
        final Set<Event> line = currentLine; // effectively-final для лямбды

        events.removeIf(event -> {
            if (event == null || event.getAddDate() == null) return false;
            if (!event.getAddDate().isBefore(threshold)) {
                return false;
            }
            if (line.contains(event)) {
                keptBecauseInLine.incrementAndGet();
                return false;
            }
            removed.incrementAndGet();
            return true;
        });

        int removedCount = removed.get();
        int keptCount = keptBecauseInLine.get();
        if (removedCount > 0 || keptCount > 0) {
            log.debug("TTL cleanup for page '{}': removed {}, kept {} (still in line)",
                    title, removedCount, keptCount);
        }
        this.lastCleanupAt = LocalDateTime.now();
        this.lastCleanupRemoved = removedCount;
        return removedCount;
    }

    /**
     * Тянет свежую линию через {@link ParserFactory}.
     *
     * @return набор событий (может быть пустым, если парсер вернул пусто),
     *         либо {@code null}, если парсер свалился с исключением. Именно {@code null}
     *         используется как сигнал "consservative skip" — пустой Set сигналит
     *         "парсер нормально ответил, но в линии ничего нет".
     */
    private Set<Event> fetchLineOrNullOnFailure() {
        try {
            Parser parser = ParserFactory.createParser(link);
            Page fresh = parser.getForControllerPage();
            if (fresh == null || fresh.getEvents() == null) {
                return Collections.emptySet();
            }
            return fresh.getEvents();
        } catch (Throwable t) {
            // Ловим всё: MalformedURLException, UnsupportedOperationException,
            // сетевые ошибки парсера, NPE в стороннем SDK и т.п.
            log.warn("TTL cleanup: parser failed for page '{}' (link={}): {}",
                    title, link, t.toString());
            return null;
        }
    }

    /**
     * {@code true}, если {@link #lastUpdate} старше {@code now() - ttl}.
     * <p>
     * {@code lastUpdate} апдейтится в {@link #addNewEvents()} только при непустом
     * ответе парсера, поэтому "старый lastUpdate" — прямой сигнал, что обычное
     * polling-опрашивание тоже давно не видит матчей на этой странице.
     * <p>
     * {@code null} lastUpdate (страница свежая и ни разу не апдейтилась)
     * считаем "старым" — всё равно чистить там нечего, а логика останется простой.
     */
    private boolean isLastUpdateOlderThan(Duration ttl) {
        if (lastUpdate == null) return true;
        long thresholdMs = System.currentTimeMillis() - ttl.toMillis();
        return lastUpdate.getTime() < thresholdMs;
    }

    // Проверка, относится ли событие к текущей странице
    private boolean isEventChildPage(Event event) {
        if (event == null || event.getLink() == null || link == null) {
            return false;
        }

        String basePath = normalizePath(link);
        String eventPath = normalizePath(event.getLink());

        return eventPath.startsWith(basePath);
    }

    /**
     * Нормализуем URL:
     *  - отбрасываем query (?...)
     *  - отбрасываем протокол и домен (оставляем только path)
     *  - убираем лишний '/' в конце
     */
    private static String normalizePath(String url) {
        String s = url;

        // убираем query-параметры
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }

        // убираем протокол и домен
        int schemeEnd = s.indexOf("://");
        if (schemeEnd >= 0) {
            int pathStart = s.indexOf('/', schemeEnd + 3);
            if (pathStart >= 0) {
                s = s.substring(pathStart);
            } else {
                // URL без path → считаем корнем
                s = "/";
            }
        }

        // убираем завершающие слэши, кроме корня
        while (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }

        return s;
    }

    // Логирование новых событий
    private void logNewEvents(Set<Event> newEvents, Set<Event> oldEvents) {
        log.debug("------------- New Events -------------");
        log.debug("New events count: {}", newEvents.size());
        newEvents.forEach(event -> log.debug("{} - {}", event.getEventId(), event.getTitle()));
        log.debug("Old events count: {}", oldEvents.size());
        oldEvents.forEach(event -> log.debug("{} - {}", event.getEventId(), event.getTitle()));
        log.debug("-------------------------------------");
    }

    @Override
    public String toString() {
        return "Page: " +
                "idChamp='" + idChamp + "', " +
                "title='" + title + "', " +
                "link='" + link + "', " +
                "lastUpdate=" + lastUpdate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Page page = (Page) o;
        return Objects.equals(idChamp, page.idChamp) && Objects.equals(events, page.events);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idChamp, events);
    }
}
