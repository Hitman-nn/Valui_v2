package BotValui.testline;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public class PostProcessRecv {

    public static void main(String[] args) throws Exception {
        Path recvTextDir = Paths.get("ws_dump", "recv_text");
        if (!Files.isDirectory(recvTextDir)) {
            System.out.println("Нет папки " + recvTextDir.toAbsolutePath());
            return;
        }
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        Path outJsonl = Paths.get("out", "ws_jsonl", "frames_" + runId + ".jsonl");
        Path snapDir  = Paths.get("out", "ws_snapshots", runId);
        Files.createDirectories(outJsonl.getParent());
        Files.createDirectories(snapDir);

        var files = new ArrayList<Path>();
        try (var s = Files.list(recvTextDir)) {
            s.filter(p -> p.getFileName().toString().startsWith("recv_") && p.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), PostProcessRecv::bySeq))
                    .forEach(files::add);
        }
        if (files.isEmpty()) {
            System.out.println("Нет файлов в " + recvTextDir.toAbsolutePath());
            return;
        }

        int savedSnaps = 0;
        try (var w = Files.newBufferedWriter(outJsonl, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            for (Path f : files) {
                String b64 = extractB64Block(f);
                if (b64 == null || b64.isBlank()) continue;

                byte[] raw = Base64.getDecoder().decode(b64);
                byte[] bin = maybeInflate(raw);
                byte[] body = stripSpOt(bin);

                if (!looksLikeProtobuf(body)) {
                    // просто пропустим служебные
                    continue;
                }

                String json = Json.pretty(ProtoLite.parseMessage(body));
                w.write(json);
                w.write("\n");

                // первые 3 больших кадра — отдельно
                if (savedSnaps < 3 && body.length > 4000) {
                    String name = "recv_" + (++savedSnaps) + ".json";
                    Files.writeString(snapDir.resolve(name), json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                }
            }
        }

        System.out.println("Готово:");
        System.out.println("  JSONL:     " + outJsonl.toAbsolutePath());
        System.out.println("  snapshots: " + snapDir.toAbsolutePath() + " (" + savedSnaps + " файлов)");
    }

    // --------- helpers ---------

    // вытаскиваем блок base64 из файла recv_text/*.txt между строкой "B64 :" и след. секцией
    static String extractB64Block(Path f) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!in) {
                    if (line.startsWith("B64")) in = true;
                } else {
                    if (line.startsWith("HEX ") || line.startsWith("HEX")
                            || line.startsWith("ASCII") || line.startsWith("SpOt")
                            || line.startsWith("---") || line.startsWith("Time:")
                            || line.startsWith("URL") || line.startsWith("Size:")) break;
                    String s = line.trim();
                    if (!s.isEmpty()) sb.append(s);
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static byte[] stripSpOt(byte[] b) {
        if (b.length >= 4 && b[0]=='S' && b[1]=='p' && b[2]=='O' && b[3]=='t') {
            return Arrays.copyOfRange(b, 4, b.length);
        }
        return b;
    }

    static byte[] maybeInflate(byte[] in) {
        if (in.length >= 4 && in[0]=='S' && in[1]=='p' && in[2]=='O' && in[3]=='t') return in;
        // raw deflate with trailer
        try {
            byte[] trailer = new byte[]{0,0,(byte)0xFF,(byte)0xFF};
            byte[] candidate = Arrays.copyOf(in, in.length + trailer.length);
            System.arraycopy(trailer, 0, candidate, in.length, trailer.length);
            Inflater inflater = new Inflater(true);
            inflater.setInput(candidate);
            byte[] buf = new byte[Math.max(in.length * 4, 64*1024)];
            int n = inflater.inflate(buf);
            inflater.end();
            if (n > 0) return Arrays.copyOf(buf, n);
        } catch (Exception ignore) {}
        // zlib
        try (InflaterInputStream iis = new InflaterInputStream(new java.io.ByteArrayInputStream(in))) {
            byte[] out = iis.readAllBytes();
            if (out.length > 0) return out;
        } catch (Exception ignore) {}
        return in;
    }

    static boolean looksLikeProtobuf(byte[] data) {
        try {
            ProtoLite.Varint vi = ProtoLite.readVarintPublic(data, 0);
            int wire = (int)(vi.value & 7);
            if (wire!=0 && wire!=1 && wire!=2 && wire!=5) return false;
            if (wire==2) {
                ProtoLite.Varint lv = ProtoLite.readVarintPublic(data, vi.next);
                long L = lv.value;
                if (L < 0 || L > data.length) return false;
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    static int bySeq(String a, String b) {
        // recv_<url>_<seq>.txt → сортируем по числу seq
        int ia = lastNum(a), ib = lastNum(b);
        return Integer.compare(ia, ib);
    }
    static int lastNum(String s) {
        int i = s.length()-1, mul = 1, val = 0;
        while (i>=0 && Character.isDigit(s.charAt(i))) { val += (s.charAt(i)-'0')*mul; mul*=10; i--; }
        return val;
    }

    // --------- JSON printer ---------
    static final class Json {
        static String pretty(Object o) {
            StringBuilder sb = new StringBuilder();
            write(o, sb, 0);
            return sb.toString();
        }
        private static void write(Object o, StringBuilder sb, int d) {
            if (o == null) { sb.append("null"); return; }
            if (o instanceof String s) { sb.append('"').append(esc(s)).append('"'); return; }
            if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
            if (o instanceof List<?> a) {
                sb.append("[\n");
                for (int i=0;i<a.size();i++) {
                    indent(sb,d+1); write(a.get(i), sb, d+1);
                    if (i+1<a.size()) sb.append(',');
                    sb.append('\n');
                }
                indent(sb,d); sb.append(']'); return;
            }
            if (o instanceof Map<?,?> m) {
                sb.append("{\n");
                int i=0, n=m.size();
                for (var e: m.entrySet()) {
                    indent(sb,d+1); sb.append('"').append(esc(String.valueOf(e.getKey()))).append("\": ");
                    write(e.getValue(), sb, d+1);
                    if (++i<n) sb.append(',');
                    sb.append('\n');
                }
                indent(sb,d); sb.append('}'); return;
            }
            sb.append('"').append(esc(String.valueOf(o))).append('"');
        }
        private static void indent(StringBuilder sb, int d){ sb.append("  ".repeat(Math.max(0,d))); }
        private static String esc(String s){ return s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n"); }
    }

    // --------- минимальный protobuf → Map/List (без падений на обрезанных кадрах) ---------
    static final class ProtoLite {
        private static final int MAX_DEPTH = 16;

        static Object parseMessage(byte[] data) { return parseMessage(data, 0, data.length, 0); }

        private static Object parseMessage(byte[] data, int off, int len, int depth) {
            Map<String, Object> obj = new LinkedHashMap<>();
            int i = off, end = off + len;
            while (i < end) {
                Varint keyVi;
                try {
                    keyVi = readVarint(data, i);
                } catch (Throwable t) {
                    obj.put("_truncated", true);
                    break;
                }
                long key = keyVi.value; i = keyVi.next;
                int field = (int) (key >>> 3);
                int wire  = (int) (key & 7);
                String fk = "f" + field;

                try {
                    switch (wire) {
                        case 0 -> { // varint
                            Varint v = readVarint(data, i);
                            i = v.next;
                            add(obj, fk, v.value);
                        }
                        case 1 -> { // fixed64
                            if (i + 8 > end) {
                                add(obj, "_truncated_at", i - off);
                                obj.put("_truncated", true);
                                return obj;
                            }
                            long v = ByteBuffer.wrap(data, i, 8)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
                            i += 8;
                            add(obj, fk, v);
                        }
                        case 2 -> { // length-delimited
                            Varint lv = readVarint(data, i);
                            i = lv.next;
                            int L = (int) Math.min(lv.value, end - i); // НЕ выходим за end
                            if (L < 0) { obj.put("_truncated", true); return obj; }

                            byte[] sub = Arrays.copyOfRange(data, i, i + L);
                            i += L;

                            Object val = decodeLenDelimited(sub, depth);
                            add(obj, fk, val);
                        }
                        case 5 -> { // fixed32
                            if (i + 4 > end) {
                                add(obj, "_truncated_at", i - off);
                                obj.put("_truncated", true);
                                return obj;
                            }
                            int v = ByteBuffer.wrap(data, i, 4)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                            i += 4;
                            add(obj, fk, v);
                        }
                        default -> {
                            // неизвестный wiretype — дальше не читаем
                            add(obj, "_unknown_wire", wire);
                            return obj;
                        }
                    }
                } catch (Throwable t) {
                    // Любая ошибка чтения — помечаем и выходим из текущего сообщения
                    add(obj, "_truncated_at", i - off);
                    obj.put("_truncated", true);
                    return obj;
                }
            }
            return obj;
        }

        private static Object decodeLenDelimited(byte[] sub, int depth) {
            boolean utf = isUtf8(sub);
            boolean embedded = (depth < MAX_DEPTH) && looksLikeMessage(sub);
            if (embedded) {
                try { return parseMessage(sub, 0, sub.length, depth + 1); }
                catch (Throwable ignore) { /* упадёт → трактуем как bytes */ }
            }
            if (utf) return new String(sub, StandardCharsets.UTF_8);
            return Map.of("_bytes_b64", Base64.getEncoder().encodeToString(sub), "_len", sub.length);
        }

        @SuppressWarnings("unchecked")
        private static void add(Map<String, Object> obj, String k, Object v) {
            Object prev = obj.get(k);
            if (prev == null) { obj.put(k, v); return; }
            if (prev instanceof List<?>) {
                List<Object> c = new ArrayList<>();
                for (Object x : (List<?>) prev) c.add(x);
                c.add(v);
                obj.put(k, c);
            } else {
                List<Object> arr = new ArrayList<>();
                arr.add(prev); arr.add(v);
                obj.put(k, arr);
            }
        }

        // публичный враппер — если нужен вне класса
        static Varint readVarintPublic(byte[] a, int i) { return readVarint(a, i); }

        private static Varint readVarint(byte[] a, int i) {
            long x = 0; int shift = 0, pos = i, limit = Math.min(a.length, i + 10); // varint ≤ 10 байт
            while (pos < limit) {
                int b = a[pos++] & 0xFF;
                x |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return new Varint(x, pos);
                shift += 7;
            }
            throw new IllegalStateException("not varint");
        }
        static final class Varint { final long value; final int next; Varint(long v, int n) { value = v; next = n; } }

        private static boolean isUtf8(byte[] sub) {
            try {
                CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder();
                CoderResult r = dec.decode(ByteBuffer.wrap(sub),
                        java.nio.CharBuffer.allocate(Math.max(1, sub.length * 2)), true);
                return !r.isError();
            } catch (Exception e) { return false; }
        }

        private static boolean looksLikeMessage(byte[] sub) {
            try {
                int i = 0, hits = 0, limit = Math.min(sub.length, 64);
                while (i < sub.length && i < limit) {
                    Varint vi = readVarint(sub, i); i = vi.next;
                    int field = (int) (vi.value >>> 3), wire = (int) (vi.value & 7);
                    if (field <= 0 || wire > 5) return false;
                    hits++;
                    switch (wire) {
                        case 0 -> { Varint v2 = readVarint(sub, i); i = v2.next; }
                        case 1 -> { if (i + 8 > sub.length) return false; i += 8; }
                        case 2 -> {
                            Varint lv = readVarint(sub, i);
                            long L = lv.value;
                            if (L < 0 || lv.next + L > sub.length) return false;
                            i = (int) (lv.next + L);
                        }
                        case 5 -> { if (i + 4 > sub.length) return false; i += 4; }
                        default -> { return false; }
                    }
                    if (hits >= 2) return true;
                }
                return hits >= 1;
            } catch (Throwable t) { return false; }
        }
    }

}
