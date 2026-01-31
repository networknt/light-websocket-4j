package com.networknt.websocket.handler;

import com.networknt.config.Config;
import com.networknt.config.schema.ConfigSchema;
import com.networknt.config.schema.MapField;
import com.networknt.config.schema.BooleanField;
import com.networknt.config.schema.OutputFormat;
import com.networknt.server.ModuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigSchema(
        configKey = "websocket-handler",
        configName = "websocket-handler",
        configDescription = "Light websocket handler configuration",
        outputFormats = {OutputFormat.JSON_SCHEMA, OutputFormat.YAML, OutputFormat.CLOUD}
)
public class WebSocketHandlerConfig {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandlerConfig.class);
    public static final String CONFIG_NAME = "websocket-handler";
    private static final String PATH_PREFIX_HANDLERS = "pathPrefixHandlers";

    @BooleanField(
            configFieldName = "enabled",
            externalizedKeyName = "enabled",
            description = "Enable WebSocket Handler",
            defaultValue = "true"
    )
    boolean enabled;

    @MapField(
            configFieldName = PATH_PREFIX_HANDLERS,
            externalizedKeyName = PATH_PREFIX_HANDLERS,
            description = "Map of path prefix to handler class names (must implement WebSocketApplicationHandler).",
            valueType = String.class
    )
    Map<String, String> pathPrefixHandlers;


    private final Map<String, Object> mappedConfig;
    private static volatile WebSocketHandlerConfig instance;

    private WebSocketHandlerConfig() {
        this(CONFIG_NAME);
    }

    private WebSocketHandlerConfig(String configName) {
        mappedConfig = Config.getInstance().getJsonMapConfigNoCache(configName);
        setConfigData();
    }

    public static WebSocketHandlerConfig load() {
        return load(CONFIG_NAME);
    }

    public static WebSocketHandlerConfig load(String configName) {
        WebSocketHandlerConfig config = instance;
        if (config == null || config.getMappedConfig() != Config.getInstance().getJsonMapConfig(configName)) {
            synchronized (WebSocketHandlerConfig.class) {
                config = instance;
                if (config == null || config.getMappedConfig() != Config.getInstance().getJsonMapConfig(configName)) {
                    config = new WebSocketHandlerConfig(configName);
                    instance = config;
                    ModuleRegistry.registerModule(configName, WebSocketHandlerConfig.class.getName(), Config.getNoneDecryptedInstance().getJsonMapConfig(configName), null);
                }
            }
        }
        return config;
    }

    public Map<String, Object> getMappedConfig() {
        return mappedConfig;
    }

    public Map<String, String> getPathPrefixHandlers() {
        return pathPrefixHandlers;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void setConfigData() {
        if (mappedConfig != null) {
            Object object = mappedConfig.get("enabled");
            if(object != null) enabled = Config.loadBooleanValue("enabled", object);
        }

        pathPrefixHandlers = new HashMap<>();
        if (mappedConfig != null && mappedConfig.get(PATH_PREFIX_HANDLERS) != null) {
            Object object = mappedConfig.get(PATH_PREFIX_HANDLERS);
            if (object instanceof Map) {
                pathPrefixHandlers = (Map<String, String>) object;
            } else if (object instanceof String) {
                String s = (String) object;
                s = s.trim();
                if (s.startsWith("{")) {
                    try {
                        pathPrefixHandlers = Config.getInstance().getMapper().readValue(s, Map.class);
                    } catch (IOException e) {
                        logger.error("IOException:", e);
                    }
                } else {
                    Map<String, String> map = new LinkedHashMap<>();
                    for (String keyValue : s.split(" *& *")) {
                        String[] pairs = keyValue.split(" *= *", 2);
                        map.put(pairs[0], pairs[1]);
                    }
                    pathPrefixHandlers = map;
                }
            }
        }
    }
}
