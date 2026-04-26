package com.valui.parser.bookmaker.betboom.ws;

import lombok.Getter;

import java.util.concurrent.CompletableFuture;

public final class WsLease implements AutoCloseable {

    @Getter private final int id;
    @Getter private final WsClient client;
    private final Runnable onClose;

    WsLease(int id, WsClient client, Runnable onClose) {
        this.id = id;
        this.client = client;
        this.onClose = onClose;
    }

    public CompletableFuture<Void> sendBinary(byte[] bytes) {
        return client.sendBinary(bytes);
    }

    @Override
    public void close() {
        onClose.run();
    }
}
