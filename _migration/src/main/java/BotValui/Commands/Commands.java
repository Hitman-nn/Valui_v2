package BotValui.Commands;

import BotValui.Service.Parser;
import BotValui.Service.xstavka.Parser1XStavkaAPI;
import BotValui.Service.betcity.ParserBetcityAPI;
import BotValui.Service.fonbet.ParserFonBetAPI;
import BotValui.Service.olimp.ParserOlimpAPI;
import BotValui.Service.betboom.ParserBetboomAPI;
import BotValui.TelegramBotConnect.TelegramBot;
import BotValui.components.Controller;
import BotValui.components.FilterEvent;
import BotValui.components.InterfaceBot;
import BotValui.components.Menu;
import BotValui.components.Page;
import BotValui.components.UserState;
import BotValui.components.UserTempData;
import BotValui.config.ChatBotConfig;
import BotValui.config.ControllerInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static BotValui.components.InterfaceBot.BK_CONST;
import static BotValui.components.InterfaceBot.NAVIGATION_CONST;
import static BotValui.components.InterfaceBot.SPORT_ZIP_CONST;
import static BotValui.components.InterfaceBot.buildMenu;
import static BotValui.components.InterfaceBot.parserButtonParam;

@Slf4j
public class Commands {
    private static final String DELIMITER_COMMAND_BOTNAME = "@";
    private static final String PREFIX_FOR_COMMAND = "/";
    private static final String HELP_TEXT = """
             Бот пытается отслеживать добавление новых матчей. Доступны olimp.bet, fon.bet, 1xstavka.ru.
             Доступны следующие команды:
             \s
             /start - запускает бота в чате с сохраненными параметрами
             /stop - выключает бота в чате
             /add ссылка - добавление контролируемого турнира
             /delete ссылка - удаление контролируемого турнира
             /deleteall - удаление всех контролируемых турниров
             /addfilter слово - добавление слова в фильтр
             /deletefilter слово - удаления слова из фильтра
             /deleteallfilter - удаление всех слов из фильтра
             /list - список контролируемых турниров
             /listfilter - список слов в фильтре
             /help - описание
            \s""";

    // Потокобезопасные коллекции для хранения данных
    private final TelegramBot telegramBot;
    private final Map<Long, List<Integer>> chatIdMessageId = new ConcurrentHashMap<>();
    private final Set<Long> chatIdList = ConcurrentHashMap.newKeySet();
    private final Map<String, Controller> controllerById = new ConcurrentHashMap<>();
    private static final Map<Long, UserState> userStates = new ConcurrentHashMap<>();
    private static final Map<Long, UserTempData> userTempData = new ConcurrentHashMap<>();
    @Getter
    private static final Map<Long, List<Controller>> controllerListByChatId = new ConcurrentHashMap<>();

    private static final String[] COMMANDS_ONE_ARG = {
            "/add", "/delete", "/addfilter", "/deletefilter", "/viewcontroller",
            "/deleteidchamp", "/addidchamp", "/startchat", "/deletesportlink", "/inputrulesport"
    };

    public static final List<BotCommand> COMMANDS_NONE_ARG = List.of(
            new BotCommand("/start", "start bot"),
            new BotCommand("/generalmenu", "general menu"),
            new BotCommand("/stop", "stop bot"),
            new BotCommand("/deleteall", "delete all controller"),
            new BotCommand("/deleteallfilter", "delete all filter"),
            new BotCommand("/list", "list controller"),
            new BotCommand("/listfilter", "list filter"),
            new BotCommand("/help", "info bot"),
            new BotCommand("/debugttl", "show TTL cleanup debug info")
    );

    public Commands(TelegramBot telegramBot) {
        this.telegramBot = telegramBot;
    }

