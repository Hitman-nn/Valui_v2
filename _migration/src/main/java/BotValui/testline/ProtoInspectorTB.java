package BotValui.testline;

import com.google.protobuf.CodedInputStream;

import java.nio.charset.StandardCharsets;

public final class ProtoInspectorTB {

    private ProtoInspectorTB() {}

    public static String prettyDump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        dumpRaw(sb, data, 0, data.length, 0);
        return sb.toString();
    }

    private static void dumpRaw(StringBuilder sb, byte[] buf, int off, int len, int depth) {
        String indent = "  ".repeat(depth);
        try {
            CodedInputStream in = CodedInputStream.newInstance(buf, off, len);
            while (!in.isAtEnd()) {
                int tag = in.readTag();
                if (tag == 0) break;
                int field = tag >>> 3;
                int wire = tag & 7;
                sb.append(indent).append(field).append(" ");

                switch (wire) {
                    case 0 -> {
                        long v = in.readInt64();
                        sb.append(": ").append(v).append(" (varint)\n");
                    }
                    case 1 -> {
                        long v = in.readFixed64();
                        sb.append(": ").append(v).append(" (fixed64)\n");
                    }
                    case 2 -> {
                        int L = in.readRawVarint32();
                        int start = in.getTotalBytesRead();
                        byte[] sub = in.readRawBytes(L);
                        boolean isText = looksUtf8(sub);
                        sb.append(": ").append(isText ? "\"" + escape(new String(sub, StandardCharsets.UTF_8)) + "\"" : "(bytes)")
                                .append(" (len=").append(L).append(")\n");
                        if (looksLikeMsg(sub)) {
                            sb.append(indent).append("{\n");
                            dumpRaw(sb, sub, 0, sub.length, depth + 1);
                            sb.append(indent).append("}\n");
                        }
                    }
                    case 5 -> {
                        int v = in.readFixed32();
                        sb.append(": ").append(v).append(" (fixed32)\n");
                    }
                    default -> {
                        sb.append(": (unknown wiretype ").append(wire).append(")\n");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            sb.append(indent).append("(decode error: ").append(e.getMessage()).append(")\n");
        }
    }

    private static boolean looksUtf8(byte[] sub) {
        try {
            String s = new String(sub, StandardCharsets.UTF_8);
            for (char c : s.toCharArray()) {
                if (Character.isISOControl(c) && !Character.isWhitespace(c)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean looksLikeMsg(byte[] sub) {
        try {
            CodedInputStream in = CodedInputStream.newInstance(sub);
            int hits = 0;
            while (!in.isAtEnd() && hits < 3) {
                int tag = in.readTag();
                if (tag == 0) break;
                int wire = tag & 7;
                hits++;
                switch (wire) {
                    case 0 -> in.readInt64();
                    case 1 -> in.readFixed64();
                    case 2 -> {
                        int L = in.readRawVarint32();
                        in.skipRawBytes(L);
                    }
                    case 5 -> in.readFixed32();
                    default -> { return false; }
                }
            }
            return hits >= 1;
        } catch (Exception e) {
            return false;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
