package BotValui.Service.fonbet;

import BotValui.Service.Parser;
import BotValui.components.Event;
import BotValui.components.FilterEvent;
import BotValui.components.Page;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

@Slf4j
public class ParserFonBetAPI implements Parser {
    private static final String BASE_SPORT_API =
            "https://line32w.bk6bba-resources.com/events/list?lang=ru&scopeMarket=1600";
            //прошлая перестала работать "https://line05w.bk6bba-cf-resources.com/events/list?lang=ru&scopeMarket=1600";


    private URL link;
    private String typeLine = "";
    private String sportZip = "";
    private String champZip = "";

    public ParserFonBetAPI(String link) throws MalformedURLException {
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
        JSONObject json = null;
        TreeMap<String, String> allSport = new TreeMap<>();
        try {
            //json = (JSONObject) Parser.getJSONObject(BASE_SPORT_API, null);
            json = FonbetCache.getSnapshot();
            if (json == null) {
                log.warn("Fonbet cache is empty (API not yet loaded)");
                return new TreeMap<>();
            }
        } catch (RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
        }

        JSONArray jsonArrayValue = (JSONArray) json.get("sports");
        for (Object o : jsonArrayValue) {
            JSONObject value = (JSONObject) o;
            if (value.get("id") == null || value.get("name") == null || !(value.get("parentId") == null)) continue;
            allSport.put(value.get("name").toString(), value.get("id").toString());
        }
        return allSport;
    }

    public static TreeMap<String, String> getAllChampSport(String sportZip) {
        JSONObject json = null;
        TreeMap<String, String> allChamp = new TreeMap<>();
        try {
            //json = (JSONObject) Parser.getJSONObject(BASE_SPORT_API, null);
            json = FonbetCache.getSnapshot();
            if (json == null) {
                log.warn("Fonbet cache is empty (API not yet loaded)");
                return new TreeMap<>();
            }
        } catch (RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
        }

        JSONArray jsonArrayValue = (JSONArray) json.get("sports");
        for (Object o : jsonArrayValue) {
            JSONObject value = (JSONObject) o;
            if (value.get("parentId") == null || value.get("name") == null || value.get("id") == null) continue;
            if (value.get("parentId").toString().equalsIgnoreCase(sportZip))
                allChamp.put(value.get("name").toString(), value.get("id").toString());
        }
        return allChamp;
    }

    public static TreeMap<String, String> getAllChampSport(String sportZip, JSONObject json) {
        TreeMap<String, String> allChamp = new TreeMap<>();
        JSONArray jsonArrayValue = (JSONArray) json.get("sports");
        for (Object o : jsonArrayValue) {
            JSONObject value = (JSONObject) o;
            if (value.get("parentId") == null || value.get("name") == null || value.get("id") == null) continue;
            if (value.get("parentId").toString().equalsIgnoreCase(sportZip))
                allChamp.put(value.get("name").toString(), value.get("id").toString());
        }
        return allChamp;
    }

    public static String getLink(String zip) {
        JSONObject json = null;
        try {
            //json = (JSONObject) Parser.getJSONObject(BASE_SPORT_API, null);
            json = FonbetCache.getSnapshot();
            if (json == null) {
                log.warn("Fonbet cache is empty (API not yet loaded)");
                return "";
            }
        } catch (RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
        }

        JSONArray jsonArrayValue = (JSONArray) json.get("sports");
        for (Object o : jsonArrayValue) {
            JSONObject value = (JSONObject) o;
            if (value.get("id") == null) continue;
            if (value.get("id").toString().equalsIgnoreCase(zip))
                return ("https://www." + Parser.BASE_URL_FONBET + "/sports/" + (value.get("parentId") == null ? zip : (value.get("parentId").toString() +
                        "/" + zip))).toLowerCase();
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
        JSONObject jsonAPI = null;
        try {
            //jsonAPI = (JSONObject) Parser.getJSONObject(BASE_SPORT_API, null);
            jsonAPI = FonbetCache.getSnapshot();
            if (jsonAPI == null) {
                log.warn("Fonbet cache is empty (API not yet loaded)");
            }
        } catch (RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
        }

        String autoTitle = jsonAPI != null ? getTitle(jsonAPI) : EMPTY_LINE;
        TreeSet<Event> events = jsonAPI != null ? getEvents(jsonAPI) : new TreeSet<>();
        String baseTitle = isTitleValid(customTitlePart) ? customTitlePart : "ФОН: " + autoTitle;
        String finalTitle = isTitleValid(autoTitle) ? baseTitle :
                (baseTitle.startsWith(WARNING_ICON) ? baseTitle : WARNING_ICON + baseTitle);

        Page page = new Page(link.toString(), champZip, finalTitle);
        page.setEvents(events);
        return page;
    }

    private String getTitle(JSONObject jsonObject) {
        JSONArray jsonArray = (JSONArray) jsonObject.get("sports");
        for (Object value : jsonArray) {
            JSONObject jsonObjectSource = (JSONObject) value;
            if (!(jsonObjectSource.get("id") == null) && jsonObjectSource.get("id").toString().equals(champZip.isEmpty() ? sportZip : champZip)) {
                if (jsonObjectSource.get("name") == null) continue;
                return jsonObjectSource.get("name").toString();
            }
        }
        return EMPTY_LINE;
    }

    private TreeSet<Event> getEvents(JSONObject jsonObject) {
        TreeSet<Event> events = new TreeSet<>();
        if (champZip.isEmpty()) {
            for (Map.Entry entry : getAllChampSport(sportZip, jsonObject).entrySet()) {
                events.add(new Event(entry.getValue().toString(), link + "/" + entry.getValue().toString(), entry.getKey().toString()));
            }
        } else {
            JSONArray jsonArray = (JSONArray) jsonObject.get("events");
            for (Object o : jsonArray) {
                JSONObject jsonObjectEvent = (JSONObject) o;
                if ((jsonObjectEvent.get("sportId") == null) || !(jsonObjectEvent.get("parentId") == null) ||
                        !(jsonObjectEvent.get("sportId").toString().equals(champZip))) continue;
                if (jsonObjectEvent.get("team1") == null || jsonObjectEvent.get("team2") == null) continue;
                String team1 = jsonObjectEvent.get("team1").toString();
                String team2 = jsonObjectEvent.get("team2").toString();
                if (FilterEvent.contains(team1) || FilterEvent.contains(team2)) continue;
                events.add(new Event(jsonObjectEvent.get("id").toString(), link + "/" + jsonObjectEvent.get("id").toString()
                        , team1 + " - " + team2));
            }
        }
        return events;
    }
}