package BotValui.Service.olimp;

import BotValui.Service.Parser;
import BotValui.components.Event;
import BotValui.components.FilterEvent;
import BotValui.components.Page;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

@Slf4j
public class ParserOlimpAPI implements Parser {
    private static final String ALL_PLANNED_EVENTS_API = "https://www.olimp.bet/api/v4/0/line/planned-events";
    private static final String ALL_CHAMPS_API = "https://www.olimp.bet/api/v4/0/line/sports-with-competitions";
    private static final String ALL_SPORTS_API = "https://www.olimp.bet/api/v4/0/line/sports";
    private URL link = null;
    private String typeLine = "";
    private String sportZip = "";
    private String champZip = "";

    public ParserOlimpAPI(String link) throws MalformedURLException {
        initLink(link);
    }

    private void initLink(String link) throws MalformedURLException {
        if (!link.startsWith("https://")) link = "https://" + link;
        if (link.lastIndexOf("/") == link.length() - 1) link = link.substring(0, link.length() - 1);
        try {
            this.link = new URL(link);
            disassemblyLink();
        } catch (MalformedURLException e) {
            log.warn("Not correct format link: " + link, e);
            throw e;
        }
    }

    public static TreeMap<String, String> getAllSport() {
        JSONArray json = null;
        TreeMap<String, String> allSport = new TreeMap<>();
        try {
            json = (JSONArray) Parser.getJSONObject(ALL_SPORTS_API, null);
        } catch (IOException | ParseException | RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
        }

        for (Object object : json) {
            JSONObject item = (JSONObject) object;
            JSONObject payload = (JSONObject) item.get("payload");
            if (payload.get("id") == null || payload.get("name") == null) continue;
            allSport.put(payload.get("name").toString(), payload.get("id").toString());
        }

        return allSport;
    }

    public static TreeMap<String, String> getAllChampSport(String sportZip) {
        JSONArray json = null;
        TreeMap<String, String> allChamp = new TreeMap<>();
        try {
            json = (JSONArray) Parser.getJSONObject(ALL_CHAMPS_API, null);
        } catch (IOException | ParseException | RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
        }

        for (Object object : json) {
            JSONObject item = (JSONObject) object;
            JSONObject payload = (JSONObject) item.get("payload");
            if ((payload.get("id") == null) || (!payload.get("id").toString().equals(sportZip))) continue;
            if (payload.get("competitions") == null) continue;
            JSONArray competitions = (JSONArray) payload.get("competitions");
            for (Object o : competitions) {
                JSONObject itemCompetition = (JSONObject) o;
                if (itemCompetition.get("id") == null || itemCompetition.get("name") == null) continue;
                if (!(itemCompetition.get("name") == null)) {
                    allChamp.put(itemCompetition.get("name").toString(), itemCompetition.get("id").toString());
                }
            }
        }

        return allChamp;
    }

    public static TreeMap<String, String> getAllChampSport(String sportZip, JSONArray json) {
        TreeMap<String, String> allChamp = new TreeMap<>();
        for (Object object : json) {
            JSONObject item = (JSONObject) object;
            JSONObject payload = (JSONObject) item.get("payload");
            if ((payload.get("id") == null) || (!payload.get("id").toString().equals(sportZip))) continue;
            if (payload.get("competitions") == null) continue;
            JSONArray competitions = (JSONArray) payload.get("competitions");
            for (Object o : competitions) {
                JSONObject itemCompetition = (JSONObject) o;
                if (itemCompetition.get("id") == null || itemCompetition.get("name") == null) continue;
                if (!(itemCompetition.get("name") == null)) {
                    allChamp.put(itemCompetition.get("name").toString(), itemCompetition.get("id").toString());
                }
            }
        }
        return allChamp;
    }

