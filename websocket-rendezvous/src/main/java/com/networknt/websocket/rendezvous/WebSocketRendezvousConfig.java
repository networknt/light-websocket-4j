package com.networknt.websocket.rendezvous;

import com.networknt.config.Config;
import com.networknt.config.schema.ConfigSchema;
import com.networknt.config.schema.BooleanField;
import com.networknt.config.schema.StringField;
import com.networknt.config.schema.OutputFormat;
import com.networknt.server.ModuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Config class for websocket rendezvous.
 *
 */
@ConfigSchema(
        configKey = "websocket-rendezvous",
        configName = "websocket-rendezvous",
        configDescription = "Light websocket rendezvous configuration",
        outputFormats = {OutputFormat.JSON_SCHEMA, OutputFormat.YAML, OutputFormat.CLOUD}
)
public class WebSocketRendezvousConfig {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketRendezvousConfig.class);
    public static final String CONFIG_NAME = "websocket-rendezvous";
    private static final String BACKEND_PATH = "backendPath";

    @BooleanField(
            configFieldName = "enabled",
            externalizedKeyName = "enabled",
            description = "Enable WebSocket Rendezvous Handler",
            defaultValue = "true"
    )
    boolean enabled;

    @StringField(
            configFieldName = "backendPath",
            externalizedKeyName = "backendPath",
            description = "The path identifier for backend connections in the rendezvous handler.",
            defaultValue = "/connect"
    )
    String backendPath;

    private final Map<String, Object> mappedConfig;
    private static volatile WebSocketRendezvousConfig instance;

    private WebSocketRendezvousConfig() {
        this(CONFIG_NAME);
    }

    private WebSocketRendezvousConfig(String configName) {
        mappedConfig = Config.getInstance().getJsonMapConfigNoCache(configName);
        setConfigData();
    }

    public static WebSocketRendezvousConfig load() {
        return load(CONFIG_NAME);
    }

    public static WebSocketRendezvousConfig load(String configName) {
        WebSocketRendezvousConfig config = instance;
        if (config == null || config.getMappedConfig() != Config.getInstance().getJsonMapConfig(configName)) {
            synchronized (WebSocketRendezvousConfig.class) {
                config = instance;
                if (config == null || config.getMappedConfig() != Config.getInstance().getJsonMapConfig(configName)) {
                    config = new WebSocketRendezvousConfig(configName);
                    instance = config;
                    // Register the module with the new config
                    ModuleRegistry.registerModule(configName, WebSocketRendezvousConfig.class.getName(), Config.getNoneDecryptedInstance().getJsonMapConfig(configName), null);
                }
            }
        }
        return config;
    }

    public Map<String, Object> getMappedConfig() {
        return mappedConfig;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getBackendPath() {
        return backendPath;
    }

    private void setConfigData() {
        if (mappedConfig != null) {
            Object object = mappedConfig.get("enabled");
            if(object != null) enabled = Config.loadBooleanValue("enabled", object);
            
            Object backendPathObj = mappedConfig.get(BACKEND_PATH);
            if (backendPathObj != null) backendPath = (String) backendPathObj;
        }
    }
}