    /**
     * Основной метод обработки входящих команд
     */
    public void executeCommand(@NotNull Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessageUpdate(update);
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update);
            }
        } catch (Exception e) {
            log.error("Error processing update: {}", update, e);
        }
    }

    private void handleMessageUpdate(Update update) {
        long chatId = update.getMessage().getChatId();
        String userName = update.getMessage().getFrom().getFirstName();
        String messageText = update.getMessage().getText();

        if (userStates.getOrDefault(chatId, UserState.NONE) == UserState.WAITING_FOR_FILTER_RULE) {
            handleSportRuleInput(chatId, messageText);
            return;
        }

        if (isValidCommand(messageText)) {
            checkAndRunCommand(chatId, userName, cutCommandFromFullText(messageText));
        }
    }

    private void handleCallbackQuery(Update update) {
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        String callbackData = update.getCallbackQuery().getData();
        if (isValidCommand(callbackData)) {
            String userName = update.getCallbackQuery().getFrom().getFirstName();
            checkAndRunCommand(chatId, userName, cutCommandFromFullText(callbackData));
        } else {
            Menu menu = buildMenu(chatId, callbackData.toLowerCase());
            if (menu != null) {
                handleMenu(chatId, menu);
            }
        }
    }

    private boolean isValidCommand(String messageText) {
        return messageText != null &&
                messageText.startsWith(PREFIX_FOR_COMMAND) &&
                isCommandForMe(messageText);
    }

    private void handleMenu(long chatId, Menu menu) {
        deleteLastMessage(chatId);
        List<Message> messages = telegramBot.buildAndSendMessage(chatId, menu.getTitle(), menu.drawMenu());
        updateMessageIds(chatId, messages);
    }

    private void updateMessageIds(long chatId, List<Message> messages) {
        List<Integer> messageIds = messages.stream()
                .map(Message::getMessageId)
                .collect(Collectors.toList());
        chatIdMessageId.put(chatId, messageIds);
    }

    /**
     * Обработка ввода правила фильтрации для спорта
     */
    private void handleSportRuleInput(long chatId, String ruleText) {
        UserTempData tempData = userTempData.get(chatId);
        if (tempData == null) {
            log.warn("No temp data found for chatId: {}", chatId);
            return;
        }

        String link = generateLink(tempData.getBk(), tempData.getSportId());
        if (link.isEmpty()) return;

        if (!isValidRegex(ruleText)) {
            telegramBot.buildAndSendMessage(chatId,
                    "Ошибка в правиле фильтрации. Повторите ввод.");
            return;
        }

        ControllerInfo controllerInfo = new ControllerInfo(link, "", ruleText, new TreeSet<>(), true);
        addController(controllerInfo, chatId, false);
        ChatBotConfig.getInstance().updateControllers(controllerListByChatId);

        String bk = tempData.getBk();
        String cancelButton = NAVIGATION_CONST + "=" + InterfaceBot.MenuList.SELECTSPORT +
                "&" + InterfaceBot.CONTROLLER_ZIP_CONST + "=" + SPORT_ZIP_CONST +
                "&" + BK_CONST + "=" + bk;

        Menu menu = buildMenu(chatId, cancelButton);
        if (menu != null) {
            telegramBot.buildAndSendMessage(chatId, menu.getTitle(), menu.drawMenu());
        }

        userStates.put(chatId, UserState.NONE);
        userTempData.remove(chatId);
    }

    private String generateLink(String bookmaker, String sportId) {
        if (bookmaker == null || sportId == null) return "";

        return switch (bookmaker) {
            case Parser.BASE_URL_1XSTAVKA -> Parser1XStavkaAPI.getLink(sportId);
            case Parser.BASE_URL_OLIMP -> ParserOlimpAPI.getLink(sportId);
            case Parser.BASE_URL_FONBET -> ParserFonBetAPI.getLink(sportId);
            case Parser.BASE_URL_BETCITY -> ParserBetcityAPI.getLink(sportId);
            case Parser.BASE_URL_BETBOOM-> ParserBetboomAPI.getLink(sportId);
            default -> {
                log.warn("Unsupported bookmaker: {}", bookmaker);
                yield "";
            }
        };
    }

    private boolean isValidRegex(String regex) {
        try {
            Pattern.compile(regex);
            return true;
        } catch (Exception e) {
            log.warn("Invalid regex pattern: {}", regex, e);
            return false;
        }
    }

    /**
     * Удаление последнего сообщения в чате
     */
    private void deleteLastMessage(long chatId) {
        List<Integer> messageIds = chatIdMessageId.get(chatId);
        if (messageIds != null) {
            messageIds.forEach(id -> telegramBot.deleteMessage(chatId, id));
        }
    }

    /**
     * Проверка и выполнение команд
     */
    private void checkAndRunCommand(long chatId, String userName, String messageText) {
        if (!chatIdList.contains(chatId)) {
            if (messageText.equals("/start")) {
                startBot(chatId, userName);
            }
            return;
        }

        switch (messageText) {
            case "/startallchat" -> startBotAllChat(chatId, false);
            case "/startallchatmute" -> startBotAllChat(chatId, true);
            case "/cleanControllerListByChatId" -> cleanControllerListByChatId(controllerListByChatId);
            case "/stop" -> stopBot(chatId);
            case "/generalmenu" -> goToMenu(chatId, InterfaceBot.MenuList.GENERAL);
            case "/deleteall" -> handleDeleteAll(chatId);
            case "/list" -> handleListController(chatId);
            case "/deleteallfilter" -> handleDeleteAllFilter(chatId);
            case "/listfilter" -> handleListFilter(chatId);
            case "/help" -> handleHelp(chatId);
            case "/whoisalive" -> whoIsAlive(chatId);
            case "/whoisstop" -> whoIsStop(chatId);
            case "/debugttl" -> handleDebugTtl(chatId);
            default -> {
                if (!checkCommandWithArgs(chatId, messageText)) {
                    log.info("Unexpected message: {}", messageText);
                }
            }
        }
    }

    private void handleDeleteAll(long chatId) {
        deleteAllByChatId(chatId);
        ChatBotConfig.getInstance().updateControllers(controllerListByChatId);
        telegramBot.buildAndSendMessage(chatId, "Удаление выполнено...");
        goToMenu(chatId, InterfaceBot.MenuList.CONTROLLER);
    }

    private void handleListController(long chatId) {
        listController(chatId);
        goToMenu(chatId, InterfaceBot.MenuList.CONTROLLER);
    }

    private void handleDeleteAllFilter(long chatId) {
        FilterEvent.deleteAllFilter();
        ChatBotConfig.getInstance().updateFilterEvents(FilterEvent.getFilterList());
        telegramBot.buildAndSendMessage(chatId, "Удаление выполнено...");
        goToMenu(chatId, InterfaceBot.MenuList.FILTER);
    }

    private void handleListFilter(long chatId) {
        listFilter(chatId);
        goToMenu(chatId, InterfaceBot.MenuList.FILTER);
    }

    private void handleHelp(long chatId) {
        telegramBot.buildAndSendMessage(chatId, HELP_TEXT);
        goToMenu(chatId, InterfaceBot.MenuList.GENERAL);
    }

    /** Максимальная длина одного сообщения Telegram ~4096; оставляем запас. */
    private static final int TG_MESSAGE_SAFE_LIMIT = 3800;

    /**
     * Диагностика TTL-очистки — показывает состояние кэша всех контроллеров
     * по всем чатам: размер кэша событий, время последней очистки и число
     * удалённых в ней событий.
     * <p>
     * Ответ может разбиваться на несколько сообщений, если превышает лимит
     * Telegram (~4096 символов).
     */
    private void handleDebugTtl(long chatId) {
        Map<Long, List<Controller>> allByChat = controllerListByChatId;
        if (allByChat.isEmpty()) {
            telegramBot.buildAndSendMessage(chatId, "Нет активных контроллеров ни в одном чате.");
            return;
        }

        int totalControllers = allByChat.values().stream()
                .filter(Objects::nonNull)
                .mapToInt(List::size)
                .sum();
        int totalEvents = allByChat.values().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(c -> c != null && c.getPage() != null)
                .mapToInt(c -> c.getPage().getEvents().size())
                .sum();

        StringBuilder header = new StringBuilder();
        header.append("Отладка TTL-очистки (все чаты)\n")
                .append("Чатов: ").append(allByChat.size())
                .append(", контроллеров: ").append(totalControllers)
                .append(", событий в кэше: ").append(totalEvents).append("\n\n");

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        int globalIdx = 1;

        for (Map.Entry<Long, List<Controller>> entry : allByChat.entrySet()) {
            Long ownerChatId = entry.getKey();
            List<Controller> controllers = entry.getValue();
            if (controllers == null || controllers.isEmpty()) continue;

            String chatHeader = "--- chatId=" + ownerChatId
                    + " (" + controllers.size() + ") ---\n";
            if (current.length() + chatHeader.length() > TG_MESSAGE_SAFE_LIMIT) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(chatHeader);

            for (Controller controller : controllers) {
                if (controller == null || controller.getPage() == null) continue;
                Page page = controller.getPage();

                String kind = page.isMatchPage() ? "матчи" : "турниры";
                String lastAt;
                String lastRemoved;
                if (!page.isMatchPage()) {
                    // К страницам-турнирам TTL не применяется — так и показываем.
                    lastAt = "не применяется";
                    lastRemoved = "не применяется";
                } else {
                    lastAt = page.getLastCleanupAt() == null
                            ? "—"
                            : page.getLastCleanupAt().toString();
                    lastRemoved = page.getLastCleanupRemoved() < 0
                            ? "—"
                            : String.valueOf(page.getLastCleanupRemoved());
                }

                String block = globalIdx++ + ". " + page.getTitle() + " [" + kind + "]\n"
                        + "   " + page.getLink() + "\n"
                        + "   В кэше: " + page.getEvents().size() + "\n"
                        + "   Последняя очистка: " + lastAt + "\n"
                        + "   Удалено в ней: " + lastRemoved + "\n\n";

                if (current.length() + block.length() > TG_MESSAGE_SAFE_LIMIT) {
                    chunks.add(current.toString());
                    current = new StringBuilder();
                    // повторяем заголовок чата в новой части, чтобы не терялся контекст
                    current.append(chatHeader);
                }
                current.append(block);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        for (String chunk : chunks) {
            telegramBot.buildAndSendMessage(chatId, chunk);
        }
    }

    /**
     * Обработка команд с аргументами
     */
    private boolean checkCommandWithArgs(Long chatId, String messageText) {
        String[] commandParts = messageText.split(" +", 2);
        if (commandParts.length != 2 || !isSupportedCommand(commandParts[0])) {
            return false;
        }

        String command = commandParts[0];
        String argument = commandParts[1];

        switch (command) {
            case "/add" -> handleAddCommand(chatId, argument);
            case "/addidchamp" -> handleAddIdChampCommand(chatId, argument);
            case "/delete" -> handleDeleteCommand(chatId, argument);
            case "/deletesportlink" -> handleDeleteSportLinkCommand(chatId, argument);
            case "/deleteidchamp" -> handleDeleteIdChampCommand(chatId, argument);
            case "/addfilter" -> handleAddFilterCommand(chatId, argument);
            case "/deletefilter" -> handleDeleteFilterCommand(chatId, argument);
            case "/viewcontroller" -> viewController(argument, chatId);
            case "/inputrulesport" -> handleInputRuleSportCommand(chatId, argument);
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean isSupportedCommand(String command) {
        return Arrays.asList(COMMANDS_ONE_ARG).contains(command);
    }

    private void handleAddCommand(long chatId, String link) {
        if (link.isEmpty()) return;
        addController(link, chatId, false);
        ChatBotConfig.getInstance().updateControllers(controllerListByChatId);
    }

    private void handleAddIdChampCommand(long chatId, String argument) {
        String link = getLinkByChampId(argument);
        if (link.isEmpty()) return;
        addController(link, chatId, false);
        ChatBotConfig.getInstance().updateControllers(controllerListByChatId);
    }

    private void handleDeleteCommand(long chatId, String link) {
        deleteControllerForChatId(link, chatId);
        ChatBotConfig.getInstance().updateControllers(controllerListByChatId);
    }

    private void handleDeleteSportLinkCommand(long chatId, String link) {
        deleteControllerForChatId(link, chatId);
        ChatBotConfig.getInstance().updateControllers(controllerListByChatId);
        goToMenu(chatId, InterfaceBot.MenuList.DELETECONTROLLER);
    }

    private void handleDeleteIdChampCommand(long chatId, String champId) {
        String controllerId = getControllerByChampId(chatId, champId);
        if (!controllerId.isEmpty()) {
            deleteControllerForChatId(controllerId, chatId);
            ChatBotConfig.getInstance().updateControllers(controllerListByChatId);
            goToMenu(chatId, InterfaceBot.MenuList.DELETECONTROLLER);
        }
    }

    private void handleAddFilterCommand(long chatId, String word) {
        FilterEvent.addFilter(word);
        ChatBotConfig.getInstance().updateFilterEvents(FilterEvent.getFilterList());
        telegramBot.buildAndSendMessage(chatId, "Фильтр добавлен: " + word);
        goToMenu(chatId, InterfaceBot.MenuList.FILTER);
    }

    private void handleDeleteFilterCommand(long chatId, String word) {
        FilterEvent.deleteFilter(word);
        ChatBotConfig.getInstance().updateFilterEvents(FilterEvent.getFilterList());
        telegramBot.buildAndSendMessage(chatId, "Фильтр удален...");
        goToMenu(chatId, InterfaceBot.MenuList.FILTER);
    }

    private void handleInputRuleSportCommand(long chatId, String argument) {
        String bk = parserButtonParam(argument, BK_CONST);
        String sportId = parserButtonParam(argument, SPORT_ZIP_CONST);

        userStates.put(chatId, UserState.WAITING_FOR_FILTER_RULE);
        userTempData.put(chatId, new UserTempData(bk, sportId));

        LinkedHashMap<String, String> buttonsMap = new LinkedHashMap<>();
        buttonsMap.put("Отмена", createCancelButton(bk));
        buttonsMap.putAll(InterfaceBot.menu_cancel_input);

        Menu menu = new Menu(
                """
                        Введите правило фильтрации для этого вида спорта, используя регулярные выражения.
                        Примеры:
                        • 'футбол' - только события содержащие слово 'футбол'
                        • '^(?!.*теннис).*$' - исключить все события с 'теннис'
                        • 'футбол|хоккей' - только футбол или хоккей
                        """,
                InterfaceBot.createButton(buttonsMap),
                1
        );

        deleteLastMessage(chatId);
        List<Message> messages = telegramBot.buildAndSendMessage(chatId, menu.getTitle(), menu.drawMenu());
        updateMessageIds(chatId, messages);
    }

    private String createCancelButton(String bk) {
        return NAVIGATION_CONST + "=" + InterfaceBot.MenuList.SELECTSPORT +
                "&" + InterfaceBot.CONTROLLER_ZIP_CONST + "=" + SPORT_ZIP_CONST +
                "&" + BK_CONST + "=" + bk;
    }

    /**
     * Просмотр информации о контроллере
     */
    private void viewController(String link, long chatId) {
        Controller controller = controllerById.get(link);
        if (controller == null) return;

        StringBuilder sb = new StringBuilder("Информация по контролируемому объекту:");
        sb.append("\nID: ").append(controller.getId());
        sb.append("\nСтатус: ").append(controller.getTaskStatus());

        sb.append("\nЧаты: ");
        controller.getChatIdList().forEach(chat -> sb.append(chat).append(" "));

        Page page = controller.getPage();
        sb.append("\n\nСтраница:")
                .append("\nID чемпионата: ").append(page.getIdChamp())
                .append("\nНазвание: ").append(page.getTitle())
                .append("\nСсылка: ").append(page.getLink())
                .append("\nПоследняя проверка: ").append(page.getLastTryUpdate())
                .append("\nПоследнее обновление: ").append(page.getLastUpdate())
                .append("\nПоследнее добавление: ").append(page.getLastNewAdd());

        if (!page.getEvents().isEmpty()) {
            sb.append("\n\nСобытия:");
            page.getEvents().forEach(event ->
                    sb.append("\n\nID: ").append(event.getEventId())
                            .append("\nНазвание: ").append(event.getTitle())
                            .append("\nДобавлен: ").append(event.getAddDate())
                            .append("\nСсылка: ").append(event.getLink()));
        }

        telegramBot.buildAndSendMessage(chatId, sb.toString());
    }

    /**
     * Запуск бота для конкретного чата
     */
    private void startBot(long chatId, String userName) {
        chatIdList.add(chatId);

        telegramBot.buildAndSendMessage(chatId, "Загрузка фильтра контроля...");
        loadFilters(chatId);

        telegramBot.buildAndSendMessage(chatId, "Загрузка настроек контроля...");
        loadControllers(chatId);

        telegramBot.buildAndSendMessage(chatId, "Здравствуйте, " + userName + "! Я начинаю работать...");
        showGeneralMenu(chatId);

        notifyReleaseIfNeeded(chatId);
    }

    private void loadFilters(long chatId) {
        try {
            Set<String> filters = ChatBotConfig.getInstance().getFilterEvents();
            filters.forEach(FilterEvent::addFilter);
        } catch (Exception e) {
            log.error("Error loading filters for chat: {}", chatId, e);
            telegramBot.buildAndSendMessage(chatId, "Ошибка загрузки фильтров");
        }
    }

    private void loadControllers(long chatId) {
        try {
            Map<Long, List<ControllerInfo>> controllersInfo = ChatBotConfig.getInstance().getControllersInfo();
            List<ControllerInfo> chatControllers = controllersInfo.get(chatId);

            if (chatControllers != null) {
                chatControllers.forEach(controller -> addController(controller, chatId, false));
            }
        } catch (Exception e) {
            log.error("Error loading controllers for chat: {}", chatId, e);
            telegramBot.buildAndSendMessage(chatId, "Ошибка загрузки контроллеров");
        }
    }

    private void showGeneralMenu(long chatId) {
        Menu menu = buildMenu(chatId, NAVIGATION_CONST + "=general");
        deleteLastMessage(chatId);
        List<Message> messages = telegramBot.buildAndSendMessage(chatId, menu.getTitle(), menu.drawMenu());
        updateMessageIds(chatId, messages);
    }

    /**
     * Остановка бота для конкретного чата
     */
    private void stopBot(long chatId) {
        telegramBot.buildAndSendMessage(chatId, "Бот остановлен. До свидания!");
        deleteAllByChatId(chatId);
        chatIdList.remove(chatId);
    }

    /**
     * Запуск бота для всех чатов с параметром оповещения
     */
    private void startBotAllChat(long chatId,boolean isMute) {
        chatIdList.addAll(ChatBotConfig.getInstance().getAllChatIds());

        Set<String> filters = ChatBotConfig.getInstance().getFilterEvents();
        FilterEvent.addFilters(filters);

        Map<Long, List<ControllerInfo>> controllersInfo = ChatBotConfig.getInstance().getControllersInfo();
        controllersInfo.forEach((id, controllers) -> {
            controllers.forEach(controller -> addController(controller, id, isMute));
            notifyReleaseIfNeeded(id);
            telegramBot.buildAndSendMessage(chatId, "Запущено для чата " + id);
        });
    }

    /**
     * Вывод списка контроллеров
     */
    private void listController(long chatId) {
        List<Controller> controllers = controllerListByChatId.get(chatId);
        if (controllers == null || controllers.isEmpty()) {
            telegramBot.buildAndSendMessage(chatId, "Нет активных контроллеров");
            return;
        }

        StringBuilder sb = new StringBuilder("Активные контроллеры:\n");
        controllers.forEach(controller ->
                sb.append("\n").append(controller.getPage().getTitle())
                        .append("\n").append(controller.getPage().getLink()).append("\n"));

        telegramBot.buildAndSendMessage(chatId, sb.toString());
    }

    /**
     * Вывод списка активных контроллеров
     */
    private void whoIsAlive(Long chatId) {
        if (controllerById.isEmpty()) {
            telegramBot.buildAndSendMessage(chatId, "Нет активных контроллеров");
            return;
        }

        StringBuilder sb = new StringBuilder("Активные контроллеры:\n");
        int index = 1;

        for (Controller controller : controllerById.values()) {
            sb.append("\n").append(index++).append(") ")
                    .append(controller.getPage().getTitle())
                    .append("\nСтатус: ").append(controller.getTaskStatus())
                    .append("\nФильтр: ").append(formatFilter(controller.getRulesFilter()))
                    .append("\nПоследнее обновление: ").append(controller.getPage().getLastUpdate())
                    .append("\nСсылка: ").append(controller.getPage().getLink()).append("\n");
        }

        telegramBot.buildAndSendMessage(chatId, sb.toString());
    }

    private String formatFilter(Map<Long, String> rulesFilter) {
        if (rulesFilter.isEmpty()) {
            return "❌ Нет фильтра";
        }
        return rulesFilter.entrySet().stream()
                .map(entry -> String.format("Чат %d: %s", entry.getKey(),
                        entry.getValue().isEmpty() ? "нет правила" : entry.getValue()))
                .collect(Collectors.joining("; "));
    }

    /**
     * Вывод списка остановленных контроллеров
     */
    private void whoIsStop(Long chatId) {
        List<Controller> stoppedControllers = controllerById.values().stream()
                .filter(controller ->
                        "CANCELLED".equals(controller.getTaskStatus()) ||
                                "DONE".equals(controller.getTaskStatus()))
                .toList();

        if (stoppedControllers.isEmpty()) {
            telegramBot.buildAndSendMessage(chatId, "Нет остановленных контроллеров");
            return;
        }

        StringBuilder sb = new StringBuilder("Остановленные контроллеры:\n");
        int index = 1;

        for (Controller controller : stoppedControllers) {
            sb.append("\n").append(index++).append(") ")
                    .append(controller.getPage().getTitle())
                    .append("\nСтатус: ").append(controller.getTaskStatus())
                    .append("\nПоследнее обновление: ").append(controller.getPage().getLastUpdate())
                    .append("\nСсылка: ").append(controller.getPage().getLink()).append("\n");
        }

        telegramBot.buildAndSendMessage(chatId, sb.toString());
    }

    /**
     * Вывод списка фильтров
     */
    private void listFilter(Long chatId) {
        Set<String> filters = FilterEvent.getFilterList();
        if (filters.isEmpty()) {
            telegramBot.buildAndSendMessage(chatId, "Нет активных фильтров");
            return;
        }

        StringBuilder sb = new StringBuilder("Активные фильтры:\n");
        int index = 1;

        for (String filter : filters) {
            sb.append("\n").append(index++).append(") ").append(filter);
        }

        telegramBot.buildAndSendMessage(chatId, sb.toString());
    }

    /**
     * Добавление/удаление фильтров
     */
    private void addFilter(String word) {
        FilterEvent.addFilter(word);
    }

    private void deleteAllFilter() {
        FilterEvent.deleteAllFilter();
    }

    private void deleteFilter(String word) {
        FilterEvent.deleteFilter(word);
    }

    /**
     * Работа с контроллерами
     */
    private void addController(String link, Long chatId, boolean isMute) {
        ControllerInfo controllerInfo = new ControllerInfo(link, "", "", new TreeSet<>(), true);
        addController(controllerInfo, chatId, isMute);
    }

    private void addController(ControllerInfo controllerInfo, Long chatId, boolean isMute) {
        String normalizedLink = controllerInfo.getLink().toLowerCase();
        Controller existingController = controllerById.get(normalizedLink);

        if (existingController != null) {
            processExistingController(existingController, controllerInfo, chatId, normalizedLink, isMute);
            return;
        }

        createNewController(controllerInfo, chatId, isMute);
    }

    private void processExistingController(Controller controller, ControllerInfo controllerInfo,
                                           Long chatId, String link, boolean isMute) {
        String newFilterRule = controllerInfo.getRulesFilter();
        String currentFilterRule = controller.getFilterRule(chatId);

        boolean filterChanged = !Objects.equals(newFilterRule, currentFilterRule);

        if (filterChanged) {
            controller.updateFilterRule(chatId, newFilterRule);
        }

        String filterInfo = buildFilterInfo(newFilterRule, currentFilterRule, filterChanged);

        if (controller.getChatIdList().contains(chatId)) {
            if (!isMute) {
                sendControllerExistsMessage(chatId, controller, link, filterChanged, filterInfo);
            }
            return;
        }

        if (controller.addChatId(chatId)) {
            addControllerListByChatId(controller, chatId);
            if (!isMute) {
                sendControllerAddedMessage(chatId, controller, link, filterInfo);
            }
        } else if (!isMute) {
            sendControllerError(chatId, controller, link, filterInfo);
        }
    }

    private void addControllerListByChatId(Controller controller, Long chatId) {
        controllerListByChatId.computeIfAbsent(chatId, k -> new CopyOnWriteArrayList<>()).add(controller);
    }

    private String buildFilterInfo(String newRule, String currentRule, boolean changed) {
        if (currentRule == null) {
            // Случай нового контроллера
            return (newRule != null && !newRule.isEmpty())
                    ? "\n\n⚙️ Установлен фильтр: " + newRule
                    : "\n\n⚙️ Фильтр не установлен";
        }

        if (changed) {
            return (newRule != null && !newRule.isEmpty())
                    ? "\n\n⚙️ Фильтр изменён: " + newRule
                    : "\n\n⚙️ Фильтр удалён";
        }

        return (currentRule != null && !currentRule.isEmpty())
                ? "\n\n⚙️ Фильтр активен: " + currentRule
                : "\n\n⚙️ Фильтр не установлен";
    }

    private void sendControllerExistsMessage(long chatId, Controller controller, String link,
                                             boolean changed, String filterInfo) {
        String message = changed ?
                "Контроль объекта уже существует, правило фильтрации обновлено:\n" +
                        controller.getPage().getTitle() + "\n" + link + filterInfo :
                "Контроль объекта уже существует:\n" +
                        controller.getPage().getTitle() + "\n" + link + filterInfo;

        telegramBot.buildAndSendMessage(chatId, message);
    }

    private void sendControllerAddedMessage(long chatId, Controller controller, String link, String filterInfo) {
        telegramBot.buildAndSendMessage(chatId,
                "Добавлен контроль:\n" + controller.getPage().getTitle() +
                        "\n" + link + filterInfo);
    }

    private void sendControllerError(long chatId, Controller controller, String link, String filterInfo) {
        telegramBot.buildAndSendMessage(chatId,
                "Ошибка при добавлении контроля:\n" +
                        controller.getPage().getTitle() + "\n" + link + filterInfo);
    }

    private void createNewController(ControllerInfo controllerInfo, Long chatId, boolean isMute) {
        try {
            Controller controller = new Controller(controllerInfo, chatId, telegramBot);
            if (!controllerInfo.isSuppressInitialNotifications()) {
                controller.getPage().setEvents(controllerInfo.getEvents());
            }

            controllerById.put(controller.getId().toLowerCase(), controller);
            addControllerListByChatId(controller, chatId);
            controller.start();

            if (!isMute) {
                String filterInfo = buildFilterInfo(controller.getFilterRule(chatId), null, false);
                telegramBot.buildAndSendMessage(chatId,
                        "Добавлен контроль:\n" + controller.getPage().getTitle() +
                                "\n" + controller.getId() + filterInfo);
            }
        } catch (Exception e) {
            log.error("Error creating controller for {}", controllerInfo.getLink(), e);
            if (!isMute) {
                telegramBot.buildAndSendMessage(chatId,
                        "Ошибка создания контроллера для: " + controllerInfo.getLink());
            }
        }
    }

    /**
     * Удаление контроллеров
     */
    private void deleteControllerForChatId(String link, Long chatId) {
        Controller controller = controllerById.get(link);
        if (controller == null) return;

        synchronized (controller) {
            controller.removeChatId(chatId);
            if (controller.getChatIdList().isEmpty()) {
                controller.stopTask();
                controllerById.remove(controller.getId());
            }
        }

        List<Controller> controllers = controllerListByChatId.get(chatId);
        if (controllers != null) {
            controllers.remove(controller);
        }

        telegramBot.buildAndSendMessage(chatId,
                "Удаление выполнено для:\n" + controller.getPage().getTitle() +
                        "\n" + controller.getPage().getLink());
    }

    private void deleteAllByChatId(Long chatId) {
        List<Controller> controllers = controllerListByChatId.get(chatId);
        if (controllers == null) return;

        for (Controller controller : controllers) {
            synchronized (controller) {
                controller.removeChatId(chatId);
                if (controller.getChatIdList().isEmpty()) {
                    controller.stopTask();
                    controllerById.remove(controller.getId());
                }
            }
        }

        controllerListByChatId.remove(chatId);
    }

    /**
     * Вспомогательные методы
     */
    private String getControllerByChampId(long chatId, String champId) {
        List<Controller> controllers = controllerListByChatId.get(chatId);
        if (controllers == null) return "";

        return controllers.stream()
                .filter(c -> champId.equalsIgnoreCase(c.getPage().getIdChamp()))
                .findFirst()
                .map(Controller::getId)
                .orElse("");
    }

    private String getLinkByChampId(String messageText) {
        String bk = parserButtonParam(messageText, BK_CONST);
        String zip = parserButtonParam(messageText, SPORT_ZIP_CONST);

        if (zip.isEmpty()) {
            zip = parserButtonParam(messageText, InterfaceBot.CHAMP_ZIP_CONST);
        }

        if (zip.isEmpty()) return "";

        return switch (bk) {
            case Parser.BASE_URL_1XSTAVKA -> Parser1XStavkaAPI.getLink(zip);
            case Parser.BASE_URL_OLIMP -> ParserOlimpAPI.getLink(zip);
            case Parser.BASE_URL_FONBET -> ParserFonBetAPI.getLink(zip);
            case Parser.BASE_URL_BETCITY -> ParserBetcityAPI.getLink(zip);
            case Parser.BASE_URL_BETBOOM -> ParserBetboomAPI.getLink(zip);
            default -> "";
        };
    }

    private String cutCommandFromFullText(String text) {
        return text.contains(DELIMITER_COMMAND_BOTNAME) ?
                text.substring(0, text.indexOf(DELIMITER_COMMAND_BOTNAME)) :
                text;
    }

    private boolean isCommandForMe(String command) {
        if (!command.contains(DELIMITER_COMMAND_BOTNAME)) {
            return true;
        }

        String botName = command.substring(command.indexOf(DELIMITER_COMMAND_BOTNAME) + 1);
        return telegramBot.getBotUsername().equalsIgnoreCase(botName);
    }

    private void goToMenu(long chatId, InterfaceBot.MenuList view) {
        Menu menu = buildMenu(chatId, NAVIGATION_CONST + "=" + view);
        deleteLastMessage(chatId);
        List<Message> messages = telegramBot.buildAndSendMessage(chatId, menu.getTitle(), menu.drawMenu());
        updateMessageIds(chatId, messages);
    }

    public static List<Controller> getAllControllerByChatId(long chatId) {
        return controllerListByChatId.getOrDefault(chatId, Collections.emptyList());
    }

    private void cleanControllerListByChatId(Map<Long, List<Controller>> controllerMap) {
        controllerMap.forEach((chatId, controllers) -> {
            Map<String, Controller> uniqueControllers = new LinkedHashMap<>();

            for (Controller controller : controllers) {
                String idChamp = controller.getPage().getIdChamp();
                if (uniqueControllers.containsKey(idChamp)) {
                    Controller existing = uniqueControllers.get(idChamp);
                    log.warn("🟡 Chat {}: дубликат idChamp='{}'. Оставляем: {}, отбрасываем: {}",
                            chatId, idChamp,
                            existing.getPage().getLink(),
                            controller.getPage().getLink());
                }
                // либо перезаписываем, либо оставляем первого:
                uniqueControllers.putIfAbsent(idChamp, controller);
            }
            //controllerMap.put(chatId, new ArrayList<>(uniqueControllers.values()));
        });

        log.info("✅ Завершен анализ controllerListByChatId на дубликаты idChamp.");
    }

    // =======================================
// ✅ уведомление о релизе при старте чата
// =======================================
    private void notifyReleaseIfNeeded(long chatId) {
        try {
            String notes = readFileSafe(Path.of("release-notes.md"));
            if (notes == null || notes.isBlank()) return;

            ReleaseBlock top = parseTopReleaseBlock(notes);
            if (top == null) return;

            String last = ChatBotConfig.getInstance().getChatReleaseVersion(chatId);
            if (isNewer(top.version, last)) {
                String msg = "📦 Release notes\n\n" + top.block.trim();
                telegramBot.buildAndSendMessage(chatId, msg);
                ChatBotConfig.getInstance().setChatReleaseVersion(chatId, top.version);
            }
        } catch (Exception ignored) {}
    }

    private static String readFileSafe(Path path) {
        try {
            if (!Files.exists(path)) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static class ReleaseBlock {
        final String version;
        final String block;
        ReleaseBlock(String v, String b) { this.version = v; this.block = b; }
    }

    private static ReleaseBlock parseTopReleaseBlock(String markdown) {
        // допускаем "## v.1.7", "## v1.7", "## 1.7", "## 1.7.0", суффиксы типа -beta и т.д.
        Pattern header = Pattern.compile(
                "^##\\s*(?:[vV]\\.?)?\\s*(\\d+(?:\\.\\d+){1,2})(?:[-._a-zA-Z0-9]*)?\\b.*$",
                Pattern.MULTILINE
        );

        Matcher m = header.matcher(markdown);
        if (!m.find()) return null;

        // ВАЖНО: сохраняем первую версию СРАЗУ,
        // прежде чем искать следующий заголовок
        String firstVersion = m.group(1);     // "1.7" или "1.7.0"
        int blockStart = m.start();
        int searchFrom = m.end();

        // Ищем следующий заголовок отдельным matcher/region,
        // чтобы не сдвинуть текущий match
        Matcher m2 = header.matcher(markdown);
        m2.region(searchFrom, markdown.length());
        int nextStart = m2.find() ? m2.start() : -1;

        String top = (nextStart > blockStart)
                ? markdown.substring(blockStart, nextStart).trim()
                : markdown.substring(blockStart).trim();

        return new ReleaseBlock(firstVersion, top);
    }


    private static boolean isNewer(String v, String last) {
        try {
            int[] a = parseVer(v);
            int[] b = parseVer(last == null ? "0.0.0" : last);
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int ai = i < a.length ? a[i] : 0;
                int bi = i < b.length ? b[i] : 0;
                if (ai != bi) return ai > bi;
            }
            return false;
        } catch (Exception e) {
            return !v.equals(last);
        }
    }

    private static int[] parseVer(String v) {
        if (v == null || v.isBlank()) return new int[]{0,0,0};
        String clean = v.trim()
                .replaceFirst("^[vV]\\.?", "")   // срезаем v или v.
                .replaceAll("\\s.*$", "");       // срезаем всё после пробела (дата и т.п.)

        // берём только первые 3 сегмента цифр, недостающие добиваем нулями
        String[] parts = clean.split("\\.");
        int[] out = new int[]{0, 0, 0};
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            String num = parts[i].replaceAll("[^0-9].*$", ""); // отсекаем суффиксы типа -beta
            out[i] = num.isEmpty() ? 0 : Integer.parseInt(num);
        }
        return out;
    }


}
