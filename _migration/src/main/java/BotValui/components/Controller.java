package BotValui.components;

import BotValui.Service.Parser;
import BotValui.Service.ParserFactory;
import BotValui.Service.ThreadPoolManager;
import BotValui.TelegramBotConnect.TelegramBot;
import BotValui.config.ControllerInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
public class Controller implements Runnable {
    @Getter
    private final String id;
    @Getter
    private final Page page;
    private final TelegramBot telegramBot;
    private ScheduledFuture<?> scheduledTask;
    private final ThreadPoolManager threadPoolManager = ThreadPoolManager.getInstance();
    @Getter
    private final Map<Long, String> rulesFilter = new ConcurrentHashMap<>();
    private final Map<Long, Pattern> compiledRules = new ConcurrentHashMap<>();
    @Getter
    private final Set<Long> chatIdList = new ConcurrentSkipListSet<>();

    private final AtomicBoolean running = new AtomicBoolean(false);


    public Controller(ControllerInfo controllerInfo, Long chatId,
                      TelegramBot telegramBot)
            throws MalformedURLException {
        Parser parser = createParser(controllerInfo.getLink());
        this.page = parser.getForControllerPage(controllerInfo.getTitle());
        this.telegramBot = telegramBot;
        this.addChatId(chatId, controllerInfo.getRulesFilter());
        this.chatIdList.add(chatId);
        this.id = controllerInfo.getLink();
    }

    private Parser createParser(String link) throws MalformedURLException {
        return ParserFactory.createParser(link);
    }

    public void addChatId(Long chatId, String rulesFilter) {
        this.rulesFilter.put(chatId, rulesFilter);
        compileFilterRule(chatId, rulesFilter);
        chatIdList.add(chatId);
    }

    public boolean addChatId(Long chatId) {
        return chatIdList.add(chatId);
    }

    public void removeChatId(Long chatId) {
        rulesFilter.remove(chatId);
        compiledRules.remove(chatId);
        chatIdList.remove(chatId);
    }

    public void stopTask() {
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(true);
        }
    }

    public String getTaskStatus() {
        if (scheduledTask == null) return TaskStatus.NOT_STARTED.name();
        if (scheduledTask.isCancelled()) return TaskStatus.CANCELLED.name();
        if (scheduledTask.isDone()) return TaskStatus.DONE.name();
        return TaskStatus.RUNNING.name();
    }

    public void start() {
        if (scheduledTask == null || scheduledTask.isDone() || scheduledTask.isCancelled()) {
            scheduledTask = threadPoolManager.getExecutor()
                    .scheduleWithFixedDelay(this::safeRun, 0, 30, TimeUnit.SECONDS);
        }
    }

    // новая обёртка: не даём NИЧЕМУ вылететь наружу
    private void safeRun() {
        try {
            // защита от наложений (если пред. запуск ещё идёт — пропускаем тик)
            if (!running.compareAndSet(false, true)) {
                log.warn("Skip tick: previous execution still running for {}", page.getLink());
                return;
            }
            // вызываем исходную логику
            run();
        } catch (Throwable t) { // ловим вообще всё, включая Error
            log.error("Uncaught throwable in scheduled task (suppressed to keep schedule alive)", t);
            // НИЧЕГО не пробрасываем!
        } finally {
            running.set(false);
        }
    }


    @Override
    public void run() {
        try {
            log.debug("Checking for new events: {}", page.getLink());
            Set<Event> newEvents = page.addNewEvents();

            if (!newEvents.isEmpty()) {
                processNewEvents(newEvents);
            }
        } catch (MalformedURLException e) {
            handleError("MalformedURLException in task", e);
        } catch (Exception e) {
            handleError("Exception in task", e);
        }
    }

    private void processNewEvents(Set<Event> newEvents) {
        for (Event event : newEvents) {
            for (long chatId : this.getChatIdList()) {
                if (matchesFilterRule(chatId, event)) {
                    sendNotificationToChat(chatId, event);
                }
            }
        }
    }

    private void sendNotificationToChat(long chatId, Event event) {
        String message = String.format("%s\n%s :\n%s",
                page.getTitle(),
                event.getLink(),
                event.getTitle());

        if (page.getIdChamp().isEmpty()) {
            Menu menu = new Menu("Добавлен новый турнир:\n" + message,
                    InterfaceBot.createButton("Добавить", "/add " + event.getLink()), 1);
            telegramBot.buildAndSendMessage(chatId, menu.getTitle(), menu.drawMenu());
        } else {
            telegramBot.buildAndSendMessage(chatId, "Добавлено новое событие:\n" + message);
        }
    }

    private boolean matchesFilterRule(Long chatId, Event event) {
        String rule = rulesFilter.get(chatId);
        if (rule == null || rule.isEmpty()) {
            return true;
        }

        Pattern pattern = compiledRules.get(chatId);
        if (pattern == null) {
            return false;
        }

        return pattern.matcher(event.getTitle()).matches();
    }

    public void updateFilterRule(Long chatId, String newRule) {
        if (newRule != null && !newRule.isEmpty()) {
            try {
                compileFilterRule(chatId, newRule);
            } catch (PatternSyntaxException e) {
                telegramBot.buildAndSendMessage(chatId,
                        "Ошибка в новом правиле фильтрации: " + e.getMessage());
                return;
            }
        }
        rulesFilter.put(chatId, newRule);
    }

    private void compileFilterRule(Long chatId, String rule) {
        try {
            Pattern pattern = Pattern.compile(rule, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
            compiledRules.put(chatId, pattern);
        } catch (PatternSyntaxException e) {
            log.error("Invalid regex pattern for chat {}: {}", chatId, rule, e);
            telegramBot.buildAndSendMessage(chatId,
                    "Ошибка в правиле фильтрации: " + e.getMessage() +
                            "\nПравило: " + rule +
                            "\nВсе события будут пропускаться до исправления правила.");
        }
    }

    public String getFilterRule(Long chatId) {
        return rulesFilter.get(chatId);
    }

    private void handleError(String errorPrefix, Exception e) {
        log.error("{} ID:{}, Name: {}", errorPrefix, page.getIdChamp(), page.getTitle(), e);
        telegramBot.buildAndSendMessage(telegramBot.getChatAdmin(),
                errorPrefix + ". ID:" + page.getIdChamp() + ", Name: " + page.getTitle());
    }

    public enum TaskStatus {
        NOT_STARTED, RUNNING, DONE, CANCELLED
    }
}
