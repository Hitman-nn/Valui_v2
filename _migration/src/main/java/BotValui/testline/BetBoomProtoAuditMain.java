package BotValui.testline;

import BotValui.Service.betboom.BetBoomSportsMap;
import BotValui.Service.betboom.BetBoomSubscribeBuilder;
import com.google.protobuf.ByteString;
import proto.betboom.Current;
import proto.betboom.Envelope;
import proto.betboom.MatchesFrame;
import proto.betboom.ServerFrame;
import proto.betboom.SportAllBody;
import proto.betboom.TournamentListFrame;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;

public class BetBoomProtoAuditMain {

    private static final String WS_URL = "wss://ru-ws.sporthub.bet:444/api/tree_ws/v1";
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long AWAIT_TIMEOUT_MS = 10_000;

    public static void main(String[] args) throws Exception {
        auditAllSports();

        Integer tennisSportId = BetBoomSportsMap.getSportId("tennis").orElse(4);
        auditTournamentsForSport(tennisSportId);

        auditMatchesForTournament(17776);
        auditMatchesForTournament(5623);
    }

    // ========================= 1) ALL SPORTS =========================

    private static void auditAllSports() throws Exception {
        System.out.println("\n==================== ALL SPORTS ====================");

        byte[] resp = sendAndAwait(
                BetBoomSubscribeBuilder.sportAllBytes(Current.TypeLine.LINE, 1),
                Envelope::hasResponseSportAll
        );

        if (resp == null) {
            System.out.println("No responseSportAll");
            return;
        }

        Envelope env = Envelope.parseFrom(resp);
        ServerFrame sf = ServerFrame.parseFrom(env.getResponseSportAll().toByteArray());
        byte[] body = firstNonEmptyBody(sf);
        SportAllBody all = SportAllBody.parseFrom(body);

        System.out.println("SportAllBody.listType = " + all.getListType());
        System.out.println("rows = " + all.getRowsCount());

        for (int i = 0; i < Math.min(5, all.getRowsCount()); i++) {
            SportAllBody.Row row = all.getRows(i);
            var s = row.getSport();

            System.out.println("\nSport row[" + i + "]");
            System.out.println("  id         = " + s.getId());
            System.out.println("  name       = " + s.getName());
            System.out.println("  order      = " + s.getOrder());
            System.out.println("  count      = " + s.getCount());
            System.out.println("  updatedAt  = " + s.getUpdatedAt());
            System.out.println("  category   = " + s.getCategory());
            System.out.println("  alias      = " + s.getAlias());

            if (s.hasAssets()) {
                System.out.println("  assets.svg       = " + s.getAssets().getIconSvg());
                System.out.println("  assets.pdf       = " + s.getAssets().getIconPdf());
                System.out.println("  assets.fillImage = " + s.getAssets().getFillImage());
                System.out.println("  assets.image     = " + s.getAssets().getImage());
            }

            System.out.println("  groupsCount = " + s.getGroupsCount());
            for (int g = 0; g < Math.min(3, s.getGroupsCount()); g++) {
                var group = s.getGroups(g);
                System.out.println("    group[" + g + "] name=" + group.getName() + ", codes=" + group.getCodesList());
            }

            System.out.println("  RAW row:");
            ProtoWireDump.dump("sportAll.row[" + i + "]", row.toByteArray());

            System.out.println("  RAW sport:");
            ProtoWireDump.dump("sportAll.row[" + i + "].sport", s.toByteArray());
        }
    }

    // ========================= 2) TOURNAMENTS =========================

