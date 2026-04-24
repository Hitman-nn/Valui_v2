package BotValui.testline;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class ProtobufRaw {
    private ProtobufRaw() {}

    public static ByteString varints(int id, int type) {
        return varints(new int[] { id, type });
    }

    public static ByteString varints(int... values) {
        var baos = new ByteArrayOutputStream(values.length * 5);
        var cos = CodedOutputStream.newInstance(baos);
        try {
            for (int v : values) {
                cos.writeInt32NoTag(v);
            }
            cos.flush();
            return ByteString.copyFrom(baos.toByteArray());
        } catch (IOException e) {
            throw new AssertionError("Failed to encode varints", e);
        }
    }
}