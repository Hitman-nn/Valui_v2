package com.valui.parser.bookmaker.betboom.ws;

import proto.betboom.Envelope;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class WsClient {

    public static final class Builder {
        private String wsUrl;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String subprotocol;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private int initialBuffer = 64 * 1024;

        private Runnable onOpen;
        private Consumer<byte[]> onHello;
        private Consumer<byte[]> onBinary;
        private Consumer<String> onText;
        private BiConsumer<Integer, String> onClose;
        private Consumer<Throwable> onError;

        public Builder url(String u)          { this.wsUrl = Objects.requireNonNull(u); return this; }
        public Builder header(String k, String v) { if (k != null && v != null) headers.put(k, v); return this; }
        public Builder headers(Map<String, String> m) { if (m != null) headers.putAll(m); return this; }
        public Builder subprotocol(String p)  { this.subprotocol = p; return this; }
        public Builder connectTimeout(Duration d) { this.connectTimeout = d; return this; }
        public Builder initialBuffer(int b)   { this.initialBuffer = Math.max(4096, b); return this; }

        public Builder onOpen(Runnable r)                      { this.onOpen   = r; return this; }
        public Builder onHello(Consumer<byte[]> c)             { this.onHello  = c; return this; }
        public Builder onBinary(Consumer<byte[]> c)            { this.onBinary = c; return this; }
        public Builder onText(Consumer<String> c)              { this.onText   = c; return this; }
        public Builder onClose(BiConsumer<Integer, String> c)  { this.onClose  = c; return this; }
        public Builder onError(Consumer<Throwable> c)          { this.onError  = c; return this; }

        public WsClient build() { return new WsClient(this); }
    }

    public static Builder builder() { return new Builder(); }

    // ── instance fields ───────────────────────────────────────────────────────

    private final URI url;
    private final Map<String, String> headers;
    private final String subprotocol;
    private final Duration connectTimeout;
    private final int initialBuffer;

    private final Runnable onOpen;
    private final Consumer<byte[]> onHello;
    private final Consumer<byte[]> onBinary;
    private final Consumer<String> onText;
    private final BiConsumer<Integer, String> onClose;
    private final Consumer<Throwable> onError;

    private final HttpClient http;
    private volatile WebSocket ws;

    private final BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
    private final CompletableFuture<Void> helloGate = new CompletableFuture<>();

    private WsClient(Builder b) {
        this.url = URI.create(Objects.requireNonNull(b.wsUrl, "wsUrl"));
        Map<String, String> h = new LinkedHashMap<>(b.headers);
        h.putIfAbsent("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        this.headers = Collections.unmodifiableMap(h);
        this.subprotocol = b.subprotocol;
        this.connectTimeout = b.connectTimeout;
        this.initialBuffer = b.initialBuffer;
        this.onOpen = b.onOpen;
        this.onHello = b.onHello;
        this.onBinary = b.onBinary;
        this.onText = b.onText;
        this.onClose = b.onClose;
        this.onError = b.onError;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    // ── API ───────────────────────────────────────────────────────────────────

    public CompletableFuture<Void> connectOrFail(Duration helloTimeout) {
        WebSocket.Builder wb = http.newWebSocketBuilder();
        headers.forEach(wb::header);
        if (subprotocol != null && !subprotocol.isBlank()) wb.subprotocols(subprotocol);
        Listener listener = new Listener(initialBuffer);
        return wb.connectTimeout(connectTimeout)
                .buildAsync(url, listener)
                .thenAccept(w -> this.ws = w)
                .thenCompose(v -> helloGate.orTimeout(helloTimeout.toMillis(), TimeUnit.MILLISECONDS));
    }

    public int clearInbox() {
        int n = 0;
        while (inbox.poll() != null) n++;
        return n;
    }

    public CompletableFuture<Void> sendBinary(byte[] bytes) {
        Objects.requireNonNull(ws, "Not connected");
        return ws.sendBinary(ByteBuffer.wrap(bytes), true).thenAccept(v -> {});
    }

    public CompletableFuture<Void> sendText(String text) {
        Objects.requireNonNull(ws, "Not connected");
        return ws.sendText(Objects.requireNonNull(text), true).thenAccept(v -> {});
    }

    public byte[] awaitBinary(long timeoutMs) throws InterruptedException {
        return inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public byte[] awaitBinary(long timeout, TimeUnit unit) throws InterruptedException {
        return inbox.poll(timeout, unit);
    }

    public CompletableFuture<Void> closeNormal() {
        WebSocket w = this.ws;
        this.ws = null;
        if (w == null) return CompletableFuture.completedFuture(null);
        return w.sendClose(WebSocket.NORMAL_CLOSURE, "bye").thenAccept(v -> {});
    }

    public void abort() {
        WebSocket w = this.ws;
        this.ws = null;
        if (w != null) try { w.abort(); } catch (Throwable ignore) {}
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    private final class Listener implements WebSocket.Listener {
        private ByteBuffer acc;
        private boolean firstBinarySeen = false;

        Listener(int initial) { this.acc = ByteBuffer.allocate(initial); }

        @Override
        public void onOpen(WebSocket ws) {
            if (onOpen != null) try { onOpen.run(); } catch (Throwable ignore) {}
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            ensureCapacity(data.remaining());
            acc.put(data);
            if (last) {
                acc.flip();
                byte[] frame = new byte[acc.remaining()];
                acc.get(frame);
                acc.clear();

                if (!firstBinarySeen) {
                    firstBinarySeen = true;
                    if (isHello(frame)) {
                        if (onHello != null) try { onHello.accept(frame); } catch (Throwable ignore) {}
                        helloGate.complete(null);
                    } else {
                        helloGate.completeExceptionally(
                                new IllegalStateException("Expected HELLO as first binary frame"));
                        try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bad-hello").join(); }
                        catch (Throwable ignore) {}
                    }
                } else {
                    if (onBinary != null) try { onBinary.accept(frame); } catch (Throwable ignore) {}
                    inbox.offer(frame);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (last && onText != null) try { onText.accept(data.toString()); } catch (Throwable ignore) {}
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (!helloGate.isDone()) helloGate.completeExceptionally(
                    new IllegalStateException("Closed before HELLO"));
            if (onClose != null) try { onClose.accept(statusCode, reason); } catch (Throwable ignore) {}
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!helloGate.isDone()) helloGate.completeExceptionally(error);
            if (onError != null) try { onError.accept(error); } catch (Throwable ignore) {}
        }

        private void ensureCapacity(int add) {
            if (acc.remaining() >= add) return;
            int need = acc.position() + add, cap = acc.capacity(), ncap = cap;
            while (ncap < need) ncap <<= 1;
            ByteBuffer nb = ByteBuffer.allocate(ncap);
            acc.flip();
            nb.put(acc);
            acc = nb;
        }
    }

    private static boolean isHello(byte[] frame) {
        try { return Envelope.parseFrom(frame).hasResponseConnectOpen(); }
        catch (Exception e) { return false; }
    }
}
