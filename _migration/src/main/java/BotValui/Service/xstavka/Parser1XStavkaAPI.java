package BotValui.Service.xstavka;

import BotValui.Service.Parser;
import BotValui.components.Event;
import BotValui.components.FilterEvent;
import BotValui.components.Page;
import BotValui.config.ParserProxyConfigKZ;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.net.Authenticator;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Slf4j
public class Parser1XStavkaAPI implements Parser {

    //пsports=1&champs=118587,118593&count=40&mode=4&country=1&getEmpty=true&virtualSports=true
    private static final String ALL_CHAMP_API = "https://1xbet.kz/service-api/LineFeed/GetChampsZip";
    private static final String ALL_SPORT_API = "https://1xbet.kz/service-api/LineFeed/GetSportsShortZip";
    private static final String BASE_SPORT_API = "https://1xbet.kz/service-api/LineFeed/Get1x2_VZip?";
    private static final String COUNT_API = "1000";
    private static final String MODE_API = "4";

    // TTL кэша справочников (подстрой под себя)
    private static final long CACHE_TTL_MS = 60_000; // 1 мин
    private static volatile CacheEntry SPORTS_CACHE = new CacheEntry(null, 0);
    private static volatile CacheEntry CHAMPS_CACHE = new CacheEntry(null, 0);

    // throttling логов
    private static final long LOG_TTL_MS = 30_000;
    private static final ConcurrentHashMap<String, Long> LAST_LOG = new ConcurrentHashMap<>();

    private URL link; // https://1xstavka.ru/line/football/96463-.../...
    private String typeLine = "";
    private String sportSlug = "";    // paramPath[2] (например football)
    private String champZip = "";     // paramPath[3].split("-")[0]
    private volatile String sportZip; // лениво вычисляемый zip спорта

    static ParserProxyConfigKZ proxyConfig = new ParserProxyConfigKZ();

    private record CacheEntry(JSONObject json, long expiresAt) {}

