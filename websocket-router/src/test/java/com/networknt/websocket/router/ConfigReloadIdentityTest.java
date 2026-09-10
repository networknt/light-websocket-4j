package com.networknt.websocket.router;

import com.networknt.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigReloadIdentityTest {
    @Test
    void reusesConfigUntilCacheIsCleared() {
        WebSocketRouterConfig first = WebSocketRouterConfig.load();
        assertSame(first, WebSocketRouterConfig.load());
        assertSame(Config.getInstance().getJsonMapConfig(WebSocketRouterConfig.CONFIG_NAME),
                first.getMappedConfig());

        Config.getInstance().clearConfigCache(WebSocketRouterConfig.CONFIG_NAME);
        WebSocketRouterConfig reloaded = WebSocketRouterConfig.load();
        assertNotSame(first, reloaded);
        assertSame(reloaded, WebSocketRouterConfig.load());
        assertSame(Config.getInstance().getJsonMapConfig(WebSocketRouterConfig.CONFIG_NAME),
                reloaded.getMappedConfig());
    }

    @Test
    void onlyDefaultConfigNameIsCached() {
        WebSocketRouterConfig defaultConfig = WebSocketRouterConfig.load();
        WebSocketRouterConfig namedConfig = WebSocketRouterConfig.load("named-config");
        assertFalse(namedConfig.isEnabled());
        assertNotSame(defaultConfig, namedConfig);
        assertNotSame(namedConfig, WebSocketRouterConfig.load("named-config"));
        assertSame(defaultConfig, WebSocketRouterConfig.load());
    }
}
