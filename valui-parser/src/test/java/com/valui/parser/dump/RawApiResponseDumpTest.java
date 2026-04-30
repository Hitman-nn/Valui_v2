package com.valui.parser.dump;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.util.JsonFormat;
import com.valui.parser.bookmaker.betboom.BetBoomSubscribeBuilder;
import com.valui.parser.bookmaker.betboom.ws.WsClientBorrowingPool;
import com.valui.parser.bookmaker.betboom.ws.WsPoolProperties;
import com.valui.parser.bookmaker.betboom.ws.WsRequestService;
import com.valui.parser.http.BookmakerHttpClient;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.junit.jupiter.api.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import proto.betboom.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Manual test — captures raw API responses from all bookmakers for structure analysis.
 *
 * Run with:
 *   mvn test -pl valui-parser -Dtest=RawApiResponseDumpTest -Dsurefire.failIfNoSpecifiedTests=false
 *
 * Results saved to: valui-parser/build/api-dumps/
 *
 * Files produced:
 *   fonbet_sports_sample.json     — первые 3 sport-записи из снапшота
 *   fonbet_events_sample.json     — первые 3 top-level события (без parentId)
 *   xbet_sports.json              — ответ GetSportsShortZip
 *   xbet_tournaments_sample.json  — ответ GetChampsZip, первые 3 записи
 *   xbet_matches_sample.json      — ответ Get1x2_VZip для первого турнира
 *   olimp_sports_sample.json      — ответ /sports, первые 3
 *   olimp_tournaments_sample.json — ответ /sports-with-competitions, первые 3
 *   olimp_events_sample.json      — ответ /planned-events, первые 3
 *   betcity_sports.json           — ответ /sports целиком (маленький)
 *   betcity_tournaments_sample.json — ответ /champs, первые 3 турнира
 *   betcity_events_sample.json    — ответ /events POST, первые 3 матча
 *   betboom_sports_proto.json     — SportAllBody decoded from proto
 *   betboom_tournaments_proto.json — TournamentListFrame decoded from proto
 *   betboom_matches_proto.json    — MatchesFrame decoded (содержит markets с odds_raw1/odds_raw2!)
 *
 * Note: 1xBet может требовать SOCKS-прокси. BetBoom требует доступ к wss://ru-ws.sporthub.bet:444.
 */
@Tag("manual")
@Disabled("Run manually to capture live API responses. See class javadoc for instructions.")
class RawApiResponseDumpTest {

    private static final Path OUT = Path.of("build/api-dumps");
    private static final int SAMPLE = 3;
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // JDK JSSE TLS stack — accepted by 1xBet and BetCity (Netty JA3 is rejected)
    private static final HttpClient JDK_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @BeforeAll
    static void createOutputDir() throws IOException {
        Files.createDirectories(OUT);
    }

    // ── Fonbet ────────────────────────────────────────────────────────────────

    @Test
    void dump_fonbet() throws Exception {
        // Single snapshot contains both sports and events in one response.
        // Buffer 50 MB — CDN response regularly exceeds the default 10 MB limit.
        BookmakerHttpClient http = client(50 * 1024 * 1024);
        String url = "https://line32w.bk6bba-resources.com/events/list?lang=ru&scopeMarket=1600";

        JsonNode snap = http.blockGet(http.getJson(url, JsonNode.class));

        ObjectNode sportsSample = MAPPER.createObjectNode();
        sportsSample.set("sports", takeSample(snap.path("sports"), SAMPLE));
        save("fonbet_sports_sample.json", sportsSample);

        // Top-level events only (no parentId) contain startTime field
        ArrayNode evtSample = MAPPER.createArrayNode();
        int count = 0;
        for (JsonNode ev : iter(snap.path("events"))) {
            if (ev.has("parentId")) continue;
            evtSample.add(ev);
            if (++count >= SAMPLE) break;
        }
        ObjectNode evtOut = MAPPER.createObjectNode();
        evtOut.set("events", evtSample);
        save("fonbet_events_sample.json", evtOut);

        log("fonbet", snap.path("sports").size() + " sports, "
                + snap.path("events").size() + " events in snapshot");
    }

