package com.networknt.websocket.router;

import com.networknt.cluster.DiscoverableHost;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

class WebSocketRouterConfigMapTest {
    private static WebSocketRouterConfig config;
    @BeforeEach
    public void setUp() {
        config = WebSocketRouterConfig.load("websocket-router-map");
    }

    @Test
    public void testConfigData() {
        Assertions.assertTrue(config.isEnabled());
        Assertions.assertEquals("http", config.getDefaultProtocol());
        Assertions.assertNull(config.getDefaultEnvTag());

        Map<String, DiscoverableHost> pathPrefixServiceMap = config.getPathPrefixService();
        Assertions.assertNotNull(pathPrefixServiceMap);
        DiscoverableHost discoverableHost = pathPrefixServiceMap.get("/chat");
        Assertions.assertNotNull(discoverableHost);
        Assertions.assertEquals("com.networknt.llmchat-1.0.0", discoverableHost.serviceId());
        Assertions.assertEquals("http", discoverableHost.protocol());
        Assertions.assertEquals("dev", discoverableHost.envTag());
    }

}
