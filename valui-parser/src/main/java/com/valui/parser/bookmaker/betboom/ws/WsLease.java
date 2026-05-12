package com.valui.parser.bookmaker.betboom.ws;

import lombok.Getter;

import java.util.concurrent.CompletableFuture;

public final class WsLease implements AutoCloseable {

    @Getter private final int id;
    @Getter private final WsClient client;
    private final Runnable onReturn;
    private final Runnable onReconnect;
    private volatile boolean reconnectOnClose = false;

    WsLease(int id, WsClient client, Runnable onReturn, Runnable onReconnect) {
        this.id = id;
        this.client = client;
        this.onReturn = onReturn;
        this.onReconnect = onReconnect;
    }

    public CompletableFuture<Void> sendBinary(byte[] bytes) {
        return client.sendBinary(bytes);
    }

    /** Mark this lease so close() will reconnect the slot instead of returning it to the free pool. */
    public void markForReconnect() {
        this.reconnectOnClose = true;
    }

    @Override
    public void close() {
        if (reconnectOnClose) onReconnect.run();
        else onReturn.run();
    }
}
