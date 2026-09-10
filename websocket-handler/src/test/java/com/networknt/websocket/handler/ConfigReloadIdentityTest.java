package com.networknt.websocket.handler;

import com.networknt.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigReloadIdentityTest {
    @Test
    void reusesConfigUntilCacheIsCleared() {
        WebSocketHandlerConfig first = WebSocketHandlerConfig.load();
        assertSame(first, WebSocketHandlerConfig.load());
        assertSame(Config.getInstance().getJsonMapConfig(WebSocketHandlerConfig.CONFIG_NAME),
                first.getMappedConfig());

        Config.getInstance().clearConfigCache(WebSocketHandlerConfig.CONFIG_NAME);
        WebSocketHandlerConfig reloaded = WebSocketHandlerConfig.load();
        assertNotSame(first, reloaded);
        assertSame(reloaded, WebSocketHandlerConfig.load());
        assertSame(Config.getInstance().getJsonMapConfig(WebSocketHandlerConfig.CONFIG_NAME),
                reloaded.getMappedConfig());
    }

    @Test
    void onlyDefaultConfigNameIsCached() {
        WebSocketHandlerConfig defaultConfig = WebSocketHandlerConfig.load();
        WebSocketHandlerConfig namedConfig = WebSocketHandlerConfig.load("named-config");
        assertFalse(namedConfig.isEnabled());
        assertNotSame(defaultConfig, namedConfig);
        assertNotSame(namedConfig, WebSocketHandlerConfig.load("named-config"));
        assertSame(defaultConfig, WebSocketHandlerConfig.load());
    }
}
