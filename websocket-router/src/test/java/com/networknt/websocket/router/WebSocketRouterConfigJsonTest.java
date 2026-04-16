package com.networknt.websocket.router;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class WebSocketRouterConfigJsonTest {
    private static WebSocketRouterConfig config;

    @BeforeEach
    public void setUp() {
        config = WebSocketRouterConfig.load("websocket-router-json");
    }

    @Test
    public void testConfigData() {
        Assertions.assertTrue(config.isEnabled());
        Assertions.assertEquals("http", config.getDefaultProtocol());
        Assertions.assertNull(config.getDefaultEnvTag());

        Map<String, WebSocketRouterConfig.DiscoverableHost> pathPrefixServiceMap = config.getPathPrefixService();
        Assertions.assertNotNull(pathPrefixServiceMap);
        WebSocketRouterConfig.DiscoverableHost discoverableHost = pathPrefixServiceMap.get("/chat");
        Assertions.assertNotNull(discoverableHost);
        Assertions.assertEquals("com.networknt.llmchat-1.0.0", discoverableHost.serviceId());
        Assertions.assertEquals("http", discoverableHost.protocol());
        Assertions.assertEquals("dev", discoverableHost.envTag());
    }
}