    private static void auditTournamentsForSport(int sportId) throws Exception {
        System.out.println("\n==================== TOURNAMENTS sportId=" + sportId + " ====================");

        byte[] resp = sendAndAwait(
                BetBoomSubscribeBuilder.sportTournamentsBytes(Current.TypeLine.LINE, sportId),
                Envelope::hasResponseSportTournaments
        );

        if (resp == null) {
            System.out.println("No responseSportTournaments");
            return;
        }

        Envelope env = Envelope.parseFrom(resp);
        ServerFrame sf = ServerFrame.parseFrom(env.getResponseSportTournaments().toByteArray());
        byte[] body = firstNonEmptyBody(sf);
        TournamentListFrame frame = TournamentListFrame.parseFrom(body);

        System.out.println("TournamentListFrame.type       = " + frame.getType());
        System.out.println("TournamentListFrame.subType    = " + frame.getSubType());
        System.out.println("TournamentListFrame.parentNode = " + frame.getParentNode());
        System.out.println("TournamentListFrame.listType   = " + frame.getListType());
        System.out.println("entries = " + frame.getList().getEntriesCount());

        if (frame.getList().hasSport() && frame.getList().getSport().hasSport()) {
            var s = frame.getList().getSport().getSport();
            System.out.println("List.sport.id    = " + s.getId());
            System.out.println("List.sport.name  = " + s.getName());
            System.out.println("List.sport.alias = " + s.getAlias());
            System.out.println("RAW list.sport:");
            ProtoWireDump.dump("tournaments.list.sport", s.toByteArray());
        }

        for (int i = 0; i < Math.min(5, frame.getList().getEntriesCount()); i++) {
            var entry = frame.getList().getEntries(i);
            var t = entry.getTournament();

            System.out.println("\nEntry[" + i + "] tournament");
            System.out.println("  id         = " + t.getId());
            System.out.println("  sportId    = " + t.getSportId());
            System.out.println("  countryId  = " + t.getCountryId());
            System.out.println("  title      = " + t.getTitle());
            System.out.println("  events     = " + t.getEvents());
            System.out.println("  level      = " + t.getLevel());
            System.out.println("  active     = " + t.getActive());
            System.out.println("  shortName  = " + t.getShortName());

            System.out.println("RAW tournament:");
            ProtoWireDump.dump("tournaments.entry[" + i + "].tournament", t.toByteArray());

            System.out.println("\nEntry[" + i + "] category wrapper");
            ProtoWireDump.dump("tournaments.entry[" + i + "].categoryHeader", entry.getCategory().toByteArray());

            if (entry.hasCategory() && entry.getCategory().hasCategory()) {
                var c = entry.getCategory().getCategory();

                System.out.println("Entry[" + i + "] category");
                System.out.println("  id         = " + c.getId());
                System.out.println("  sportId    = " + c.getSportId());
                System.out.println("  nameShort  = " + c.getNameShort());
                System.out.println("  tier       = " + c.getTier());
                System.out.println("  updatedAt  = " + c.getUpdatedAt());
                System.out.println("  regionId   = " + c.getRegionId());
                System.out.println("  alias      = " + c.getAlias());

                if (c.hasAssets()) {
                    System.out.println("  assets.svg       = " + c.getAssets().getIconSvg());
                    System.out.println("  assets.pdf       = " + c.getAssets().getIconPdf());
                    System.out.println("  assets.fillImage = " + c.getAssets().getFillImage());
                    System.out.println("  assets.image     = " + c.getAssets().getImage());
                }

                System.out.println("  groupsCount = " + c.getGroupsCount());
                for (int g = 0; g < Math.min(3, c.getGroupsCount()); g++) {
                    var group = c.getGroups(g);
                    System.out.println("    group[" + g + "] name=" + group.getName() + ", codes=" + group.getCodesList());
                }

                System.out.println("RAW category:");
                ProtoWireDump.dump("tournaments.entry[" + i + "].category", c.toByteArray());
            } else {
                System.out.println("  category = <absent>");
            }
        }
    }

    // ========================= 3) MATCHES =========================

