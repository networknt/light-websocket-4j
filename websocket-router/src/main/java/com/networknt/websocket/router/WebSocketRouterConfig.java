package com.networknt.websocket.router;

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

/**
 * Config class for websocket router.
 *
 */
@ConfigSchema(
        configKey = "websocket-router",
        configName = "websocket-router",
        configDescription = "Light websocket router configuration",
        outputFormats = {OutputFormat.JSON_SCHEMA, OutputFormat.YAML, OutputFormat.CLOUD}
)
public class WebSocketRouterConfig {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketRouterConfig.class);
    public static final String CONFIG_NAME = "websocket-router";
    private static final String PATH_PREFIX_SERVICE = "pathPrefixService";

    @MapField(
            configFieldName = PATH_PREFIX_SERVICE,
            externalizedKeyName = PATH_PREFIX_SERVICE,
            description = "Map of path prefix to serviceId for routing purposes when service_id header is missing.",
            valueType = String.class
    )
    Map<String, String> pathPrefixService;

    @BooleanField(
            configFieldName = "enabled",
            externalizedKeyName = "enabled",
            description = "Enable WebSocket Router Handler",
            defaultValue = "true"
    )
    boolean enabled;

    private Map<String, Object> mappedConfig;
    private static volatile WebSocketRouterConfig instance;

    private WebSocketRouterConfig() {
        this(CONFIG_NAME);
    }

    private WebSocketRouterConfig(String configName) {
        mappedConfig = Config.getInstance().getJsonMapConfigNoCache(configName);
        setConfigData();
    }

    public static WebSocketRouterConfig load() {
        return load(CONFIG_NAME);
    }

    public static WebSocketRouterConfig load(String configName) {
        WebSocketRouterConfig config = instance;
        if (config == null || config.getMappedConfig() != Config.getInstance().getJsonMapConfig(configName)) {
            synchronized (WebSocketRouterConfig.class) {
                config = instance;
                if (config == null || config.getMappedConfig() != Config.getInstance().getJsonMapConfig(configName)) {
                    config = new WebSocketRouterConfig(configName);
                    instance = config;
                    // Register the module with the new config
                    ModuleRegistry.registerModule(configName, WebSocketRouterConfig.class.getName(), Config.getNoneDecryptedInstance().getJsonMapConfig(configName), null);
                }
            }
        }
        return config;
    }

    public Map<String, Object> getMappedConfig() {
        return mappedConfig;
    }

    public Map<String, String> getPathPrefixService() {
        return pathPrefixService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void setConfigData() {
        if (mappedConfig != null) {
            Object object = mappedConfig.get("enabled");
            if(object != null) enabled = Config.loadBooleanValue("enabled", object);
        }
        setPathPrefixService();
    }

    public void setPathPrefixService() {
        pathPrefixService = new HashMap<>();
        if (mappedConfig != null && mappedConfig.get(PATH_PREFIX_SERVICE) != null) {
            if (mappedConfig.get(PATH_PREFIX_SERVICE) instanceof Map) {
                pathPrefixService = (Map<String, String>)mappedConfig.get(PATH_PREFIX_SERVICE);
            } else if (mappedConfig.get(PATH_PREFIX_SERVICE) instanceof String) {
                String s = (String)mappedConfig.get(PATH_PREFIX_SERVICE);
                s = s.trim();
                if(logger.isTraceEnabled()) logger.trace("s = " + s);
                if(s.startsWith("{")) {
                    // json map
                    try {
                        pathPrefixService = Config.getInstance().getMapper().readValue(s, Map.class);
                    } catch (IOException e) {
                        logger.error("IOException:", e);
                    }
                } else {
                    Map<String, String> map = new LinkedHashMap<>();
                    for(String keyValue : s.split(" *& *")) {
                        String[] pairs = keyValue.split(" *= *", 2);
                        map.put(pairs[0], pairs[1]);
                    }
                    pathPrefixService = map;
                }
            } else {
                logger.error("pathPrefixService is the wrong type. Only JSON map or YAML map is supported.");
            }
        }
    }
}
