package com.networknt.websocket.handler;

import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;

/**
 * Interface that application handlers must implement to handle WebSocket connections.
 */
public interface WebSocketApplicationHandler {
    /**
     * Called when a new WebSocket connection is established.
     * The implementation should set up receive listeners on the channel.
     *
     * @param exchange The upgrade exchange
     * @param channel The established WebSocket channel
     */
    void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel);
}
