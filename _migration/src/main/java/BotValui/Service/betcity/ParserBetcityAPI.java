package BotValui.Service.betcity;

import BotValui.Service.Parser;
import BotValui.components.Event;
import BotValui.components.Page;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

@Slf4j
public class ParserBetcityAPI implements Parser {
    public static final String BASE_URL_BETCITY = "betcity.ru";
    private static final String ALL_SPORT_API =
            "https://ad.betcity.ru/d/off/sports";
    private static final String ALL_CHAMP_BASE_API =
            "https://ad.betcity.ru/d/off/champs?rev=4";
    private static final String ALL_EVENT_BASE_API =
            "https://ad.betcity.ru/d/off/events?rev=6";

    private URL link;
    private String typeLine = "";
    private String sportZip = "";
    private String champZip = "";

    public ParserBetcityAPI(String link) throws MalformedURLException {
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

    private void disassemblyLink() {
        String path = link.getPath();
        String[] paramPath = path.split("/");
        if (paramPath.length > 2) typeLine = paramPath[2];
        if (paramPath.length > 3) sportZip = getSportZip(paramPath[3]).map(String::valueOf).orElse("-");
        if (paramPath.length > 4) champZip = paramPath[4];
    }

    private Optional<Integer> getSportZip(String sportName) {
        return BetcitySportsMap.getSportId(sportName);
    }

    public static TreeMap<String, String> getAllSport() {
        JSONObject json = null;
        TreeMap<String, String> allSport = new TreeMap<>();
        try {
            json = (JSONObject) Parser.getJSONObject(ALL_SPORT_API, null);
        } catch (IOException | ParseException | RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
        }

        assert json != null;
        JSONObject replyObj = (JSONObject) json.get("reply");

        JSONArray jsonArrayValue = (JSONArray) replyObj.get("sports");
        for (Object o : jsonArrayValue) {
            JSONObject value = (JSONObject) o;
            if (value.get("name_sp") == null || value.get("id_sp") == null) continue;
            allSport.put(value.get("name_sp").toString(), value.get("id_sp").toString());
        }
        return allSport;
    }

    public static TreeMap<String, String> getAllChampSport(String sportZip) {
        JSONObject json = null;
        TreeMap<String, String> allChamp = new TreeMap<>();
        try {
            json = (JSONObject) Parser.getJSONObject(ALL_CHAMP_BASE_API + "&ids_sp=" + sportZip, null);
        } catch (IOException | ParseException | RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
        }

        assert json != null;
        JSONObject replyObj = (JSONObject) json.get("reply");
        replyObj = (JSONObject) replyObj.get("sports");
        replyObj = (JSONObject) replyObj.get(sportZip);
        replyObj = (JSONObject) replyObj.get("chmps");

        for (Object o : replyObj.entrySet()) {
            Map.Entry<String, JSONObject> entry = (Map.Entry<String, JSONObject>) o;
            JSONObject tournament = entry.getValue();
            allChamp.put(tournament.get("name_ch").toString(), entry.getKey());
        }
        return allChamp;
    }

    public static String getLink(String zip) {
        Optional<String> result = BetcitySportsMap.getSport(Integer.valueOf(zip))
                .map(v -> "https://" + Parser.BASE_URL_BETCITY + "/ru/line/" + v);
        if (result.isPresent()) return result.get().toLowerCase();


        JSONObject json = null;
        try {
            json = (JSONObject) Parser.getJSONObject(ALL_CHAMP_BASE_API + "&id_ch=" + zip, null);
        } catch (IOException | ParseException | RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
        }

        assert json != null;
        JSONObject replyObj = (JSONObject) json.get("reply");
        if (replyObj == null) return "";
        replyObj = (JSONObject) replyObj.get("sports");
        if (replyObj == null) return "";
        for (Object sport : replyObj.entrySet()) {
            Map.Entry<String, JSONObject> entrySport = (Map.Entry<String, JSONObject>) sport;
            String sportId = entrySport.getKey();
            result = BetcitySportsMap.getSport(Integer.valueOf(sportId))
                    .map(v -> "https://" + Parser.BASE_URL_BETCITY + "/ru/line/" + v + "/" + zip);
            if (result.isPresent()) return result.get().toLowerCase();
        }
        return "";
    }

    private String getTitle() {
        if (champZip.isEmpty())
            return BetcitySportsMap.getSport(Integer.valueOf(sportZip)).orElseGet(() -> EMPTY_LINE);

        JSONObject json = null;
        try {
            json = (JSONObject) Parser.getJSONObject(ALL_CHAMP_BASE_API + "&id_ch=" + champZip, null);
        } catch (IOException | ParseException | RuntimeException e) {
            log.warn("Error get JSON object API-request", e);
            return EMPTY_LINE;
        }

        assert json != null;
        JSONObject replyObj = (JSONObject) json.get("reply");
        if (replyObj == null) return EMPTY_LINE;
        replyObj = (JSONObject) replyObj.get("sports");
        if (replyObj == null) return EMPTY_LINE;
        for (Object sport : replyObj.entrySet()) {
            Map.Entry<String, JSONObject> entrySport = (Map.Entry<String, JSONObject>) sport;
            JSONObject chmpsObj = (JSONObject) entrySport.getValue().get("chmps");
            if (chmpsObj == null) return EMPTY_LINE;
            for (Object chmp : chmpsObj.entrySet()) {
                Map.Entry<String, JSONObject> entryChmp = (Map.Entry<String, JSONObject>) chmp;
                JSONObject curChmp = entryChmp.getValue();
                if (curChmp.get("id_ch") == null) continue;
                return curChmp.get("name_ch").toString();
            }
        }
        return EMPTY_LINE;
    }

    public TreeSet<Event> getEvents() {
        TreeSet<Event> events = new TreeSet<>();
        JSONObject json = null;
        if (champZip.isEmpty()) {
            for (Map.Entry entry : getAllChampSport(sportZip).entrySet()) {
                events.add(new Event(entry.getKey().toString(), link + "/" + entry.getKey().toString(), entry.getKey().toString()));
            }
        } else {
            try {
                Map<String, String> formData = new HashMap<>();
                formData.put("ids", champZip);
                json = (JSONObject) Parser.getJSONObject(ALL_EVENT_BASE_API, null, formData);
            } catch (IOException | ParseException | RuntimeException e) {
                log.warn("Error get JSON object API-request", e);
                return events;
            }
            assert json != null;
            JSONObject replyObj = (JSONObject) json.get("reply");
            if (replyObj == null) return events;
            replyObj = (JSONObject) replyObj.get("sports");
            if (replyObj == null) return events;
            replyObj = (JSONObject) replyObj.get(sportZip);
            if (replyObj == null) return events;
            replyObj = (JSONObject) replyObj.get("chmps");
            if (replyObj == null) return events;
            replyObj = (JSONObject) replyObj.get(champZip);
            if (replyObj == null) return events;
            replyObj = (JSONObject) replyObj.get("evts");
            if (replyObj == null) return events;
            for (Object evt : replyObj.entrySet()) {
                Map.Entry<String, JSONObject> entryEvt = (Map.Entry<String, JSONObject>) evt;
                JSONObject curEvt = entryEvt.getValue();
                events.add(new Event(entryEvt.getKey(), link + "/" + entryEvt.getKey(), curEvt.get("name_ht") + " - " + curEvt.get("name_at")));
            }
        }
        return events;
    }

    @Override
    public Page getForControllerPage() {
        return getForControllerPage(null);
    }

    @Override
    public Page getForControllerPage(String customTitlePart) {
        String autoTitle = getTitle();
        String baseTitle = isTitleValid(customTitlePart) ? customTitlePart : "БЕТСИТИ: " + autoTitle;
        String finalTitle = isTitleValid(autoTitle) ? baseTitle :
                (baseTitle.startsWith(WARNING_ICON) ? baseTitle : WARNING_ICON + baseTitle);

        Page page = new Page(link.toString(), champZip, finalTitle);
        page.setEvents(getEvents());
        return page;
    }
}