    public static String getLink(String zip) {
        JSONArray json = null;
        try {
            json = (JSONArray) Parser.getJSONObject(ALL_CHAMPS_API, null);
        } catch (IOException | ParseException | RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
        }

        for (Object object : json) {
            JSONObject item = (JSONObject) object;
            JSONObject payload = (JSONObject) item.get("payload");
            if (payload.get("sport") == null) continue;
            JSONObject sport = (JSONObject) payload.get("sport");
            if (sport.get("id") == null) continue;
            if (sport.get("id").toString().equalsIgnoreCase(zip))
                return ("https://www." + Parser.BASE_URL_OLIMP + "/line/" + sport.get("id").toString()).toLowerCase();
            if (payload.get("competitions") == null) continue;
            JSONArray competitions = (JSONArray) payload.get("competitions");
            for (Object o : competitions) {
                JSONObject itemCompetition = (JSONObject) o;
                if (itemCompetition.get("id") == null || itemCompetition.get("sportId") == null) continue;
                if (itemCompetition.get("id").toString().equalsIgnoreCase(zip))
                    return ("https://www." + Parser.BASE_URL_OLIMP + "/line/" + itemCompetition.get("sportId").toString() + "/" +
                            itemCompetition.get("id").toString()).toLowerCase();
            }
        }
        return "";
    }

    private void disassemblyLink() {
        String path = link.getPath();
        String[] paramPath = path.split("/");
        if (paramPath.length > 1) typeLine = paramPath[1];
        if (paramPath.length > 2) sportZip = paramPath[2];
        if (paramPath.length > 3) champZip = paramPath[3];
    }

    @Override
    public Page getForControllerPage() {
        return getForControllerPage(null);
    }

    @Override
    public Page getForControllerPage(String customTitlePart) {
        JSONArray jsonAPI = null;
        try {
            String apiUrl = champZip.isEmpty() ? ALL_CHAMPS_API : ALL_PLANNED_EVENTS_API;
            jsonAPI = (JSONArray) Parser.getJSONObject(apiUrl, null);

        } catch (IOException | ParseException | RuntimeException e) {
            log.warn("Error getting JSON object from API request", e);
        }
        String autoTitle = jsonAPI != null ? getTitle(jsonAPI) : EMPTY_LINE;
        TreeSet<Event> events = jsonAPI != null ? getEvents(jsonAPI) : new TreeSet<>();
        // Формируем базовый заголовок
        String baseTitle = isTitleValid(customTitlePart) ? customTitlePart : "Олимп: " + autoTitle;
        String finalTitle = isTitleValid(autoTitle) ? baseTitle :
                (baseTitle.startsWith(WARNING_ICON) ? baseTitle : WARNING_ICON + baseTitle);

        Page page = new Page(link.toString(), champZip, finalTitle);
        page.setEvents(events);
        return page;
    }

    private TreeSet<Event> getEvents(JSONArray jsonAPI) {
        TreeSet<Event> events = new TreeSet<>();
        if (champZip.isEmpty()) {
            for (Map.Entry entry : getAllChampSport(sportZip, jsonAPI).entrySet()) {
                events.add(new Event(entry.getValue().toString(), link + "/" + entry.getValue().toString(), entry.getKey().toString()));
            }
        } else {
            for (Object object : jsonAPI) {
                JSONObject item = (JSONObject) object;
                JSONObject payload = (JSONObject) item.get("payload");
                if ((payload.get("competitionId") == null) || (!payload.get("competitionId").toString().equals(champZip)))
                    continue;
                if ((payload.get("name") == null) || (FilterEvent.contains(payload.get("name").toString()))) continue;
                Event event = new Event(payload.get("id").toString(), link.toString() + "/" + payload.get("id").toString(),
                        payload.get("name").toString());
                events.add(event);
            }
        }
        return events;
    }

    private String getTitle(JSONArray jsonAPI) {
        for (Object object : jsonAPI) {
            JSONObject item = (JSONObject) object;
            JSONObject payload = (JSONObject) item.get("payload");
            if (champZip.isEmpty()) {
                if ((payload.get("id") == null) || (!payload.get("id").toString().equals(sportZip))) continue;
                if (payload.get("sport") == null) continue;
                JSONObject sport = (JSONObject) payload.get("sport");
                if (sport.get("name") == null) continue;
                return sport.get("name").toString();
            } else {
                if ((payload.get("sportId") == null) || (!payload.get("sportId").toString().equals(sportZip))
                        || payload.get("competitionId") == null || (!payload.get("competitionId").toString().equals(champZip)))
                    continue;
                if (!(payload.get("competitionName") == null)) return payload.get("competitionName").toString();
            }
        }
        return EMPTY_LINE;
    }
}
