package BotValui.testline;

import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.devtools.v139.network.Network;
import org.openqa.selenium.devtools.v139.network.model.Headers;
import org.openqa.selenium.devtools.v139.network.model.RequestId;
import org.openqa.selenium.devtools.v139.page.Page;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BetboomSnifferV139 {

    private static final Set<String> JSON_CT = Set.of(
            "application/json", "application/ld+json", "application/problem+json"
    );

    private static final AtomicInteger RECV_SEQ = new AtomicInteger(0);
    private static final ConcurrentHashMap<RequestId, String> WS_URL_BY_ID = new ConcurrentHashMap<>();

    private static BufferedWriter COMBINED_LOG;
    private static BufferedWriter COMBINED_B64;
    private static OutputStream COMBINED_BIN;
    private static OutputStream COMBINED_BIN_NO_SPOT;
    private static final Object LOCK = new Object();

    public static void main(String[] args) throws Exception {
        System.out.println("🚀 [" + LocalDateTime.now() + "] Запуск сниффера BetBoom (SEND+RECV)...");
        System.out.println("==========================================");

        String startUrl = "https://betboom.ru/sport/tennis/375/24592?period=all";
        initCombinedSinks();

        ChromeOptions opts = new ChromeOptions();
        opts.addArguments(
                "--headless=new",
                "--disable-gpu",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--lang=ru-RU",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                "--blink-settings=imagesEnabled=false"
        );

        ChromeDriver driver = new ChromeDriver(opts);
        DevTools devTools = ((HasDevTools) driver).getDevTools();
        devTools.createSession();

        devTools.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        devTools.send(Network.setCacheDisabled(true));
        devTools.send(Page.enable(Optional.empty()));

        // === HTTP JSON ===
        var httpUrlById = new ConcurrentHashMap<RequestId, String>();
        var respHeadersById = new ConcurrentHashMap<RequestId, Headers>();

        devTools.addListener(Network.requestWillBeSent(), e -> httpUrlById.put(e.getRequestId(), e.getRequest().getUrl()));
        devTools.addListener(Network.responseReceived(), e -> respHeadersById.put(e.getRequestId(), e.getResponse().getHeaders()));
        devTools.addListener(Network.loadingFinished(), e -> {
            var id = e.getRequestId();
            var url = httpUrlById.get(id);
            if (url == null) return;
            try {
                var body = devTools.send(Network.getResponseBody(id));
                String text = body.getBase64Encoded()
                        ? new String(Base64.getDecoder().decode(body.getBody()), StandardCharsets.UTF_8)
                        : body.getBody();
                String ct = "";
                var h = respHeadersById.get(id);
                if (h != null) {
                    var map = h.toJson();
                    var val = map.get("content-type");
                    if (val != null) ct = val.toString().toLowerCase();
                }
                if (JSON_CT.stream().anyMatch(ct::contains) || looksLikeJson(text)) {
                    logLine("\n================ JSON RESPONSE ================\r\n" +
                            "URL: " + url + "\r\n" +
                            "Content-Type: " + ct + "\r\n" +
                            "Preview:\r\n" + snip(text, 2000) + "\r\n" +
                            "================================================\r\n");
                }
            } catch (Exception ignore) {
            } finally {
                httpUrlById.remove(id);
                respHeadersById.remove(id);
            }
        });

        // === WebSocket CREATED ===
        devTools.addListener(Network.webSocketCreated(), e -> {
            WS_URL_BY_ID.put(e.getRequestId(), e.getUrl());
            logLine("🔌 WebSocket: " + e.getUrl());
        });

        // === WebSocket SENT ===
        devTools.addListener(Network.webSocketFrameSent(), e -> {
            var frame = e.getResponse();
            String payload = frame.getPayloadData();
            Number opcodeNum = frame.getOpcode();
            int opcode = (opcodeNum == null) ? -1 : opcodeNum.intValue();
            String url = WS_URL_BY_ID.get(e.getRequestId());

            if (payload == null || payload.isBlank()) return;
            if (opcode == 1) {
                logLine("\n➡️  WS SENT (text) [" + url + "]:\n" + snip(payload, 4000));
                saveSent(url, opcode, payload);
            } else if (opcode == 2) {
                logLine("\n➡️  WS SENT (b64) [" + url + "]:\n" + wrapB64(payload));
                saveSent(url, opcode, payload);
            }
        });

        // === WebSocket RECEIVED ===
        devTools.addListener(Network.webSocketFrameReceived(), e -> {
            var frame = e.getResponse();
            String payload = frame.getPayloadData();
            Number opcodeNum = frame.getOpcode();
            Integer opcode = (opcodeNum == null) ? null : opcodeNum.intValue();
            if (payload == null || payload.isBlank()) return;
            String url = WS_URL_BY_ID.get(e.getRequestId());
            int n = RECV_SEQ.incrementAndGet();
            processRecvFrame(n, url, opcode, payload);
        });

        logLine("🌐 Загружаем страницу: " + startUrl);
        driver.get(startUrl);
        logLine("⏳ Ожидание сетевых запросов...");

        for (int i = 0; i < 6; i++) {
            Thread.sleep(4000);
            driver.executeScript("window.scrollBy(0, document.body.scrollHeight/2)");
        }
        Thread.sleep(6000);
        driver.quit();

        logLine("==========================================");
        logLine("✅ [" + LocalDateTime.now() + "] Сниффер завершил работу (SEND+RECV).");

        closeCombinedSinks();
    }

    // === SAVE SENT ===
    private static void saveSent(String url, int opcode, String payload) {
        try {
            Path dir = Paths.get("ws_dump", "sent");
            Files.createDirectories(dir);
            String safe = (url == null ? "ws" : url.replaceAll("[^A-Za-z0-9._-]", "_"));
            String kind = (opcode == 1) ? "text" : "b64";
            Path out = dir.resolve(String.format("sent_%s_%d.%s.txt", safe, System.currentTimeMillis(), kind));
            try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                w.write('\uFEFF');
                w.write("Time: " + LocalDateTime.now() + "\r\n");
                w.write("URL : " + (url == null ? "-" : url) + "\r\n");
                w.write("OP  : " + opcode + "\r\n");
                w.write("PAYLOAD:\r\n");
                w.write(payload.replace("\n", "\r\n"));
            }
        } catch (Exception ignore) {}
    }

    // === RECEIVE ===
    private static void processRecvFrame(int seq, String url, Integer opcode, String payloadB64) {
        try {
            if (opcode != null && opcode == 1) {
                logLine("\n⬅️  WS RECV (text) #" + seq + " " + snip(payloadB64, 2000));
                // Текстовые кадры сохранять можно аналогично, но чаще не требуется.
                saveRecvText(seq, url, opcode, payloadB64);
                return;
            }

            // бинарные кадры — payload приходит как base64-строка
            byte[] raw = Base64.getDecoder().decode(payloadB64);
            byte[] inflated = maybeInflate(raw);

            boolean hasSpOt = inflated.length >= 4
                    && inflated[0]=='S' && inflated[1]=='p'
                    && inflated[2]=='O' && inflated[3]=='t';
            byte[] bodyNoSpOt = hasSpOt ? Arrays.copyOfRange(inflated, 4, inflated.length) : inflated;

            logLine("🟢 WS RECV #" + seq + " (protobuf?): raw=" + raw.length + "B, infl=" + inflated.length + "B"
                    + (hasSpOt ? " [SpOt]" : "") + ", url=" + (url == null ? "-" : url));

            saveRecv(seq, url, opcode, payloadB64, raw, inflated, hasSpOt, bodyNoSpOt);

        } catch (Exception ex) {
            logLine("❌ processRecvFrame error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static void saveRecvText(int seq, String url, Integer opcode, String textPayload) {
        try {
            Path dir = Paths.get("ws_dump", "recv");
            Files.createDirectories(dir);
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(LocalDateTime.now());
            Path meta = dir.resolve(String.format("recv_%06d_%s.meta.txt", seq, ts));
            Path b64  = dir.resolve(String.format("recv_%06d_%s.text.txt", seq, ts));

            try (BufferedWriter w = Files.newBufferedWriter(meta, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                w.write('\uFEFF');
                w.write("time=" + LocalDateTime.now() + "\r\n");
                w.write("seq=" + seq + "\r\n");
                w.write("url=" + (url == null ? "-" : url) + "\r\n");
                w.write("opcode=" + opcode + "\r\n");
                w.write("type=text\r\n");
                w.write("lenText=" + textPayload.length() + "\r\n");
            }
            try (BufferedWriter w = Files.newBufferedWriter(b64, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                w.write('\uFEFF');
                w.write(wrapB64(Base64.getEncoder().encodeToString(textPayload.getBytes(StandardCharsets.UTF_8))));
            }
            // комбинированный b64
            synchronized (LOCK) {
                if (COMBINED_B64 != null) {
                    COMBINED_B64.write("----- RECV TEXT FRAME #" + seq + " -----\r\n");
                    COMBINED_B64.write(wrapB64(Base64.getEncoder().encodeToString(textPayload.getBytes(StandardCharsets.UTF_8))));
                    COMBINED_B64.write("\r\n");
                    COMBINED_B64.flush();
                }
            }
        } catch (Exception ignore) {}
    }

    private static void saveRecv(int seq, String url, Integer opcode, String payloadB64,
                                 byte[] raw, byte[] inflated, boolean hasSpOt, byte[] bodyNoSpOt) {
        try {
            Path dir = Paths.get("ws_dump", "recv");
            Files.createDirectories(dir);
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(LocalDateTime.now());

            Path meta  = dir.resolve(String.format("recv_%06d_%s.meta.txt", seq, ts));
            Path b64   = dir.resolve(String.format("recv_%06d_%s.b64.txt",  seq, ts));
            Path rawF  = dir.resolve(String.format("recv_%06d_%s.raw.bin",  seq, ts));
            Path inflF = dir.resolve(String.format("recv_%06d_%s.infl.bin", seq, ts));

            try (BufferedWriter w = Files.newBufferedWriter(meta, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                w.write('\uFEFF');
                w.write("time=" + LocalDateTime.now() + "\r\n");
                w.write("seq=" + seq + "\r\n");
                w.write("url=" + (url == null ? "-" : url) + "\r\n");
                w.write("opcode=" + opcode + "\r\n");
                w.write("hasSpOt=" + hasSpOt + "\r\n");
                w.write("lenRaw=" + raw.length + "\r\n");
                w.write("lenInfl=" + inflated.length + "\r\n");
                if (hasSpOt) w.write("lenInflNoSpOt=" + bodyNoSpOt.length + "\r\n");
            }

            // base64 исходного кадра (как пришёл)
            try (BufferedWriter w = Files.newBufferedWriter(b64, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                w.write('\uFEFF');
                w.write(wrapB64(payloadB64));
            }

            // бинарники
            Files.write(rawF,  raw,  StandardOpenOption.CREATE_NEW);
            Files.write(inflF, inflated, StandardOpenOption.CREATE_NEW);

            // вариант без SpOt
            if (hasSpOt) {
                Path noSpotF = dir.resolve(String.format("recv_%06d_%s.infl.nospot.bin", seq, ts));
                Files.write(noSpotF, bodyNoSpOt, StandardOpenOption.CREATE_NEW);
            }

            // комбинированные файлы
            synchronized (LOCK) {
                if (COMBINED_B64 != null) {
                    COMBINED_B64.write("----- RECV FRAME #" + seq + " -----\r\n");
                    COMBINED_B64.write(wrapB64(payloadB64));
                    COMBINED_B64.write("\r\n");
                    COMBINED_B64.flush();
                }
                if (COMBINED_BIN != null) {
                    writeIntBE(COMBINED_BIN, inflated.length);
                    COMBINED_BIN.write(inflated);
                    COMBINED_BIN.flush();
                }
                if (COMBINED_BIN_NO_SPOT != null) {
                    byte[] toWrite = hasSpOt ? bodyNoSpOt : inflated;
                    writeIntBE(COMBINED_BIN_NO_SPOT, toWrite.length);
                    COMBINED_BIN_NO_SPOT.write(toWrite);
                    COMBINED_BIN_NO_SPOT.flush();
                }
            }
        } catch (Exception ignore) {}
    }

    // === HELPERS ===
    private static boolean looksLikeJson(String s) {
        if (s == null) return false;
        var t = s.stripLeading();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static String snip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…(truncated)…";
    }

    private static String wrapB64(String b64) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < b64.length(); i += 76) {
            int end = Math.min(i + 76, b64.length());
            out.append(b64, i, end).append("\r\n");
        }
        return out.toString();
    }

    private static byte[] maybeInflate(byte[] in) {
        if (in.length >= 4 && in[0]=='S' && in[1]=='p' && in[2]=='O' && in[3]=='t') return in;
        try {
            java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
            inf.setInput(in);
            byte[] buf = new byte[Math.max(1024, in.length * 4)];
            int n = inf.inflate(buf);
            inf.end();
            if (n > 0) return Arrays.copyOf(buf, n);
        } catch (Exception ignore) {}
        return in;
    }

    private static void initCombinedSinks() throws Exception {
        Path dir = Paths.get("ws_dump", "combined");
        Files.createDirectories(dir);
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        Path log = dir.resolve("frames_" + ts + ".log");
        COMBINED_LOG = Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        COMBINED_LOG.write('\uFEFF');
        logLine("💾 combined log: " + log.toAbsolutePath());

        Path b64 = dir.resolve("frames_" + ts + ".b64.txt");
        COMBINED_B64 = Files.newBufferedWriter(b64, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        COMBINED_B64.write('\uFEFF');
        logLine("💾 combined b64: " + b64.toAbsolutePath());

        Path bin = dir.resolve("frames_" + ts + ".infl.bin");
        COMBINED_BIN = Files.newOutputStream(bin, StandardOpenOption.CREATE_NEW);
        logLine("💾 combined infl.bin: " + bin.toAbsolutePath());

        Path binNoSpot = dir.resolve("frames_" + ts + ".infl.nospot.bin");
        COMBINED_BIN_NO_SPOT = Files.newOutputStream(binNoSpot, StandardOpenOption.CREATE_NEW);
        logLine("💾 combined infl.nospot.bin: " + binNoSpot.toAbsolutePath());
    }

    private static void closeCombinedSinks() {
        try { if (COMBINED_LOG != null) COMBINED_LOG.close(); } catch (Exception ignore) {}
        try { if (COMBINED_B64 != null) COMBINED_B64.close(); } catch (Exception ignore) {}
        try { if (COMBINED_BIN != null) COMBINED_BIN.close(); } catch (Exception ignore) {}
        try { if (COMBINED_BIN_NO_SPOT != null) COMBINED_BIN_NO_SPOT.close(); } catch (Exception ignore) {}
    }

    private static void logLine(String s) {
        System.out.println(s);
        synchronized (LOCK) {
            try {
                if (COMBINED_LOG != null) {
                    COMBINED_LOG.write(s + "\r\n");
                    COMBINED_LOG.flush();
                }
            } catch (Exception ignore) {}
        }
    }

    private static void writeIntBE(OutputStream os, int v) throws Exception {
        os.write((v >>> 24) & 0xFF);
        os.write((v >>> 16) & 0xFF);
        os.write((v >>> 8)  & 0xFF);
        os.write((v)        & 0xFF);
    }
}