    private static void auditMatchesForTournament(int champId) throws Exception {
        System.out.println("\n==================== MATCHES champId=" + champId + " ====================");

        byte[] resp = sendAndAwait(
                BetBoomSubscribeBuilder.tournamentMatchesBytes(Current.TypeLine.LINE, champId),
                Envelope::hasResponseTournamentMatches
        );

        if (resp == null) {
            System.out.println("No responseTournamentMatches");
            return;
        }

        Envelope env = Envelope.parseFrom(resp);
        ServerFrame sf = ServerFrame.parseFrom(env.getResponseTournamentMatches().toByteArray());
        byte[] body = firstNonEmptyBody(sf);
        MatchesFrame frame = MatchesFrame.parseFrom(body);

        System.out.println("MatchesFrame.type       = " + frame.getType());
        System.out.println("MatchesFrame.subType    = " + frame.getSubType());
        System.out.println("MatchesFrame.parentNode = " + frame.getParentNode());

        if (frame.hasSport() && frame.getSport().hasSport()) {
            var s = frame.getSport().getSport();
            System.out.println("\nFrame.sport");
            System.out.println("  id        = " + s.getId());
            System.out.println("  name      = " + s.getName());
            System.out.println("  alias     = " + s.getAlias());
            ProtoWireDump.dump("matches.frame.sport", s.toByteArray());
        }

        if (frame.hasCountry() && frame.getCountry().hasCountry()) {
            var c = frame.getCountry().getCountry();
            System.out.println("\nFrame.country");
            System.out.println("  id        = " + c.getId());
            System.out.println("  sportId   = " + c.getSportId());
            System.out.println("  name      = " + c.getName());
            System.out.println("  regionId  = " + c.getRegionId());
            System.out.println("  alias     = " + c.getAlias());
            ProtoWireDump.dump("matches.frame.country", c.toByteArray());
        }

        if (frame.hasSection() && frame.getSection().hasTournament()) {
            var t = frame.getSection().getTournament();
            System.out.println("\nSection.tournament");
            System.out.println("  id        = " + t.getId());
            System.out.println("  sportId   = " + t.getSportId());
            System.out.println("  countryId = " + t.getCountryId());
            System.out.println("  title     = " + t.getTitle());
            System.out.println("  rounds    = " + t.getRounds());
            System.out.println("  level     = " + t.getLevel());
            System.out.println("  image     = " + t.getImage());
            System.out.println("  active    = " + t.getActive());
            System.out.println("  shortName = " + t.getShortName());
            ProtoWireDump.dump("matches.section.tournament", t.toByteArray());
        }

        System.out.println("\nMatches count = " + frame.getSection().getMatchesCount());

        for (int i = 0; i < Math.min(3, frame.getSection().getMatchesCount()); i++) {
            var match = frame.getSection().getMatches(i);

            System.out.println("\n--------------- MATCH[" + i + "] ---------------");

            if (match.hasHeader()) {
                var h = match.getHeader();

                System.out.println("Header parsed:");
                System.out.println("  id             = " + h.getId());
                System.out.println("  lineGroup      = " + h.getLineGroup());
                System.out.println("  unknown3       = " + h.getUnknown3());
                System.out.println("  unknown4       = " + h.getUnknown4());
                System.out.println("  srMatchId      = " + h.getSrMatchId());
                System.out.println("  statusId       = " + h.getStatusId());
                System.out.println("  sportId        = " + h.getSportId());
                System.out.println("  countryId      = " + h.getCountryId());
                System.out.println("  tournamentId   = " + h.getTournamentId());
                System.out.println("  startsAt       = " + h.getStartsAt());
                System.out.println("  flag14         = " + h.getFlag14());
                System.out.println("  flag15         = " + h.getFlag15());
                System.out.println("  h2hText        = " + h.getH2HText());
                System.out.println("  live           = " + h.getLive());
                System.out.println("  unknown23      = " + h.getUnknown23());
                System.out.println("  sortOrder      = " + h.getSortOrder());
                System.out.println("  unknown26      = " + h.getUnknown26());
                System.out.println("  unknown28      = " + h.getUnknown28());

                if (h.hasStatus()) {
                    System.out.println("  status.text    = " + h.getStatus().getText());
                    ProtoWireDump.dump("matches.match[" + i + "].status", h.getStatus().toByteArray());
                }

                if (h.hasExtra20()) {
                    System.out.println("  extra20.f1     = " + h.getExtra20().getF1());
                    System.out.println("  extra20.f2     = " + h.getExtra20().getF2());
                    System.out.println("  extra20.f3     = " + h.getExtra20().getF3());
                    System.out.println("  extra20.f4     = " + h.getExtra20().getF4());
                    System.out.println("  extra20.f5     = " + h.getExtra20().getF5());
                    System.out.println("  extra20.f6     = " + h.getExtra20().getF6());
                    System.out.println("  extra20.f7     = " + h.getExtra20().getF7());
                    if (h.getExtra20().hasF8()) {
                        System.out.println("  extra20.f8.a   = " + h.getExtra20().getF8().getA());
                        System.out.println("  extra20.f8.b   = " + h.getExtra20().getF8().getB());
                    }
                    ProtoWireDump.dump("matches.match[" + i + "].extra20", h.getExtra20().toByteArray());
                }

                System.out.println("  unknownFields  = " + h.getUnknownFields().asMap().keySet());

                ProtoWireDump.dump("matches.match[" + i + "].header", h.toByteArray());

                if (h.hasTeams()) {
                    var teams = h.getTeams();
                    ProtoWireDump.dump("matches.match[" + i + "].teams", teams.toByteArray());

                    if (teams.hasHome()) {
                        var home = teams.getHome();
                        System.out.println("\nHOME TEAM");
                        System.out.println("  id         = " + home.getId());
                        System.out.println("  srId       = " + home.getSrId());
                        System.out.println("  name       = " + home.getName());
                        System.out.println("  nameShort  = " + home.getNameShort());
                        System.out.println("  rankText   = " + home.getRankText());
                        System.out.println("  unknown6   = " + home.getUnknown6());
                        System.out.println("  logoWebp   = " + home.getLogoWebp());
                        System.out.println("  colorHex   = " + home.getColorHex());
                        ProtoWireDump.dump("matches.match[" + i + "].teams.home", home.toByteArray());
                    }

                    if (teams.hasAway()) {
                        var away = teams.getAway();
                        System.out.println("\nAWAY TEAM");
                        System.out.println("  id         = " + away.getId());
                        System.out.println("  srId       = " + away.getSrId());
                        System.out.println("  name       = " + away.getName());
                        System.out.println("  nameShort  = " + away.getNameShort());
                        System.out.println("  rankText   = " + away.getRankText());
                        System.out.println("  unknown6   = " + away.getUnknown6());
                        System.out.println("  logoWebp   = " + away.getLogoWebp());
                        System.out.println("  colorHex   = " + away.getColorHex());
                        ProtoWireDump.dump("matches.match[" + i + "].teams.away", away.toByteArray());
                    }
                }
            }

            System.out.println("\nMarkets count = " + match.getMarketsCount());
            for (int j = 0; j < Math.min(3, match.getMarketsCount()); j++) {
                var m = match.getMarkets(j);

                System.out.println("  Market[" + j + "]");
                System.out.println("    key         = " + m.getKey());
                System.out.println("    matchId     = " + m.getMatchId());
                System.out.println("    scope       = " + m.getScope());
                System.out.println("    titleFull   = " + m.getTitleFull());
                System.out.println("    titleShort  = " + m.getTitleShort());
                System.out.println("    outcomeId   = " + m.getOutcomeId());
                System.out.println("    outcomeNo   = " + m.getOutcomeNo());
                System.out.println("    oddsRaw1    = " + m.getOddsRaw1());
                System.out.println("    oddsRaw2    = " + m.getOddsRaw2());
                System.out.println("    flag11      = " + m.getFlag11());
                System.out.println("    flag12      = " + m.getFlag12());
                System.out.println("    viewId      = " + m.getViewId());
                System.out.println("    marketName  = " + m.getMarketName());
                System.out.println("    groupId     = " + m.getGroupId());
                System.out.println("    viewKey     = " + m.getViewKey());
                System.out.println("    period      = " + m.getPeriod());
                System.out.println("    groupName   = " + m.getGroupName());
                System.out.println("    sort        = " + m.getSort());
                System.out.println("    timeScope   = " + m.getTimeScope());
                System.out.println("    flag21      = " + m.getFlag21());
                System.out.println("    cacheKey    = " + m.getCacheKey());
                System.out.println("    label       = " + m.getLabel());

                ProtoWireDump.dump("matches.match[" + i + "].market[" + j + "]", m.toByteArray());
            }
        }
    }

