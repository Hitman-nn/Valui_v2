package BotValui.config;

import BotValui.components.Controller;
import BotValui.components.Event;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ChatBotConfig {
    private static final String CHAT_CONFIG_FILE = "chatConfig.json";
    private static final String CONTROLLERS_KEY = "controller";
    private static final String FILTERS_KEY = "filter";
    private static final String CHAT_ID_KEY = "chatid";
    private static final String LINK_KEY = "link";
    private static final String TITLE_KEY = "title";
    private static final String RULES_FILTER = "ruleFilter";
    private static final String EVENTS_KEY = "events";
    private static final String RELEASES_KEY = "release";

    private static volatile ChatBotConfig instance;

    private ChatBotConfig() {
    }

    public static synchronized ChatBotConfig getInstance() {
        if (instance == null) {
            instance = new ChatBotConfig();
        }
        return instance;
    }

    private File getChatConfigFile() {
        File chatConfig = new File("./" + CHAT_CONFIG_FILE);
        try {
            if (!chatConfig.exists()) {
                chatConfig.createNewFile();
                initializeEmptyConfig(chatConfig);
            }
        } catch (IOException e) {
            log.error("Error creating chatConfig.json", e);
        }
        return chatConfig;
    }

    private void initializeEmptyConfig(File configFile) throws IOException {
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(new JSONObject().toJSONString());
        }
    }

    private JSONObject readConfig() {
        try {
            String content = Files.readString(getChatConfigFile().toPath());
            return (JSONObject) new JSONParser().parse(content);
        } catch (IOException | ParseException e) {
            log.error("Error reading config file", e);
            return new JSONObject();
        }
    }

    private void writeConfig(JSONObject config) {
        try (FileWriter writer = new FileWriter(getChatConfigFile())) {
            writer.write(config.toJSONString());
        } catch (IOException e) {
            log.error("Error writing to config file", e);
        }
    }

    public void updateFilterEvents(Set<String> filterList) {
        JSONObject config = readConfig();
        JSONArray filterArray = new JSONArray();
        filterArray.addAll(filterList);
        config.put(FILTERS_KEY, filterArray);
        writeConfig(config);
    }

    public Set<Long> getAllChatIds() {
        Set<Long> chatIds = ConcurrentHashMap.newKeySet();
        JSONObject config = readConfig();

        if (config.containsKey(CONTROLLERS_KEY)) {
            JSONArray controllers = (JSONArray) config.get(CONTROLLERS_KEY);
            controllers.forEach(item -> {
                JSONObject controller = (JSONObject) item;
                chatIds.add(Long.parseLong(controller.get(CHAT_ID_KEY).toString()));
            });
        }

        return chatIds;
    }

    public Set<String> getFilterEvents() {
        JSONObject config = readConfig();
        if (!config.containsKey(FILTERS_KEY)) return ConcurrentHashMap.newKeySet();

        Set<String> filters = ConcurrentHashMap.newKeySet();
        ((JSONArray) config.get(FILTERS_KEY)).forEach(filter -> filters.add(filter.toString()));
        return filters;
    }

    public void updateControllers(Map<Long, List<Controller>> controllersByChat) {
        JSONObject config = readConfig();
        JSONArray controllersArray = new JSONArray();

        controllersByChat.forEach((chatId, controllers) -> {
            controllers.forEach(controller -> {
                JSONObject item = new JSONObject();
                item.put(CHAT_ID_KEY, chatId.toString());
                item.put(LINK_KEY, controller.getPage().getLink());
                item.put(TITLE_KEY, controller.getPage().getTitle());

                // Обработка null для правила фильтрации
                String filterRule = controller.getFilterRule(chatId);
                item.put(RULES_FILTER, filterRule != null ? filterRule : "");

                // Добавляем события контроллера
                JSONArray eventsArray = new JSONArray();
                controller.getPage().getEvents().forEach(event -> {
                    JSONObject eventObj = new JSONObject();
                    eventObj.put("eventId", event.getEventId());
                    eventObj.put("link", event.getLink());
                    eventObj.put("title", event.getTitle());
                    eventObj.put("timestamp", event.getAddDate().toString());
                    eventsArray.add(eventObj);
                });
                item.put(EVENTS_KEY, eventsArray);

                controllersArray.add(item);
            });
        });

        config.put(CONTROLLERS_KEY, controllersArray);
        writeConfig(config);
    }

    public Map<Long, List<ControllerInfo>> getControllersInfo() {
        Map<Long, List<ControllerInfo>> result = new ConcurrentHashMap<>();
        JSONObject config = readConfig();

        if (config.containsKey(CONTROLLERS_KEY)) {
            JSONArray controllers = (JSONArray) config.get(CONTROLLERS_KEY);
            controllers.forEach(item -> {
                JSONObject controller = (JSONObject) item;
                Long chatId = Long.parseLong(controller.get(CHAT_ID_KEY).toString());

                Set<Event> events = ConcurrentHashMap.newKeySet();
                if (controller.containsKey(EVENTS_KEY)) {
                    JSONArray eventsJson = (JSONArray) controller.get(EVENTS_KEY);
                    eventsJson.forEach(eventObj -> {
                        JSONObject eventJson = (JSONObject) eventObj;
                        events.add(new Event(
                                getStringSafe(eventJson, "eventId"),
                                getStringSafe(eventJson, "link"),
                                getStringSafe(eventJson, "title"),
                                getStringSafe(eventJson, "timestamp")
                        ));
                    });
                }

                ControllerInfo info = new ControllerInfo(
                        getStringSafe(controller, LINK_KEY),
                        getStringSafe(controller, TITLE_KEY),
                        getStringSafe(controller, RULES_FILTER),
                        events,
                        false
                );

                result.computeIfAbsent(chatId, k -> new ArrayList<>()).add(info);
            });
        }
        return result;
    }

    private String getStringSafe(JSONObject json, String key) {
        Object value = json.get(key);
        return value != null ? value.toString() : "";
    }

    // =======================================
