package com.networknt.websocket.router;

import com.networknt.config.Config;
import com.networknt.config.schema.BooleanField;
import com.networknt.config.schema.ConfigSchema;
import com.networknt.config.schema.MapField;
import com.networknt.config.schema.OutputFormat;
import com.networknt.config.schema.StringField;
import com.networknt.server.ModuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
    public static final String DEFAULT_PROTOCOL = "defaultProtocol";
    public static final String DEFAULT_ENV_TAG = "defaultEnvTag";
    private static final String PATH_PREFIX_SERVICE = "pathPrefixService";

    @BooleanField(
            configFieldName = "enabled",
            externalizedKeyName = "enabled",
            description = "Enable WebSocket Router Handler",
            defaultValue = "true"
    )
    boolean enabled;

    @StringField(
            configFieldName = DEFAULT_PROTOCOL,
            externalizedKeyName = DEFAULT_PROTOCOL,
            description = "Default downstream protocol when not specified in the request. It can be http or https.",
            defaultValue = "http"
    )
    String defaultProtocol;

    @StringField(
            configFieldName = DEFAULT_ENV_TAG,
            externalizedKeyName = DEFAULT_ENV_TAG,
            description = "Default downstream envTag when not specified in the request. Like dev/sit/stg/prd etc."
    )
    String defaultEnvTag;

    @MapField(
            configFieldName = PATH_PREFIX_SERVICE,
            externalizedKeyName = PATH_PREFIX_SERVICE,
            description = "Map of path prefix to downstream host attributes for routing when service_id header is missing.",
            valueType = DiscoverableHost.class
    )
    Map<String, DiscoverableHost> pathPrefixService;

    public record DiscoverableHost(String protocol, String serviceId, String envTag) {
    }

    private final Map<String, Object> mappedConfig;
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

    public Map<String, DiscoverableHost> getPathPrefixService() {
        return pathPrefixService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDefaultProtocol() {
        return defaultProtocol;
    }

    public String getDefaultEnvTag() {
        return defaultEnvTag;
    }

    private void setConfigData() {
        if (mappedConfig != null) {
            Object object = mappedConfig.get("enabled");
            if(object != null) enabled = Config.loadBooleanValue("enabled", object);
            object = mappedConfig.get(DEFAULT_PROTOCOL);
            if(object != null) defaultProtocol = (String)object;
            object = mappedConfig.get(DEFAULT_ENV_TAG);
            if(object != null) defaultEnvTag = (String)object;
        }
        setPathPrefixService();
    }

    public void setPathPrefixService() {
        pathPrefixService = new HashMap<>();
        if (mappedConfig != null && mappedConfig.get(PATH_PREFIX_SERVICE) != null) {
            if (mappedConfig.get(PATH_PREFIX_SERVICE) instanceof Map) {
                pathPrefixService = normalizePathPrefixService((Map<?, ?>) mappedConfig.get(PATH_PREFIX_SERVICE));
            } else if (mappedConfig.get(PATH_PREFIX_SERVICE) instanceof String) {
                String s = (String)mappedConfig.get(PATH_PREFIX_SERVICE);
                s = s.trim();
                if(logger.isTraceEnabled()) logger.trace("s = {}", s);
                if(s.startsWith("{")) {
                    // json map
                    try {
                        Map<?, ?> parsed = Config.getInstance().getMapper().readValue(s, Map.class);
                        pathPrefixService = normalizePathPrefixService(parsed);
                    } catch (IOException e) {
                        logger.error("IOException:", e);
                    }
                } else {
                    Map<String, DiscoverableHost> map = new LinkedHashMap<>();
                    for(String keyValue : s.split(" *& *")) {
                        String[] pairs = keyValue.split(" *= *", 2);
                        if (pairs.length != 2) {
                            logger.error("Invalid pathPrefixService key/value segment: {}", keyValue);
                            continue;
                        }
                        map.put(pairs[0], new DiscoverableHost(defaultProtocol, pairs[1], defaultEnvTag));
                    }
                    pathPrefixService = map;
                }
            } else {
                logger.error("pathPrefixService is the wrong type. Only JSON map or YAML map is supported.");
            }
        }
    }

    private Map<String, DiscoverableHost> normalizePathPrefixService(Map<?, ?> configuredMap) {
        Map<String, DiscoverableHost> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : configuredMap.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            DiscoverableHost host = toDiscoverableHost(entry.getValue());
            if (host != null) {
                normalized.put(entry.getKey().toString(), host);
            }
        }
        return normalized;
    }

    private DiscoverableHost toDiscoverableHost(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof DiscoverableHost) {
            return (DiscoverableHost) rawValue;
        }
        if (rawValue instanceof String) {
            return new DiscoverableHost(defaultProtocol, (String) rawValue, defaultEnvTag);
        }
        if (rawValue instanceof Map<?, ?>) {
            Map<?, ?> hostMap = (Map<?, ?>) rawValue;
            String serviceId = firstNonBlank(hostMap.get("serviceId"), hostMap.get("host"));
            if (serviceId == null) {
                logger.error("Each pathPrefixService entry must define serviceId or host. entry={}", hostMap);
                return null;
            }
            String protocol = firstNonBlank(hostMap.get("protocol"), defaultProtocol);
            String envTag = firstNonBlank(hostMap.get("envTag"), defaultEnvTag);
            return new DiscoverableHost(protocol, serviceId, envTag);
        }
        logger.error("Unsupported pathPrefixService value type: {}", rawValue.getClass().getName());
        return null;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String s = Objects.toString(value, null);
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
