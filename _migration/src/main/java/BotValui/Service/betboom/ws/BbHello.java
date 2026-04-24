package BotValui.Service.betboom.ws;

import proto.betboom.Envelope;

/** Утилита для распознавания HELLO-кадра: Envelope{ response_connect_open = <bytes> }. */
public final class BbHello {
    private BbHello() {}

    /** true если кадр — Envelope{ response_connect_open = <bytes> } */
    public static boolean isHelloEnvelope(byte[] frame) {
        try {
            Envelope env = Envelope.parseFrom(frame);
            return env.hasResponseConnectOpen();
        } catch (Exception ignore) {
            return false;
        }
    }
}
