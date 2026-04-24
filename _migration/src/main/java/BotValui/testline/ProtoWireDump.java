package BotValui.testline;

import java.nio.charset.StandardCharsets;

public final class ProtoWireDump {

    private static final int MAX_PREVIEW_HEX = 96;
    private static final int MAX_UTF8_PREVIEW = 240;
    private static final int MAX_RECURSION_DEPTH = 6;

    private ProtoWireDump() {
    }

    public static void dump(String label, byte[] data) {
        System.out.println("RAW DUMP: " + label + ", size=" + (data == null ? 0 : data.length));
        if (data == null || data.length == 0) {
            System.out.println("  <empty>");
            return;
        }
        dumpInternal(data, 1, 0);
    }

    private static void dumpInternal(byte[] data, int indentLevel, int depth) {
        if (data == null || data.length == 0) {
            print(indentLevel, "<empty>");
            return;
        }

        if (depth > MAX_RECURSION_DEPTH) {
            print(indentLevel, "<max recursion depth reached>");
            return;
        }

        int[] posRef = {0};

        while (posRef[0] < data.length) {
            int fieldStart = posRef[0];

            long tag;
            try {
                tag = readVarint(data, posRef);
            } catch (Exception e) {
                print(indentLevel, "@" + fieldStart + " <failed to read tag: " + e.getMessage() + ">");
                return;
            }

            int fieldNumber = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x07);

            try {
                switch (wireType) {
                    case 0 -> {
                        long value = readVarint(data, posRef);
                        print(indentLevel, "@" + fieldStart + " field=" + fieldNumber + " VARINT value=" + value);
                    }
                    case 1 -> {
                        long value = readFixed64(data, posRef);
                        print(indentLevel, "@" + fieldStart + " field=" + fieldNumber + " FIXED64 value=" + value);
                    }
                    case 2 -> {
                        int len = (int) readVarint(data, posRef);
                        byte[] bytes = readBytes(data, posRef, len);

                        print(indentLevel, "@" + fieldStart + " field=" + fieldNumber + " LEN len=" + len);

                        String utf8 = tryUtf8(bytes);
                        if (utf8 != null) {
                            print(indentLevel + 1, "utf8='" + abbreviate(utf8) + "'");
                        } else {
                            print(indentLevel + 1, "hex=" + toHex(bytes));
                        }

                        if (looksLikeEmbeddedProto(bytes)) {
                            print(indentLevel + 1, "{");
                            dumpInternal(bytes, indentLevel + 2, depth + 1);
                            print(indentLevel + 1, "}");
                        }
                    }
                    case 5 -> {
                        int value = readFixed32(data, posRef);
                        print(indentLevel, "@" + fieldStart + " field=" + fieldNumber + " FIXED32 value=" + value);
                    }
                    default -> {
                        print(indentLevel, "@" + fieldStart + " field=" + fieldNumber + " wire=" + wireType + " <unsupported>");
                        return;
                    }
                }
            } catch (Exception e) {
                print(indentLevel, "@" + fieldStart + " field=" + fieldNumber + " wire=" + wireType
                        + " <parse error: " + e.getMessage() + ">");
                return;
            }
        }
    }

    private static boolean looksLikeEmbeddedProto(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }

        int[] posRef = {0};
        int fieldsParsed = 0;

        try {
            while (posRef[0] < bytes.length && fieldsParsed < 6) {
                int beforeTag = posRef[0];
                long tag = readVarint(bytes, posRef);
                if (tag == 0) {
                    return false;
                }

                int fieldNumber = (int) (tag >>> 3);
                int wireType = (int) (tag & 0x07);

                if (fieldNumber <= 0) {
                    return false;
                }

                switch (wireType) {
                    case 0 -> readVarint(bytes, posRef);
                    case 1 -> skip(bytes, posRef, 8);
                    case 2 -> {
                        int len = (int) readVarint(bytes, posRef);
                        if (len < 0 || posRef[0] + len > bytes.length) {
                            return false;
                        }
                        skip(bytes, posRef, len);
                    }
                    case 5 -> skip(bytes, posRef, 4);
                    default -> {
                        return false;
                    }
                }

                if (posRef[0] <= beforeTag) {
                    return false;
                }

                fieldsParsed++;
            }

            return fieldsParsed > 0 && posRef[0] == bytes.length;
        } catch (Exception e) {
            return false;
        }
    }

    private static long readVarint(byte[] data, int[] posRef) {
        long result = 0;
        int shift = 0;

        while (true) {
            if (posRef[0] >= data.length) {
                throw new IllegalStateException("unexpected EOF while reading varint");
            }

            int b = data[posRef[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;

            if ((b & 0x80) == 0) {
                return result;
            }

            shift += 7;
            if (shift > 63) {
                throw new IllegalStateException("varint too long");
            }
        }
    }

    private static int readFixed32(byte[] data, int[] posRef) {
        ensureAvailable(data, posRef[0], 4);
        int b0 = data[posRef[0]++] & 0xFF;
        int b1 = data[posRef[0]++] & 0xFF;
        int b2 = data[posRef[0]++] & 0xFF;
        int b3 = data[posRef[0]++] & 0xFF;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static long readFixed64(byte[] data, int[] posRef) {
        ensureAvailable(data, posRef[0], 8);
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) data[posRef[0]++] & 0xFF) << (8 * i);
        }
        return result;
    }

    private static byte[] readBytes(byte[] data, int[] posRef, int len) {
        if (len < 0) {
            throw new IllegalStateException("negative length");
        }
        ensureAvailable(data, posRef[0], len);

        byte[] out = new byte[len];
        System.arraycopy(data, posRef[0], out, 0, len);
        posRef[0] += len;
        return out;
    }

    private static void skip(byte[] data, int[] posRef, int len) {
        ensureAvailable(data, posRef[0], len);
        posRef[0] += len;
    }

    private static void ensureAvailable(byte[] data, int pos, int needed) {
        if (pos + needed > data.length) {
            throw new IllegalStateException("unexpected EOF");
        }
    }

    private static String tryUtf8(byte[] bytes) {
        try {
            String s = new String(bytes, StandardCharsets.UTF_8);
            if (s.isEmpty()) {
                return null;
            }

            int printable = 0;
            for (char ch : s.toCharArray()) {
                if (!Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                    printable++;
                }
            }

            double ratio = (double) printable / s.length();
            return ratio >= 0.85 ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() <= ProtoWireDump.MAX_UTF8_PREVIEW) {
            return s;
        }
        return s.substring(0, ProtoWireDump.MAX_UTF8_PREVIEW) + "...";
    }

    private static String toHex(byte[] bytes) {
        int len = Math.min(bytes.length, ProtoWireDump.MAX_PREVIEW_HEX);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", bytes[i]));
            if (i < len - 1) {
                sb.append(' ');
            }
        }

        if (bytes.length > ProtoWireDump.MAX_PREVIEW_HEX) {
            sb.append(" ...");
        }

        return sb.toString();
    }

    private static void print(int indentLevel, String text) {
        System.out.println("  ".repeat(Math.max(0, indentLevel)) + text);
    }
}