    // ── 1xBet ─────────────────────────────────────────────────────────────────

    @Test
    void dump_xbet() throws Exception {
        // 1xBet rejects Netty's JA3 fingerprint; JDK JSSE is accepted without a proxy.
        String base = "https://1xbet.kz/service-api/LineFeed";

        JsonNode sports;
        try {
            sports = jdkGetJson(base + "/GetSportsShortZip");
        } catch (Exception e) {
            log("xbet", "SKIPPED — " + e.getMessage());
            return;
        }
        save("xbet_sports.json", sample(sports, SAMPLE));

        JsonNode champs = jdkGetJson(base + "/GetChampsZip");
        save("xbet_tournaments_sample.json", sample(champs, SAMPLE));

        String firstChampId = null, firstSportId = null;
        for (JsonNode v : iter(champs.path("Value"))) {
            if (v.has("LI") && v.has("SI")) {
                firstChampId = v.path("LI").asText();
                firstSportId = v.path("SI").asText();
                break;
            }
        }
        if (firstChampId != null) {
            String matchUrl = base + "/Get1x2_VZip?sports=" + firstSportId
                    + "&champs=" + firstChampId + "&count=50&mode=4";
            JsonNode matches = jdkGetJson(matchUrl);
            save("xbet_matches_sample.json", sample(matches, SAMPLE));
            log("xbet", "matches from: " + matchUrl);
        }
        log("xbet", "champs count: " + champs.path("Value").size());
    }

    // ── Olimp ─────────────────────────────────────────────────────────────────

    @Test
    void dump_olimp() throws Exception {
        // /planned-events response regularly exceeds 10 MB
        BookmakerHttpClient http = client(50 * 1024 * 1024);
        String base = "https://www.olimp.bet/api/v4/0/line";

        JsonNode sports = http.blockGet(http.getJson(base + "/sports", JsonNode.class));
        save("olimp_sports_sample.json", takeSample(sports, SAMPLE));
        log("olimp", "sports count: " + sports.size());

        JsonNode champs = http.blockGet(http.getJson(base + "/sports-with-competitions", JsonNode.class));
        save("olimp_tournaments_sample.json", takeSample(champs, SAMPLE));

        // /planned-events returns all events for all competitions in one response
        JsonNode events = http.blockGet(http.getJson(base + "/planned-events", JsonNode.class));
        save("olimp_events_sample.json", takeSample(events, SAMPLE));
        log("olimp", "planned-events count: " + events.size());
    }

    // ── BetCity ───────────────────────────────────────────────────────────────

    @Test
    void dump_betcity() throws Exception {
        // BetCity rejects Netty's JA3 fingerprint; JDK JSSE is accepted without a proxy.
        String base = "https://ad.betcity.ru/d/off";

        JsonNode sports;
        try {
            sports = jdkGetJson(base + "/sports");
        } catch (Exception e) {
            log("betcity", "SKIPPED — " + e.getMessage());
            return;
        }
        save("betcity_sports.json", sports);
        log("betcity", "sports count: " + sports.path("reply").path("sports").size());

        String firstSportId = null;
        for (JsonNode s : iter(sports.path("reply").path("sports"))) {
            firstSportId = s.path("id_sp").asText(null);
            if (firstSportId != null) break;
        }
        if (firstSportId == null) { log("betcity", "no sports found"); return; }

        JsonNode champs = jdkGetJson(base + "/champs?rev=4&ids_sp=" + firstSportId);
        save("betcity_tournaments_sample.json", sampleObject(champs, SAMPLE));

        String firstChampId = betcityFirstChampId(champs, firstSportId);
        if (firstChampId == null) { log("betcity", "no tournaments found"); return; }

        JsonNode events = jdkPostMultipart(base + "/events?rev=6", "ids", firstChampId);
        save("betcity_events_sample.json", sampleObject(events, SAMPLE));
        log("betcity", "events for tournament: " + firstChampId);
    }