// ✅ новые методы для версий релизов по чатам
// =======================================

    public String getChatReleaseVersion(long chatId) {
        JSONObject config = readConfig();
        Object rel = config.get(RELEASES_KEY);
        if (!(rel instanceof JSONObject releases)) {
            return "0.0.0";
        }
        Object v = releases.get(String.valueOf(chatId));
        return v != null ? v.toString() : "0.0.0";
    }

    public void setChatReleaseVersion(long chatId, String version) {
        JSONObject fullConfig = readConfig();

        // если файл пуст или повреждён — не теряем ничего
        if (fullConfig == null) {
            fullConfig = new JSONObject();
        }

        // аккуратно достаём существующую секцию release
        JSONObject releases = null;
        Object rel = fullConfig.get(RELEASES_KEY);
        if (rel instanceof JSONObject) {
            releases = (JSONObject) rel;
        } else {
            releases = new JSONObject();
            fullConfig.put(RELEASES_KEY, releases);
        }

        // обновляем / добавляем конкретный chatId
        releases.put(String.valueOf(chatId), version);

        // ✅ теперь пишем обратно, не трогая другие секции
        try {
            // читаем старый файл как текст (если есть)
            File file = getChatConfigFile();
            JSONObject existing;
            try {
                String old = Files.readString(file.toPath());
                existing = (JSONObject) new JSONParser().parse(old);
            } catch (Exception e) {
                existing = new JSONObject();
            }

            // мерджим старое содержимое с новым "release"
            existing.put(RELEASES_KEY, releases);

            try (FileWriter writer = new FileWriter(file)) {
                writer.write(existing.toJSONString());
            }
        } catch (Exception e) {
            log.error("Error updating release version in chatConfig.json", e);
        }
    }


}