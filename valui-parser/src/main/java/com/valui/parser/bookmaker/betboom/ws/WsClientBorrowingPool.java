package com.valui.parser.bookmaker.betboom.ws;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class WsClientBorrowingPool implements SmartLifecycle {

    private final WsPoolProperties props;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 4));

    private final List<Slot> slots;
    private final BlockingQueue<Integer> free;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WsClientBorrowingPool(WsPoolProperties props) {
        this.props = Objects.requireNonNull(props);
        int cap = props.getMaxSize();
        this.slots = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) slots.add(new Slot(i));
        this.free = new LinkedBlockingQueue<>(cap);
    }

    public WsLease borrow() throws InterruptedException, TimeoutException {
        return tryBorrow(props.getReadyTimeout().toMillis());
    }

    public WsLease tryBorrow(long timeoutMs) throws InterruptedException, TimeoutException {
        ensureRunning();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Integer id = pollFree(deadline);
        if (id == null) throw new TimeoutException("No free connections");
        Slot s = slots.get(id);
        long leftMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        if (!s.awaitReady(leftMs, TimeUnit.MILLISECONDS)) {
            free.offer(id); // return slot so it can be borrowed again once ready
            log.warn("Pool: Connection #{} not ready after {} ms", id, leftMs);
            throw new TimeoutException("Connection #" + id + " not ready");
        }
        return new WsLease(id, s.client, () -> free.offer(id), () -> reconnectClean(id));
    }

    public int size()      { return props.getMaxSize(); }
    public int connected() { return (int) slots.stream().filter(Slot::isConnected).count(); }
    public int available() { return free.size(); }

    @Override public void start() {
        if (!running.compareAndSet(false, true)) return;
        for (int i = 0; i < props.getMaxSize(); i++) connectSlot(i);
        log.info("WS pool started: {} slots", props.getMaxSize());
    }

    @Override @PreDestroy public void stop() {
        if (!running.compareAndSet(true, false)) return;
        slots.forEach(Slot::closeNow);
        free.clear();
        scheduler.shutdownNow();
        log.info("WS pool stopped.");
    }

    @Override public boolean isRunning()      { return running.get(); }
    @Override public boolean isAutoStartup()  { return true; }
    @Override public int getPhase()           { return Integer.MAX_VALUE; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }

    // ── internals ─────────────────────────────────────────────────────────────

    private void ensureRunning() {
        if (!isRunning()) throw new IllegalStateException("Pool is not running");
    }

    private Integer pollFree(long deadlineNanos) throws InterruptedException {
        Integer id = free.poll();
        if (id != null) return id;
        long rem = deadlineNanos - System.nanoTime();
        if (rem <= 0) return null;
        return free.poll(rem, TimeUnit.NANOSECONDS);
    }

    private void connectSlot(int idx) {
        slots.get(idx).connect(0);
    }

    /**
     * Reconnects a slot without counting it as a failure.
     * Called when a lease is returned after use — resets server-side subscriptions
     * that BetBoom keeps alive on the connection indefinitely.
     */
    void reconnectClean(int slotId) {
        if (!running.get()) return;
        Slot s = slots.get(slotId);
        s.failures = 0;
        s.connect(0);
    }

    // ── Slot ─────────────────────────────────────────────────────────────────

    private final class Slot {
        final int id;
        volatile WsClient client;
        volatile boolean connected = false;
        volatile int failures = 0;
        volatile CountDownLatch readyOnce = new CountDownLatch(1);
        final AtomicBoolean reconnecting = new AtomicBoolean(false);

        Slot(int id) { this.id = id; }

        boolean isConnected() { return connected; }

        void resetReadyLatch() { readyOnce = new CountDownLatch(1); }

        void connect(int attempt) {
            if (!running.get()) return;
            reconnecting.set(false);
            resetReadyLatch();

            client = WsClient.builder()
                    .url(props.getUrl())
                    .headers(props.getHeaders())
                    .connectTimeout(props.getConnectTimeout())
                    .initialBuffer(props.getInitialBuffer())
                    .onOpen(() -> { connected = true; failures = 0; })
                    .onHello(bytes -> {
                        try { client.clearInbox(); } catch (Exception ignore) {}
                        if (readyOnce.getCount() > 0) {
                            readyOnce.countDown();
                            free.offer(id);
                        }
                    })
                    .onClose((code, reason) -> scheduleReconnect())
                    .onError(err -> scheduleReconnect())
                    .build();

            Duration helloTimeout = props.getConnectTimeout().plusSeconds(5);
            client.connectOrFail(helloTimeout)
                    .whenComplete((v, err) -> {
                        if (err != null) {
                            log.warn("Pool: connect failed slot #{}, attempt={}: {}", id, attempt, err.toString());
                            scheduleReconnect();
                        } else {
                            connected = true;
                            failures = 0;
                        }
                    });
        }

        boolean awaitReady(long t, TimeUnit u) throws InterruptedException {
            if (readyOnce.getCount() == 0) return true;
            return readyOnce.await(t, u);
        }

        void scheduleReconnect() {
            if (!reconnecting.compareAndSet(false, true)) return; // only one reconnect at a time
            connected = false;
            failures++;
            free.remove(id);
            long base = props.getBackoffBase().toMillis(), max = props.getBackoffMax().toMillis();
            long backoff = Math.min(base * (1L << Math.min(6, failures)), max);
            long jitter = ThreadLocalRandom.current().nextLong(backoff / 3 + 1);
            long delay  = backoff / 2 + jitter;
            if (!running.get()) {
                reconnecting.set(false);
                return;
            }
            log.warn("Pool: scheduling reconnect slot #{}, failures={}, delay={} ms", id, failures, delay);
            scheduler.schedule(() -> slots.get(id).connect(failures), delay, TimeUnit.MILLISECONDS);
        }

        void closeNow() {
            connected = false;
            try {
                if (client != null) client.closeNormal().get(500, TimeUnit.MILLISECONDS);
            } catch (Exception ignore) {
                try { if (client != null) client.abort(); } catch (Exception ignore2) {}
            } finally {
                client = null;
            }
        }
    }
}
