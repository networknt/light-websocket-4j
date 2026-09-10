package com.networknt.websocket.rendezvous;

import com.networknt.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigReloadIdentityTest {
    @Test
    void reusesConfigUntilCacheIsCleared() {
        WebSocketRendezvousConfig first = WebSocketRendezvousConfig.load();
        assertSame(first, WebSocketRendezvousConfig.load());
        assertSame(Config.getInstance().getJsonMapConfig(WebSocketRendezvousConfig.CONFIG_NAME),
                first.getMappedConfig());

        Config.getInstance().clearConfigCache(WebSocketRendezvousConfig.CONFIG_NAME);
        WebSocketRendezvousConfig reloaded = WebSocketRendezvousConfig.load();
        assertNotSame(first, reloaded);
        assertSame(reloaded, WebSocketRendezvousConfig.load());
        assertSame(Config.getInstance().getJsonMapConfig(WebSocketRendezvousConfig.CONFIG_NAME),
                reloaded.getMappedConfig());
    }

    @Test
    void onlyDefaultConfigNameIsCached() {
        WebSocketRendezvousConfig defaultConfig = WebSocketRendezvousConfig.load();
        WebSocketRendezvousConfig namedConfig = WebSocketRendezvousConfig.load("named-config");
        assertFalse(namedConfig.isEnabled());
        assertNotSame(defaultConfig, namedConfig);
        assertNotSame(namedConfig, WebSocketRendezvousConfig.load("named-config"));
        assertSame(defaultConfig, WebSocketRendezvousConfig.load());
    }
}