    // --------- Proxy init ----------
    private static void setupProxyAuth() {
        if (proxyConfig != null && proxyConfig.isProxyEnabled()) {
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                            proxyConfig.getProxyUser(),
                            proxyConfig.getProxyPass().toCharArray()
                    );
                }
            });
        }
    }

    public static void initProxyConfig() {
        proxyConfig = new ParserProxyConfigKZ();
        if (proxyConfig.isProxyEnabled()) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
            setupProxyAuth();
        }
    }

    // --------- ctor ----------
    public Parser1XStavkaAPI(String link) throws MalformedURLException {
        initLink(link);
    }

    private void initLink(String link) throws MalformedURLException {
        if (!link.startsWith("https://")) link = "https://" + link;
        if (link.lastIndexOf("/") == link.length() - 1) link = link.substring(0, link.length() - 1);

        try {
            this.link = new URL(link);
            disassemblyLinkNoNetwork();
        } catch (MalformedURLException e) {
            log.warn("Not correct format link: {}", link, e);
            throw e;
        }
    }

    /**
     * ВАЖНО: никаких сетевых вызовов здесь.
     */
    private void disassemblyLinkNoNetwork() {
        String path = link.getPath();
        String[] paramPath = path.split("/");

        if (paramPath.length > 1) typeLine = paramPath[1];
        if (paramPath.length > 2) sportSlug = paramPath[2];
        if (paramPath.length > 3) champZip = paramPath[3].split("-")[0];
    }

    // --------- Public helpers (API) ----------
    public static TreeMap<String, String> getAllSport() {
        TreeMap<String, String> allSport = new TreeMap<>();
        JSONObject json = getSportsJsonCached();
        JSONArray arr = safeValueArray(json);

        for (Object o : arr) {
            if (!(o instanceof JSONObject value)) continue;
            String n = s(value, "N");
            String i = s(value, "I");
            if (n == null || i == null) continue;
            allSport.put(n, i);
        }
        return allSport;
    }

    public static TreeMap<String, String> getAllChampSport(String sportZip) {
        TreeMap<String, String> allChamp = new TreeMap<>();
        if (sportZip == null || sportZip.isBlank() || "-".equals(sportZip)) return allChamp;

        JSONObject json = getChampsJsonCached();
        return getAllChampSport(sportZip, json);
    }

    public static TreeMap<String, String> getAllChampSport(String sportZip, JSONObject json) {
        TreeMap<String, String> allChamp = new TreeMap<>();
        if (sportZip == null || sportZip.isBlank() || "-".equals(sportZip)) return allChamp;

        JSONArray arr = safeValueArray(json);
        for (Object o : arr) {
            if (!(o instanceof JSONObject value)) continue;

            String si = s(value, "SI");
            String l  = s(value, "L");
            String li = s(value, "LI");
            if (si == null || l == null || li == null) continue;

            if (si.equalsIgnoreCase(sportZip)) {
                allChamp.put(l, li);
            }
        }
        return allChamp;
    }

    public static String getLink(String zip) {
        if (zip == null || zip.isBlank() || "-".equals(zip)) return "";

        JSONObject json = getChampsJsonCached();
        JSONArray arr = safeValueArray(json);

        for (Object o : arr) {
            if (!(o instanceof JSONObject value)) continue;

            // sport-level
            String se = s(value, "SE");
            String si = s(value, "SI");
            if (se != null && si != null && si.equalsIgnoreCase(zip)) {
                return ("https://" + Parser.BASE_URL_1XSTAVKA + "/line/" +
                        normalizeSlug(se)).toLowerCase();
            }

            // league-level
            String le = s(value, "LE");
            String li = s(value, "LI");
            if (se != null && le != null && li != null && li.equalsIgnoreCase(zip)) {
                return ("https://" + Parser.BASE_URL_1XSTAVKA + "/line/" +
                        normalizeSlug(se) + "/" +
                        li + "-" + normalizeSlug(le)).toLowerCase();
            }
        }
        return "";
    }

    // --------- Main behavior ----------
    @Override
    public Page getForControllerPage() {
        return getForControllerPage(null);
    }

    @Override
    public Page getForControllerPage(String customTitlePart) {
        String autoTitle = getTitleSafe();
        String baseTitle = isTitleValid(customTitlePart) ? customTitlePart : "1xСтавка: " + autoTitle;

        String finalTitle = isTitleValid(autoTitle)
                ? baseTitle
                : (baseTitle.startsWith(WARNING_ICON) ? baseTitle : WARNING_ICON + baseTitle);

        Page page = new Page(link.toString(), champZip, finalTitle);
        page.setEvents(getEventsSafe());
        return page;
    }

    // --------- Internals ----------
    private String sportZip() {
        // lazy + thread-safe enough (idempotent)
        String local = sportZip;
        if (local != null) return local;

        String resolved = resolveSportZipFromCache();
        this.sportZip = resolved;
        return resolved;
    }

    private String resolveSportZipFromCache() {
        // sportSlug типа "football" → в API сравнивается с полем "E" (обычно "football", "tennis" и т.п.)
        // раньше ты делал replaceAll("-", " "), сохраним логику.
        String key = (sportSlug == null) ? "" : sportSlug.replace("-", " ");

        JSONObject json = getSportsJsonCached();
        JSONArray arr = safeValueArray(json);

        for (Object o : arr) {
            if (!(o instanceof JSONObject value)) continue;
            String e = s(value, "E");
            String i = s(value, "I");
            if (e == null || i == null) continue;

            if (e.equalsIgnoreCase(key)) {
                return i;
            }
        }
        return "-";
    }

    private String getTitleSafe() {
        // если чемп не выбран — показываем название спорта из sports cache
        if (champZip == null || champZip.isEmpty()) {
            String sz = sportZip();
            if ("-".equals(sz)) return EMPTY_LINE;

            JSONObject json = getSportsJsonCached();
            JSONArray arr = safeValueArray(json);

            for (Object o : arr) {
                if (!(o instanceof JSONObject value)) continue;
                String i = s(value, "I");
                String n = s(value, "N");
                if (i == null || n == null) continue;

                if (i.equalsIgnoreCase(sz)) return n;
            }
            return EMPTY_LINE;
        }

        // если чемп выбран — ищем лигу в champs cache
        JSONObject json = getChampsJsonCached();
        JSONArray arr = safeValueArray(json);

        for (Object o : arr) {
            if (!(o instanceof JSONObject value)) continue;
            String li = s(value, "LI");
            String l  = s(value, "L");
            if (li == null || l == null) continue;

            if (li.equalsIgnoreCase(champZip)) return l;
        }
        return EMPTY_LINE;
    }

    private TreeSet<Event> getEventsSafe() {
        TreeSet<Event> events = new TreeSet<>();

        String sz = sportZip();
        if (sz == null || "-".equals(sz)) return events;

        // если чемп не выбран — вывести список чемпионатов
        if (champZip == null || champZip.isEmpty()) {
            TreeMap<String, String> champs = getAllChampSport(sz);
            for (Map.Entry<String, String> entry : champs.entrySet()) {
                events.add(new Event(entry.getKey(), link + "/" + entry.getValue(), entry.getKey()));
            }
            return events;
        }

        // если чемп выбран — получить матчи
        JSONObject json;
        try {
            json = (JSONObject) Parser.getJSONObject(buildBaseLinkAPI(sz, champZip), proxyConfig);
        } catch (IOException | ParseException | RuntimeException e) {
            logWarnOnce(buildBaseLinkAPI(sz, champZip), e);
            return events;
        }

        JSONArray arr = safeValueArray(json);
        for (Object o : arr) {
            if (!(o instanceof JSONObject value)) continue;

            String li = s(value, "LI");
            if (li == null || !li.equals(champZip)) continue;

            String o1 = s(value, "O1");
            String o2 = s(value, "O2");
            String ci = s(value, "CI");
            if (o1 == null || o2 == null || ci == null) continue;

            if (FilterEvent.contains(o1) || FilterEvent.contains(o2)) continue;

            events.add(new Event(ci, buildEventLink(value), o1 + " - " + o2));
        }

        return events;
    }

    private static String buildBaseLinkAPI(String sportZip, String champZip) {
        return BASE_SPORT_API
                + "sports=" + sportZip
                + "&champs=" + champZip
                + "&count=" + COUNT_API
                + "&mode=" + MODE_API;
    }

    private String buildEventLink(JSONObject value) {
        String ci = s(value, "CI");
        String o1e = s(value, "O1E");
        String o2e = s(value, "O2E");

        // если чего-то не хватает — хотя бы не падать
        if (ci == null || o1e == null || o2e == null) return link.toString();

        return link + "/"
                + ci + "-"
                + normalizeSlug(o1e) + "-"
                + normalizeSlug(o2e);
    }

    // --------- Caching ----------
    private static JSONObject getSportsJsonCached() {
        long now = System.currentTimeMillis();
        CacheEntry c = SPORTS_CACHE;
        if (c.json != null && c.expiresAt > now) return c.json;

        JSONObject fresh = safeGetJson(ALL_SPORT_API);
        if (fresh != null) SPORTS_CACHE = new CacheEntry(fresh, now + CACHE_TTL_MS);
        return fresh;
    }

    private static final long NEGATIVE_TTL_MS = 15_000;

    private static JSONObject getChampsJsonCached() {
        long now = System.currentTimeMillis();
        CacheEntry c = CHAMPS_CACHE;

        if (c.expiresAt > now) {
            return c.json; // может быть null
        }

        JSONObject fresh = safeGetJson(ALL_CHAMP_API);

        if (fresh != null) {
            CHAMPS_CACHE = new CacheEntry(fresh, now + CACHE_TTL_MS);
        } else {
            CHAMPS_CACHE = new CacheEntry(null, now + NEGATIVE_TTL_MS);
        }
        return fresh;
    }

    //private static final Semaphore API_LIMIT = new Semaphore(2);

    private static JSONObject safeGetJson(String url) {
        boolean acquired = false;
        try {
      //      API_LIMIT.acquire();
      //      acquired = true;

            Object o = Parser.getJSONObject(url, proxyConfig);
            return (o instanceof JSONObject jo) ? jo : null;

        } catch (Exception e) {
            logWarnOnce(normalizeUrlForLog(url), e);
            return null;

        //} finally {
         //   if (acquired) API_LIMIT.release();
        }
    }

    private static String normalizeUrlForLog(String url) {
        if (url.contains("Get1x2_VZip")) return "Get1x2_VZip";
        if (url.contains("GetChampsZip")) return "GetChampsZip";
        if (url.contains("GetSportsShortZip")) return "GetSportsShortZip";
        return url;
    }

    private static JSONArray safeValueArray(JSONObject json) {
        if (json == null) return new JSONArray();
        Object v = json.get("Value");
        return (v instanceof JSONArray arr) ? arr : new JSONArray();
    }

    // --------- Logging throttle ----------
    private static void logWarnOnce(String url, Exception e) {
        String key = url + "|" + e.getClass().getSimpleName();
        long now = System.currentTimeMillis();
        Long prev = LAST_LOG.put(key, now);

        if (prev == null || now - prev > LOG_TTL_MS) {
            log.warn("1xstavka API problem: url={} err={}", url, e.toString());
            log.debug("Stacktrace", e);
        }
    }

    // --------- Utils ----------
    private static String s(JSONObject o, String key) {
        Object v = o.get(key);
        return v == null ? null : v.toString();
    }

    private static String normalizeSlug(String raw) {
        return raw
                .replaceAll("\\.", "")
                .replace(" ", "-");
    }
}