    // ========================= TRANSPORT =========================

    private static byte[] sendAndAwait(byte[] request,
                                       java.util.function.Predicate<Envelope> predicate) throws Exception {
        SimpleWsClient client = new SimpleWsClient(WS_URL);
        try {
            client.connect();
            client.sendBinary(request);

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS);
            while (System.nanoTime() < deadline) {
                long leftNs = deadline - System.nanoTime();
                long leftMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(leftNs));

                byte[] bin = client.awaitBinary(leftMs);
                if (bin == null) {
                    continue;
                }

                try {
                    Envelope env = Envelope.parseFrom(bin);
                    System.out.println("recv envelope kind=" + env.getKindCase() + ", size=" + bin.length);
                    if (predicate.test(env)) {
                        return bin;
                    }
                } catch (Exception e) {
                    System.out.println("skip non-envelope frame, size=" + bin.length + ", err=" + e.getMessage());
                }
            }

            return null;
        } finally {
            client.close();
        }
    }

    private static byte[] firstNonEmptyBody(ServerFrame sf) {
        return sf.getBodyList().stream()
                .filter(bs -> bs != null && !bs.isEmpty())
                .findFirst()
                .map(ByteString::toByteArray)
                .orElse(null);
    }

    // ========================= SIMPLE WS CLIENT =========================

    private static final class SimpleWsClient implements WebSocket.Listener {
        private final String url;
        private final BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
        private final CountDownLatch openLatch = new CountDownLatch(1);
        private final ByteArrayOutputStream currentBinary = new ByteArrayOutputStream();

        private volatile WebSocket webSocket;

        private SimpleWsClient(String url) {
            this.url = url;
        }

        void connect() throws InterruptedException {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                    .build();

            this.webSocket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                    .buildAsync(URI.create(url), this)
                    .join();

            if (!openLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("WS open timeout");
            }
        }

        void sendBinary(byte[] data) {
            webSocket.sendBinary(ByteBuffer.wrap(data), true).join();
        }

        byte[] awaitBinary(long timeout) throws InterruptedException {
            return inbox.poll(timeout, TimeUnit.MILLISECONDS);
        }

        void close() {
            try {
                if (webSocket != null) {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            openLatch.countDown();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            try {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                currentBinary.write(chunk);

                if (last) {
                    inbox.offer(currentBinary.toByteArray());
                    currentBinary.reset();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                webSocket.request(1);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            error.printStackTrace();
        }
    }
}