    // ── BetBoom (WebSocket + Protobuf) ────────────────────────────────────────

    @Test
    void dump_betboom() throws Exception {
        WsPoolProperties props = betboomProps();
        WsClientBorrowingPool pool = new WsClientBorrowingPool(props);
        pool.start();
        try {
            WsRequestService ws = new WsRequestService(pool, 10);

            // ── Sports ──────────────────────────────────────────────────────
            byte[] sportReq = BetBoomSubscribeBuilder.sportAllBytes(Current.TypeLine.LINE, 0);
            byte[] sportRaw = ws.sendAndAwaitFiltered(sportReq, 8000, Envelope::hasResponseSportAll);
            if (sportRaw == null) { log("betboom", "timeout waiting for sports"); return; }

            Envelope sportEnv = Envelope.parseFrom(sportRaw);
            ServerFrame sportSf = ServerFrame.parseFrom(sportEnv.getResponseSportAll().toByteArray());
            SportAllBody sportBody = SportAllBody.parseFrom(sportSf.getBody(0));
            save("betboom_sports_proto.json",
                    JsonFormat.printer().includingDefaultValueFields().print(sportBody));
            log("betboom", "sports rows: " + sportBody.getRowsCount());

            int firstSportId = sportBody.getRowsCount() > 0
                    ? sportBody.getRows(0).getSport().getId() : -1;
            if (firstSportId <= 0) { log("betboom", "no sport id found"); return; }

            // ── Tournaments ─────────────────────────────────────────────────
            byte[] tournReq = BetBoomSubscribeBuilder.sportTournamentsBytes(
                    Current.TypeLine.LINE, firstSportId);
            byte[] tournRaw = ws.sendAndAwaitFiltered(tournReq, 8000,
                    Envelope::hasResponseSportTournaments);
            if (tournRaw == null) { log("betboom", "timeout waiting for tournaments"); return; }

            Envelope tournEnv = Envelope.parseFrom(tournRaw);
            ServerFrame tournSf = ServerFrame.parseFrom(
                    tournEnv.getResponseSportTournaments().toByteArray());
            TournamentListFrame tlf = TournamentListFrame.parseFrom(tournSf.getBody(0));
            save("betboom_tournaments_proto.json",
                    JsonFormat.printer().includingDefaultValueFields().print(tlf));
            log("betboom", "tournament entries: " + tlf.getList().getEntriesCount());

            int firstTournId = tlf.getList().getEntriesCount() > 0
                    ? tlf.getList().getEntries(0).getTournament().getId() : -1;
            if (firstTournId <= 0) { log("betboom", "no tournament id found"); return; }

            // ── Matches (contains markets with odds_raw1 / odds_raw2) ────────
            byte[] matchReq = BetBoomSubscribeBuilder.tournamentMatchesBytes(
                    Current.TypeLine.LINE, firstTournId);
            byte[] matchRaw = ws.sendAndAwaitFiltered(matchReq, 8000,
                    Envelope::hasResponseTournamentMatches);
            if (matchRaw == null) { log("betboom", "timeout waiting for matches"); return; }

            Envelope matchEnv = Envelope.parseFrom(matchRaw);
            ServerFrame matchSf = ServerFrame.parseFrom(
                    matchEnv.getResponseTournamentMatches().toByteArray());
            MatchesFrame mf = MatchesFrame.parseFrom(matchSf.getBody(0));
            save("betboom_matches_proto.json",
                    JsonFormat.printer().includingDefaultValueFields().print(mf));
            log("betboom", "matches saved for tournamentId=" + firstTournId
                    + ", match count: " + mf.getSection().getMatchesCount());

        } finally {
            pool.stop();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static JsonNode jdkGetJson(String url) throws Exception {
        HttpRequest req = jdkRequest(url).GET().build();
        HttpResponse<byte[]> resp = JDK_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        return MAPPER.readTree(jdkDecompress(resp));
    }

    private static JsonNode jdkPostMultipart(String url, String fieldName, String fieldValue)
            throws Exception {
        String boundary = UUID.randomUUID().toString().replace("-", "");
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n\r\n"
                + fieldValue + "\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest req = jdkRequest(url)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<byte[]> resp = JDK_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        return MAPPER.readTree(jdkDecompress(resp));
    }

    private static HttpRequest.Builder jdkRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Encoding", "gzip, deflate")
                .timeout(Duration.ofSeconds(20));
    }

    private static byte[] jdkDecompress(HttpResponse<byte[]> resp) throws IOException {
        byte[] body = resp.body();
        String enc = resp.headers().firstValue("Content-Encoding").orElse("");
        if ("gzip".equalsIgnoreCase(enc)) {
            try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return in.readAllBytes();
            }
        }
        return body;
    }

