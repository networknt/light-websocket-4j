package com.networknt.websocket.router;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketRouterJackson3Test {
    @Test
    void malformedJsonRetainsHandledEmptyRoutingMap() {
        WebSocketRouterConfig config = assertDoesNotThrow(
                () -> WebSocketRouterConfig.load("websocket-router-malformed-json"));
        assertTrue(config.isEnabled());
        assertTrue(config.getPathPrefixService().isEmpty());
    }
}
