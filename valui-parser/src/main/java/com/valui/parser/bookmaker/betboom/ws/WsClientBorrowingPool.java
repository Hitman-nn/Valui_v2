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
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public class WsClientBorrowingPool implements SmartLifecycle {

    // First N consecutive connect failures on a slot are logged at WARN; beyond that the
    // slot is clearly flapping and every attempt has already been reported, so drop to DEBUG.
    // Mirrors BetBoomParser.TIMEOUT_WARN_THRESHOLD.
    private static final int RECONNECT_WARN_THRESHOLD = 3;

    private final WsPoolProperties props;
    private final BetBoomFeedUuidProvider uuidProvider;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 4));

    private final List<Slot> slots;
    private final BlockingQueue<Integer> free;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Cumulative, drained by WsPoolHealthLogger every reporting window.
    private final LongAdder failureReconnects = new LongAdder();
    private final LongAdder hygieneReconnects  = new LongAdder();
    private final LongAdder framesDropped      = new LongAdder();
    private final LongAdder idleFramesDrained  = new LongAdder();
    private volatile ScheduledFuture<?> idleDrainTask;

    public WsClientBorrowingPool(WsPoolProperties props, BetBoomFeedUuidProvider uuidProvider) {
        this.props = Objects.requireNonNull(props);
        this.uuidProvider = Objects.requireNonNull(uuidProvider);
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
        return new WsLease(id, s.client, () -> handleReturn(id), () -> reconnectClean(id, "ws-timeout"));
    }

    public int size()      { return props.getMaxSize(); }
    public int connected() { return (int) slots.stream().filter(Slot::isConnected).count(); }
    public int available() { return free.size(); }

    // ── observability (see WsPoolHealthLogger / WsPoolMetricsBinder) ───────────

    /** Live snapshot — sum of all slots' current inbox backlog. */
    public long totalInboxBacklog() {
        long total = 0;
        for (Slot s : slots) {
            WsClient c = s.client;
            if (c != null) total += c.inboxSize();
        }
        return total;
    }

    public long drainFailureReconnects() { return failureReconnects.sumThenReset(); }
    public long drainHygieneReconnects()  { return hygieneReconnects.sumThenReset(); }
    public long drainFramesDropped()      { return framesDropped.sumThenReset(); }
    public long drainIdleFramesDrained()  { return idleFramesDrained.sumThenReset(); }

    @Override public void start() {
        if (!running.compareAndSet(false, true)) return;
        if (!props.isEnabled()) {
            log.warn("WS pool disabled (ws.pool.enabled=false) — not connecting any slot. " +
                    "isAvailable()/isConnectionReady() will report false the whole time, exactly " +
                    "as if every connection attempt were failing — this is deliberate.");
            return;
        }
        for (int i = 0; i < props.getMaxSize(); i++) connectSlot(i);
        long drainMs = props.getIdleDrainInterval().toMillis();
        idleDrainTask = scheduler.scheduleAtFixedRate(
                this::drainIdleSlots, drainMs, drainMs, TimeUnit.MILLISECONDS);
        log.info("WS pool started: {} slots, idle-drain every {}", props.getMaxSize(), props.getIdleDrainInterval());
    }

    @Override @PreDestroy public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (idleDrainTask != null) idleDrainTask.cancel(false);
        slots.forEach(Slot::closeNow);
        free.clear();
        scheduler.shutdownNow();
        log.info("WS pool stopped.");
    }

    /**
     * Proactively clears the inbox of every slot currently sitting idle in the free pool.
     * BetBoom keeps pushing odds updates for every subscription a connection has ever made,
     * whether or not anyone's actively borrowing it — without this, that background traffic
     * only ever gets cleared reactively (next borrow, or hygiene recycle), which in production
     * meant idle connections' 2000-frame caps stayed permanently saturated with data nobody
     * would ever read (see WsClient.MAX_INBOX). Only pops ids that are ACTUALLY in {@code free}
     * at the moment of poll — a slot currently on loan to a borrower is never touched, since
     * its id isn't in this queue while leased out.
     */
    private void drainIdleSlots() {
        if (!running.get()) return;
        int n = free.size();
        for (int i = 0; i < n; i++) {
            Integer id = free.poll();
            if (id == null) break; // raced with concurrent borrows — nothing left to drain
            WsClient c = slots.get(id).client;
            if (c != null) {
                int cleared = c.clearInbox();
                if (cleared > 0) idleFramesDrained.add(cleared);
            }
            free.offer(id);
        }
    }

    @Override public boolean isRunning()      { return running.get(); }
    @Override public boolean isAutoStartup()  { return true; }
    @Override public int getPhase()           { return Integer.MAX_VALUE; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * A real BetBoom client's handshake carries a {@code ?uuid=} query param — but (found the
     * hard way, 24.09: a locally-generated random one is reliably rejected with "Access
     * rejected", 100% reproducible either way) it is NOT a per-connection or per-session value.
     * It's a fixed constant tied to BetBoom's current widget build, published unauthenticated in
     * their own runtime config — see {@link BetBoomFeedUuidProvider}. Every slot shares the
     * exact same value; that's correct, not a bug — a real browser session doing the same thing
     * (opening several BetBoom WS connections) would use that identical uuid on all of them too.
     */
    private String withUuid(String baseUrl) {
        String uuid = uuidProvider.getUuid();
        if (uuid == null) {
            log.warn("[BetBoom-UUID] No uuid resolved yet from BetBoom's widget config — connecting " +
                    "without one; this attempt will very likely be rejected (see BetBoomFeedUuidProvider)");
            return baseUrl;
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "uuid=" + uuid;
    }

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
     * Normal lease return path (no error, no explicit reconnect requested). Recycles the slot
     * instead of returning it to the free pool once it's carried enough traffic — see
     * {@link WsPoolProperties#getRecycleAfterUses()} / {@link WsPoolProperties#getMaxConnectionAge()}.
     */
    private void handleReturn(int id) {
        Slot s = slots.get(id);
        int uses = ++s.useCount;
        long ageMs = System.currentTimeMillis() - s.connectedAtMs;
        if (uses >= props.getRecycleAfterUses() || ageMs >= props.getMaxConnectionAge().toMillis()) {
            reconnectClean(id, "hygiene uses=" + uses + " ageMs=" + ageMs);
        } else {
            free.offer(id);
        }
    }

    /**
     * Reconnects a slot without counting it as a connectivity failure (no backoff, no WARN).
     * Called either when a WS request times out with no matching reply (clears whatever
     * server-side subscriptions accumulated on the connection) or by {@link #handleReturn} for
     * routine hygiene recycling — BetBoom keeps subscriptions alive on a connection
     * indefinitely, so periodically starting fresh bounds both subscription count and backlog.
     */
    void reconnectClean(int slotId, String reason) {
        if (!running.get()) return;
        hygieneReconnects.increment();
        log.debug("Pool: clean reconnect slot #{} ({})", slotId, reason);
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
        volatile int useCount = 0;
        volatile long connectedAtMs = 0L;
        volatile CountDownLatch readyOnce = new CountDownLatch(1);
        final AtomicBoolean reconnecting = new AtomicBoolean(false);

        Slot(int id) { this.id = id; }

        boolean isConnected() { return connected; }

        void resetReadyLatch() { readyOnce = new CountDownLatch(1); }

        // Synchronized: connect() is reachable from THREE independent, unsynchronized callers —
        // connectSlot() at startup, reconnectClean() (itself called from both handleReturn()'s
        // hygiene path and WsLease's ws-timeout callback), and scheduleReconnect()'s scheduled
        // task. The `reconnecting` CAS below only de-dupes re-entrant scheduleReconnect() calls
        // while a delayed reconnect is pending — connect() itself clears it immediately on entry
        // (line below), so it gave zero protection against two of these paths racing into the
        // previous-capture/swap/abort sequence for the SAME slot at the same time (e.g. a lease's
        // normal return firing at the same instant its own ws-timeout also fires). Interleaved,
        // that race can orphan a just-created WsClient — assigned into `client`, then immediately
        // overwritten by the other thread before anyone captures it as `previous` to abort() it —
        // reproducing the exact orphaned-live-connection OOM leak the comment below describes as
        // already fixed for the single-caller case. Serializing the whole method per-slot closes
        // that gap; it never blocks on network I/O (connectOrFail() returns a future immediately),
        // so holding the lock here can't stall other slots or the dispatcher.
        synchronized void connect(int attempt) {
            if (!running.get()) return;
            reconnecting.set(false);
            resetReadyLatch();
            useCount = 0;
            connectedAtMs = System.currentTimeMillis();

            // Capture the outgoing client BEFORE it's replaced. Not closing it here was the
            // root cause of the OOM this pool used to cause: `client = WsClient.builder()...`
            // used to just overwrite the field, leaving the previous WebSocket connection open
            // at the OS/JDK level with its listener still registered. BetBoom never stops
            // pushing to a connection that's still technically alive, so that orphaned client's
            // unbounded inbox kept growing for the rest of the process's life — once per
            // reconnect (~10-30 per slot over 17h in production), each leak compounding.
            WsClient previous = client;

            client = WsClient.builder()
                    .url(withUuid(props.getUrl()))
                    .headers(props.getHeaders())
                    .subprotocol(props.getSubprotocol())
                    .connectTimeout(props.getConnectTimeout())
                    .initialBuffer(props.getInitialBuffer())
                    // Deliberately does NOT reset `failures` here — onOpen fires the moment the
                    // raw WS transport handshake completes, well before BetBoom's app-level HELLO.
                    // A real incident (24.09, "Access rejected" / "Closed before HELLO") showed
                    // BetBoom's gateway accept the transport upgrade and then immediately reject
                    // the connection at the app level on every single attempt — since onOpen still
                    // fired first every time, it zeroed the failure counter before scheduleReconnect
                    // could ever see more than 1, so backoff never escalated past its base delay
                    // (~300-500ms) despite the connection failing continuously for 5+ hours: ~2500
                    // reconnect attempts per slot per 10 minutes, non-stop, against an endpoint that
                    // was actively telling us "no". `failures` now only resets on a genuine
                    // HELLO-confirmed success (the whenComplete branch below), so a sustained
                    // rejection correctly backs off toward backoffMax instead of hammering forever.
                    .onOpen(() -> connected = true)
                    .onHello(bytes -> {
                        try { client.clearInbox(); } catch (Exception ignore) {}
                        if (readyOnce.getCount() > 0) {
                            readyOnce.countDown();
                            free.offer(id);
                        }
                    })
                    .onClose((code, reason) -> scheduleReconnect(
                            "closed code=" + code + " reason=" + reason))
                    .onError(err -> scheduleReconnect(err.toString()))
                    .onFrameDropped(framesDropped::increment)
                    .build();

            if (previous != null) {
                try { previous.abort(); } catch (Exception ignore) {}
            }

            Duration helloTimeout = props.getConnectTimeout().plusSeconds(5);
            client.connectOrFail(helloTimeout)
                    .whenComplete((v, err) -> {
                        if (err != null) scheduleReconnect(err.toString());
                        else { connected = true; failures = 0; }
                    });
        }

        boolean awaitReady(long t, TimeUnit u) throws InterruptedException {
            if (readyOnce.getCount() == 0) return true;
            return readyOnce.await(t, u);
        }

        void scheduleReconnect(String cause) {
            if (!reconnecting.compareAndSet(false, true)) return; // only one reconnect at a time
            connected = false;
            failures++;
            failureReconnects.increment();
            free.remove(id);
            long base = props.getBackoffBase().toMillis(), max = props.getBackoffMax().toMillis();
            long backoff    = Math.min(base * (1L << Math.min(6, failures)), max);
            long jitter     = ThreadLocalRandom.current().nextLong(backoff / 3 + 1);
            long slotOffset = id * 500L; // stagger slots so they don't all reconnect simultaneously
            long delay      = backoff / 2 + jitter + slotOffset;
            if (!running.get()) {
                reconnecting.set(false);
                return;
            }
            // First few consecutive failures are WARN-worthy; a slot that's been flapping for a
            // while has already said its piece — keep logging it, just quieter, so a genuinely
            // stuck connection is still visible via WsPoolHealthLogger's summary, not per-attempt spam.
            String msg = "Pool: reconnecting slot #{} (failures={}, delay={} ms, cause={})";
            if (failures <= RECONNECT_WARN_THRESHOLD) log.warn(msg, id, failures, delay, cause);
            else log.debug(msg, id, failures, delay, cause);
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
