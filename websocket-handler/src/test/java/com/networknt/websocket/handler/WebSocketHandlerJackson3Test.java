package com.networknt.websocket.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketHandlerJackson3Test {
    @Test
    void malformedJsonRetainsHandledEmptyHandlerMap() {
        WebSocketHandlerConfig config = assertDoesNotThrow(
                () -> WebSocketHandlerConfig.load("websocket-handler-malformed-json"));
        assertTrue(config.isEnabled());
        assertTrue(config.getPathPrefixHandlers().isEmpty());
    }
}
