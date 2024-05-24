package com.networknt.websocket.client;

public interface WebSocketCallback {
    void complete(Throwable error);
}
