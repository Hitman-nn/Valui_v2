package BotValui.testline;

import BotValui.Service.betboom.BetBoomSubscribeBuilder;
import com.google.protobuf.ByteString;
import proto.betboom.Envelope;
import proto.betboom.ServerFrame;
import proto.betboom.SportAllBody;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public class BetboomWsFiveFrames {

    // === настройки ===
    private static final String WS_URI = "wss://ru-ws.sporthub.bet:444/api/tree_ws/v1";

    // ВСЕ нужные подписки (верни закомментированные, если их ждёшь!)
    private static final List<String> SUBSCRIBE_FRAMES_B64 = List.of(
            "QhgKBjViYTY5ORIOCgZjM2VhNGIQAhiQwAE="
            //"MhYKBmM4MTFkZBIMCgZlNzk1ZDAQAhgE"
            //"QhcKBmU3OTVkMBINCgZjODExZGQQAhjuAg=="
    );

    // Ждём столько крупных снапшотов, сколько подписок отправили (но не более 5)
    private static final int MAX_RECV_BIN_FRAMES = 5;
    private static final int SNAPSHOT_MIN_BYTES = 5000; // порог «крупного» кадра (под нужный проект подвинь)
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration EXTRA_WAIT = Duration.ofSeconds(2); // добор хвоста после получения всех

    private static final Path OUT_DIR = Paths.get("ws_5frames_dump");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT_DIR);
        log("=== BetboomWsFiveFrames start " + now() + " ===");

        HttpClient client = HttpClient.newHttpClient();

        int expectedSnapshots = Math.min(SUBSCRIBE_FRAMES_B64.size(), MAX_RECV_BIN_FRAMES);
        CountDownLatch bigFramesLatch = new CountDownLatch(expectedSnapshots);

        DebugListener listener = new DebugListener(bigFramesLatch);

        WebSocket ws = client.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .header("Origin", "https://betboom.ru")
                .buildAsync(URI.create(WS_URI), listener)
                .get(1, TimeUnit.SECONDS);

        log("🟢 WS connected");

        // отправляем все подписки
        for (String b64 : SUBSCRIBE_FRAMES_B64) {
            //System.out.println(BetBoomSubscribeBuilder.sportAllBytes(proto.betboom.Current.TypeLine.LINE, 1));
            byte[] bytes = BetBoomSubscribeBuilder.tournamentMatchesBytes(proto.betboom.Current.TypeLine.LINE, 459);
            //byte[] bytes = Base64.getDecoder().decode(b64);
            ws.sendBinary(ByteBuffer.wrap(bytes), true).join();
            log("➡️  SENT subscribe (" + bytes.length + " bytes)");
        }

        log("⏳ Waiting for " + expectedSnapshots + " big snapshot frame(s) (≥ " + SNAPSHOT_MIN_BYTES + " bytes)...");
        boolean allCame = bigFramesLatch.await(25, TimeUnit.SECONDS);
        log(allCame ? "✅ Got all expected snapshots." : "⚠️ Timeout before all snapshots arrived.");

        // чуть подождём хвостик
        Thread.sleep(EXTRA_WAIT.toMillis());

        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } catch (Exception ignore) {
        }
        log("=== BetboomWsFiveFrames done " + now() + " ===");
    }

    // === listener ===
    private static class DebugListener implements WebSocket.Listener {
        private final Map<WebSocket, ByteArrayOutputStreamEx> buf = new ConcurrentHashMap<>();
        private final AtomicInteger recvBinCount = new AtomicInteger(0);
        private final AtomicInteger bigCount = new AtomicInteger(0);
        private final CountDownLatch bigFramesLatch;

        DebugListener(CountDownLatch latch) {
            this.bigFramesLatch = latch;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log("onOpen()");
            webSocket.request(1);
        }

        private static void printSports(SportAllBody sb) {
            log("===== Виды спорта =====");
            if (sb.getRowsCount() == 0) {
                log("Нет данных (rowsCount=0)");
                return;
            }
            System.out.printf("%-3s %-20s %-10s %-8s %-10s%n", "ID", "Название", "Alias", "Порядок", "Матчей");
            for (var row : sb.getRowsList()) {
                var s = row.getSport();
                System.out.printf("%-3d %-20s %-10s %-8d %-10d%n",
                        s.getId(), s.getName(), s.getAlias(), s.getOrder(), s.getCount());
            }
            log("========================");
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            ByteArrayOutputStreamEx out = buf.computeIfAbsent(webSocket, k -> new ByteArrayOutputStreamEx(64 * 1024));
            out.write(data);
            if (last) {
                byte[] frame = out.toByteArray();
                out.reset();

                int idx = recvBinCount.incrementAndGet();
                boolean isBig = frame.length >= SNAPSHOT_MIN_BYTES;
                log("⬅️  BINARY #" + idx + " size=" + frame.length + " " + (isBig ? "[SNAPSHOT]" : "") +
                        " HEX(0..16)=" + hexSnippet(frame, 16));
                if (isBig) {

                    try {
                        // 1️⃣ Парсим Envelope (верхний конверт)
                        Envelope env = Envelope.parseFrom(frame);

                        // 2️⃣ Достаём payload (в твоём случае responseSportAll)
                        byte[] payload = env.getResponseSportAll().toByteArray();
                        log("ENV payload len=" + payload.length + " HEX(0..16)=" + hexSnippet(payload, 16));

                        // 3️⃣ Парсим ServerFrame
                        ServerFrame sf = ServerFrame.parseFrom(payload);
                        log(String.format("ServerFrame: status=%d sub_status=%d parent=%s bodyCount=%d",
                                sf.getStatus(), sf.getSubStatus(), sf.getParentNode(), sf.getBodyCount()));

                        // 4️⃣ Берём первый непустой body
                        byte[] body = null;
                        for (ByteString bs : sf.getBodyList()) {
                            if (bs != null && bs.size() > 0) {
                                body = bs.toByteArray();
                                break;
                            }
                        }

                        if (body == null) {
                            log("⚠️  no non-empty body in ServerFrame; dumping pretty...");
                            Files.writeString(OUT_DIR.resolve(String.format("recv_%02d_body_pretty.txt", idx)),
                                    ProtoRaw.prettyDecode(payload, 0, payload.length, 0));
                        }

                        log("Picked body.len=" + body.length + " HEX(0..16)=" + hexSnippet(body, 16));

                        // 5️⃣ Парсим тело как список видов спорта
                        SportAllBody sb = SportAllBody.parseFrom(body);
                        printSports(sb);

                    } catch (Exception e) {
                        log("❌ Error parsing SportAllBody: " + e);
                        e.printStackTrace();
                    }
                }


                // сохраняем .bin
                Path bin = OUT_DIR.resolve(String.format("recv_%02d.bin", idx));
                try {
                    Files.write(bin, frame, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log("⚠️ write bin error: " + e);
                }

                // pretty dump без .proto
                //String pretty = ProtoRaw.prettyDecode(frame, 0, frame.length, 0);
                String pretty = ProtoInspectorTB.prettyDump(frame);

                Path txt = OUT_DIR.resolve(String.format("recv_%02d_pretty.txt", idx));
                try {
                    Files.writeString(txt, pretty, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log("⚠️ write txt error: " + e);
                }

                if (isBig) {
                    int n = bigCount.incrementAndGet();
                    log("🧩 snapshot counter: " + n);
                    bigFramesLatch.countDown();
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (last) {
                String s = data.toString();
                log("⬅️  TEXT len=" + s.length() + " preview=" + (s.length() > 160 ? s.substring(0, 160) + "…" : s));
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log("onClose " + statusCode + " reason=" + reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log("onError: " + error);
        }
    }

    // === утилиты ===

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static void log(String s) {
        System.out.println("[" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) + "] " + s);
    }

    private static String hexSnippet(byte[] a, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(a.length, max); i++) {
            sb.append(String.format("%02x ", a[i] & 0xFF));
        }
        if (a.length > max) sb.append("…");
        return sb.toString();
    }

    private static class ByteArrayOutputStreamEx {
        private byte[] buf;
        private int count;

        ByteArrayOutputStreamEx(int size) {
            buf = new byte[size];
        }

        void write(ByteBuffer bb) {
            byte[] tmp = new byte[bb.remaining()];
            bb.get(tmp);
            write(tmp);
        }

        void write(byte[] b) {
            ensureCapacity(count + b.length);
            System.arraycopy(b, 0, buf, count, b.length);
            count += b.length;
        }

        void ensureCapacity(int min) {
            if (min > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, min));
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, count);
        }

        void reset() {
            count = 0;
        }
    }

    // ——— «сырой» pretty decode protobuf, без .proto ———
    static final class ProtoRaw {
        private static final int MAX_DEPTH = 8;

        static String prettyDecode(byte[] data, int off, int len, int depth) {
            StringBuilder sb = new StringBuilder();
            decodeMessage(sb, data, off, len, depth);
            return sb.toString();
        }

        private static void decodeMessage(StringBuilder out, byte[] data, int off, int len, int depth) {
            int i = off;
            String indent = "  ".repeat(Math.max(0, depth));
            while (i < off + len) {
                long key;
                int keyPos = i;
                try {
                    Varint vi = readVarint(data, i);
                    key = vi.value;
                    i = vi.next;
                } catch (IndexOutOfBoundsException e) {
                    out.append(indent).append("(truncated key at ").append(keyPos - off).append(")\n");
                    return;
                } catch (IllegalStateException e) {
                    out.append(indent).append("(not a protobuf message)\n");
                    return;
                }

                int field = (int) (key >>> 3);
                int wire = (int) (key & 7);
                out.append(indent).append(field).append(" ");

                try {
                    switch (wire) {
                        case 0 -> {
                            Varint v = readVarint(data, i);
                            i = v.next;
                            out.append(": ").append(v.value).append(" (varint)\n");
                        }
                        case 1 -> {
                            if (i + 8 > off + len) throw new IndexOutOfBoundsException();
                            long val = ByteBuffer.wrap(data, i, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
                            out.append(": ").append(val).append(" (fixed64)\n");
                            i += 8;
                        }
                        case 2 -> {
                            Varint lvi = readVarint(data, i);
                            int L = (int) lvi.value;
                            i = lvi.next;
                            if (L < 0 || i + L > off + len) throw new IndexOutOfBoundsException();
                            byte[] sub = Arrays.copyOfRange(data, i, i + L);

                            if (looksUtf8(sub)) {
                                String s = new String(sub, StandardCharsets.UTF_8);
                                out.append(": \"").append(escape(s)).append("\" (len=").append(L).append(")\n");
                            } else {
                                out.append(": (bytes len=").append(L).append(")\n");
                            }

                            if (depth < MAX_DEPTH && looksLikeEmbeddedMessage(sub)) {
                                out.append(indent).append("{\n");
                                decodeMessage(out, sub, 0, sub.length, depth + 1);
                                out.append(indent).append("}\n");
                            }
                            i += L;
                        }
                        case 5 -> {
                            if (i + 4 > off + len) throw new IndexOutOfBoundsException();
                            int val = ByteBuffer.wrap(data, i, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                            out.append(": ").append(val).append(" (fixed32)\n");
                            i += 4;
                        }
                        default -> {
                            out.append(": (unknown wiretype ").append(wire).append(")\n");
                            return;
                        }
                    }
                } catch (IndexOutOfBoundsException ex) {
                    out.append(indent).append("(truncated value at ").append(i - off).append(")\n");
                    return;
                }
            }
        }

        private static Varint readVarint(byte[] a, int i) {
            long x = 0;
            int shift = 0;
            int pos = i;
            int limit = Math.min(a.length, i + 10);
            while (pos < limit) {
                int b = a[pos++] & 0xFF;
                x |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return new Varint(x, pos);
                shift += 7;
            }
            throw new IllegalStateException("not a protobuf varint at " + i);
        }

        static final class Varint {
            final long value;
            final int next;

            Varint(long v, int n) {
                this.value = v;
                this.next = n;
            }
        }

        private static boolean looksUtf8(byte[] sub) {
            try {
                CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder();
                CoderResult r = dec.decode(ByteBuffer.wrap(sub), java.nio.CharBuffer.allocate(Math.max(1, sub.length * 2)), true);
                return !r.isError();
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean looksLikeEmbeddedMessage(byte[] sub) {
            try {
                int i = 0, hits = 0, limit = Math.min(sub.length, 64);
                while (i < sub.length && i < limit) {
                    Varint vi = readVarint(sub, i);
                    i = vi.next;
                    int field = (int) (vi.value >>> 3);
                    int wire = (int) (vi.value & 7);
                    if (field <= 0 || wire > 5) return false;
                    hits++;
                    switch (wire) {
                        case 0 -> {
                            Varint v2 = readVarint(sub, i);
                            i = v2.next;
                        }
                        case 1 -> {
                            i += 8;
                        }
                        case 2 -> {
                            Varint lv = readVarint(sub, i);
                            i = (int) (lv.next + lv.value);
                        }
                        case 5 -> {
                            i += 4;
                        }
                        default -> {
                            return false;
                        }
                    }
                    if (hits >= 2) return true;
                }
                return hits >= 1;
            } catch (Exception e) {
                return false;
            }
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }
    }

    private static void printSports(SportAllBody all) {
//        System.out.printf("📦 SportAllBody: list_type=%d, rows=%d%n", all.getListType(), all.getRowsCount());
//        System.out.println(String.format("%-18s | %-12s | %8s | %-10s | %s",
//                "Name", "Alias", "Events", "Category", "Groups(codes...)"));
//        System.out.println("-----------------------------------------------------------------------------------------");
//        Map<String,Integer> groupCounts = new LinkedHashMap<>();
//        Map<String,Integer> codeFreq = new LinkedHashMap<>();
//        for (SportAllBody.Row row : all.getRowsList()) {
//            var s = row.getSport();
//            List<String> gsum = new ArrayList<>();
//            for (SportAllBody.Group g : s.getGroupsList()) {
//                groupCounts.merge(g.getName(), 1, Integer::sum);
//                for (String code : g.getCodesList()) codeFreq.merge(code, 1, Integer::sum);
//                String shortCodes = String.join(",", g.getCodesList().size() > 8
//                        ? g.getCodesList().subList(0, 8)
//                        : g.getCodesList());
//                if (g.getCodesList().size() > 8) shortCodes += ",…";
//                gsum.add(g.getName() + "(" + shortCodes + ")");
//            }
//            System.out.println(String.format("%-18s | %-12s | %8d | %-10d | %s",
//                    s.getName(), s.getAlias(), s.getCount(), s.getCategory(), String.join("; ", gsum)));
//        }
//        System.out.println("=== GROUP STATS ===");
//        groupCounts.forEach((g,c)-> System.out.println(String.format("%-15s : %d", g, c)));
//        System.out.println("=== TOP CODES (first 20) ===");
//        codeFreq.entrySet().stream().sorted((a,b)->Integer.compare(b.getValue(), a.getValue()))
//                .limit(20).forEach(e-> System.out.println(String.format("%-6s : %d", e.getKey(), e.getValue())));
    }

}