    private static BookmakerHttpClient client(int bufferBytes) {
        // Use JVM InetAddress (system DNS) — Netty's async DNS can't resolve RU bookmaker domains.
        // compress(true) — enables automatic GZIP/deflate decompression (responses arrive as 0x1F 0x8B).
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .compress(true);
        WebClient wc = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(bufferBytes))
                .build();
        return new BookmakerHttpClient(wc);
    }

    private static WsPoolProperties betboomProps() {
        WsPoolProperties p = new WsPoolProperties();
        p.setUrl("wss://ru-ws.sporthub.bet:444/api/tree_ws/v1");
        p.setHeaders(Map.of("Origin", "https://betboom.ru"));
        p.setMinSize(1);
        p.setMaxSize(2);
        p.setConnectTimeout(Duration.ofSeconds(10));
        p.setReadyTimeout(Duration.ofSeconds(15));
        WsPoolProperties.Warmup warmup = new WsPoolProperties.Warmup();
        warmup.setEnabled(true);
        warmup.setMinReady(1);
        warmup.setTimeout(Duration.ofSeconds(15));
        p.setWarmup(warmup);
        return p;
    }

    private static ArrayNode takeSample(JsonNode arr, int n) {
        ArrayNode out = MAPPER.createArrayNode();
        int i = 0;
        for (JsonNode item : iter(arr)) {
            out.add(item);
            if (++i >= n) break;
        }
        return out;
    }

    private static JsonNode sample(JsonNode node, int n) {
        if (node.isArray()) return takeSample(node, n);
        if (node.isObject()) return sampleObject(node, n);
        return node;
    }

    private static ObjectNode sampleObject(JsonNode obj, int n) {
        ObjectNode out = MAPPER.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
            var e = it.next();
            out.set(e.getKey(), sample(e.getValue(), n));
        }
        return out;
    }

    private static String betcityFirstChampId(JsonNode champsResponse, String sportId) {
        JsonNode chmps = champsResponse.path("reply").path("sports")
                .path(sportId).path("chmps");
        if (chmps.isObject() && !chmps.isEmpty()) {
            return chmps.fieldNames().next();
        }
        return null;
    }

    private static Iterable<JsonNode> iter(JsonNode node) {
        return node::elements;
    }

    private static void save(String filename, Object data) throws IOException {
        String content = data instanceof String s ? s : MAPPER.writeValueAsString(data);
        Path file = OUT.resolve(filename);
        Files.writeString(file, content);
        System.out.println("[DUMP] Saved: " + file.toAbsolutePath());
    }

    private static void log(String bk, String message) {
        System.out.printf("[DUMP][%s] %s%n", bk.toUpperCase(), message);
    }
}
