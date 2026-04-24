package BotValui.Service.betboom.ws;

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

    /**
     * Простой вариант: отправляет бинарный фрейм и ждёт первый бинарный ответ (без фильтра по Envelope).
     */
    public byte[] sendAndAwait(byte[] frame, long awaitMs) throws Exception {
        // небольшой джиттер, чтобы куча задач не стреляла в один тик
        Thread.sleep(ThreadLocalRandom.current().nextInt(0, 200));

        long cid = ThreadLocalRandom.current().nextLong(); // correlation id для логов
        globalRps.acquire();
        try (WsLease lease = pool.borrow()) {
            // подчистили хвост перед новой операцией
            lease.getClient().clearInbox();

            log.debug("[cid={}] sendAndAwait: sending frame, size={} bytes", cid, frame.length);
            lease.sendBinary(frame).join();

            byte[] resp = lease.getClient().awaitBinary(awaitMs, TimeUnit.MILLISECONDS);
            if (resp == null) {
                log.warn("[cid={}] sendAndAwait: timeout after {} ms (no binary response)", cid, awaitMs);
            } else {
                log.debug("[cid={}] sendAndAwait: got response, size={} bytes", cid, resp.length);
            }
            return resp;
        } finally {
            globalRps.release();
        }
    }

    /**
     * Расширенный вариант: отправляет запрос и ждёт первый Envelope,
     * удовлетворяющий predicate. Все остальные кадры парсятся и игнорируются.
     */
    public byte[] sendAndAwaitFiltered(byte[] frame,
                                       long awaitMs,
                                       java.util.function.Predicate<Envelope> ok) throws Exception {

        Thread.sleep(ThreadLocalRandom.current().nextInt(0, 200));

        long cid = ThreadLocalRandom.current().nextLong();
        globalRps.acquire();
        try (WsLease lease = pool.borrow()) {
            lease.getClient().clearInbox();

            log.debug("[cid={}] sendAndAwaitFiltered: sending frame, size={} bytes", cid, frame.length);
            lease.sendBinary(frame).join();

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(awaitMs);
            while (System.nanoTime() < deadline) {
                long leftNs = deadline - System.nanoTime();
                long leftMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(leftNs));

                byte[] bin = lease.getClient().awaitBinary(leftMs, TimeUnit.MILLISECONDS);
                if (bin == null) {
                    // за этот кусок времени ничего не прилетело — ждём дальше
                    continue;
                }

                try {
                    Envelope env = Envelope.parseFrom(bin);
                    log.debug("[cid={}] recv envelope kind={}, size={}",
                            cid, env.getKindCase(), bin.length);

                    if (ok.test(env)) {
                        log.debug("[cid={}] sendAndAwaitFiltered: matched envelope kind={}",
                                cid, env.getKindCase());
                        return bin;
                    } else {
                        log.debug("[cid={}] sendAndAwaitFiltered: skip envelope kind={} (not match predicate), base64={}",
                                cid, env.getKindCase(), base64Safe(bin));
                    }
                } catch (InvalidProtocolBufferException e) {
                    log.error("[cid={}] sendAndAwaitFiltered: failed to parse Envelope, size={}, base64={}",
                            cid, bin.length, base64Safe(bin), e);
                }
            }

            log.debug("[cid={}] sendAndAwaitFiltered: timeout {} ms, no matching envelope", cid, awaitMs);
            return null;
        } finally {
            globalRps.release();
        }
    }

    private static String base64Safe(byte[] data) {
        if (data == null) return "<null>";
        int max = Math.min(data.length, 512);
        byte[] slice = (data.length == max) ? data : Arrays.copyOf(data, max);
        String b64 = Base64.getEncoder().encodeToString(slice);
        if (data.length > max) {
            return b64 + "...(truncated, fullSize=" + data.length + ")";
        }
        return b64;
    }
}
