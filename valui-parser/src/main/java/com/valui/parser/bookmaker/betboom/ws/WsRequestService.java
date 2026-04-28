package com.valui.parser.bookmaker.betboom.ws;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import proto.betboom.Envelope;

import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@Slf4j
@Service
public class WsRequestService {

    private final WsClientBorrowingPool pool;
    private final Semaphore globalRps;

    public WsRequestService(WsClientBorrowingPool pool,
                            @Value("${ws.rps:40}") int rps) {
        this.pool = pool;
        this.globalRps = new Semaphore(Math.max(1, rps), true);
    }

    public WsClientBorrowingPool getPool() {
        return pool;
    }

    /** Send frame and await first Envelope matching the predicate. */
    public byte[] sendAndAwaitFiltered(byte[] frame, long awaitMs,
                                       Predicate<Envelope> ok) throws Exception {
        Thread.sleep(ThreadLocalRandom.current().nextInt(0, 200));
        long cid = ThreadLocalRandom.current().nextLong();
        globalRps.acquire();
        try (WsLease lease = pool.borrow()) {
            lease.getClient().clearInbox();
            log.debug("[cid={}] sendAndAwaitFiltered: frame size={}", cid, frame.length);
            lease.sendBinary(frame).join();

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(awaitMs);
            while (System.nanoTime() < deadline) {
                long leftMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                byte[] bin = lease.getClient().awaitBinary(leftMs, TimeUnit.MILLISECONDS);
                if (bin == null) continue;
                try {
                    Envelope env = Envelope.parseFrom(bin);
                    if (ok.test(env)) {
                        log.debug("[cid={}] matched envelope kind={}", cid, env.getKindCase());
                        return bin;
                    }
                    log.debug("[cid={}] skip envelope kind={}", cid, env.getKindCase());
                } catch (InvalidProtocolBufferException e) {
                    log.error("[cid={}] failed to parse Envelope, size={}, b64={}",
                            cid, bin.length, base64Safe(bin), e);
                }
            }
            log.debug("[cid={}] sendAndAwaitFiltered: timeout {} ms, no match", cid, awaitMs);
            return null;
        } finally {
            globalRps.release();
        }
    }

    private static String base64Safe(byte[] data) {
        if (data == null) return "<null>";
        int max = Math.min(data.length, 512);
        byte[] slice = data.length == max ? data : Arrays.copyOf(data, max);
        String b64 = Base64.getEncoder().encodeToString(slice);
        return data.length > max ? b64 + "...(fullSize=" + data.length + ")" : b64;
    }
}
