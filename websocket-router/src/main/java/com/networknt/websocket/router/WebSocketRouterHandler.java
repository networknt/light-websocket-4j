package com.networknt.websocket.router;

import com.networknt.client.Http2Client;
import com.networknt.cluster.Cluster;
import com.networknt.cluster.DiscoverableHost;
import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.service.SingletonServiceFactory;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;
import io.undertow.util.PathMatcher;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.core.protocol.Handshake;
import io.undertow.websockets.core.protocol.version07.Hybi07Handshake;
import io.undertow.websockets.core.protocol.version08.Hybi08Handshake;
import io.undertow.websockets.core.protocol.version13.Hybi13Handshake;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * WebSocket router handler that proxies WebSocket connections from the frontend
 * (client) to the backend service. Uses JDK HttpClient for the backend WebSocket
 * connection to support TLS 1.3.
 */
public class WebSocketRouterHandler implements MiddlewareHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketRouterHandler.class);

    private final WebSocketRouterConfig config = WebSocketRouterConfig.load();
    private final Cluster cluster = SingletonServiceFactory.getBean(Cluster.class);
    private final PathMatcher<DiscoverableHost> pathMatcher;
    private final WebSocketConnectionCallback wsHandshakeCallback;
    private final HttpHandler wsHandshakeNext;
    private final HttpClient httpClient;

    private volatile HttpHandler next;

    public WebSocketRouterHandler() {
        // build path prefix mappings
        pathMatcher = new PathMatcher<>();
        if (config.getPathPrefixService() != null) {
            for (Map.Entry<String, DiscoverableHost> entry : config.getPathPrefixService().entrySet()) {
                pathMatcher.addPrefixPath(entry.getKey(), entry.getValue());
            }
        }

        // build ws handshake connection callback
        wsHandshakeCallback = (exchange, channel) -> {
            // get service details
            DiscoverableHost downstreamService = getDownstreamService(exchange);
            if(downstreamService == null) {
                LOG.warn("No downstream service entry found for request URI: {}", exchange.getRequestURI());
                WebSockets.sendClose(CloseMessage.MSG_VIOLATES_POLICY, "No downstream service entry found for this URI", channel, null);
                return;
            }
            LOG.trace("Found downstream service entry for request URI: {}", exchange.getRequestURI());

            // discover downstream host
            String downstreamHost = cluster.serviceToUrl(downstreamService.protocol(), downstreamService.serviceId(), null, downstreamService.envTag());
            if(downstreamHost == null || downstreamHost.isBlank()) {
                LOG.warn("Failed to discover downstream host from service entry");
                WebSockets.sendClose(CloseMessage.UNEXPECTED_ERROR, "Failed to discover downstream host from service entry", channel, null);
                return;
            }
            LOG.trace("Discovered downstream host {} for service {}", downstreamHost, downstreamService.serviceId());

            // start connecting to downstream server
            String wsURL = resolveWebSocketURL(downstreamHost, exchange);
            String pairId = UUID.randomUUID().toString();
            if(startDownstreamConnection(wsURL, exchange, pairId, channel) == null) {
                LOG.warn("Failed to initiate connection to downstream server at {}", wsURL);
                WebSockets.sendClose(CloseMessage.UNEXPECTED_ERROR, "Failed to initiate connection to downstream server", channel, null);
                return;
            }
            LOG.trace("Starting connection to downstream server at {}", wsURL);
        };

        // build ws handshake next handler
        wsHandshakeNext = exchange -> Handler.next(exchange, next);

        // build http client
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
        try {
            SSLContext sslContext = Http2Client.createSSLContext();
            if(sslContext != null) {
                httpClientBuilder.sslContext(sslContext);
            } else {
                LOG.warn("SSL context is null. Secure downstream connections are not available");
            }
        } catch(Exception e) {
            LOG.warn("Failed to create SSLContext. Secure downstream connections are not available", e);
        }
        httpClient = httpClientBuilder.build();

        LOG.info("WebSocketRouterHandler loaded");
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        LOG.trace("Start WebSocketRouterHandler for {}", exchange.getRequestPath());

        Set<String> protocols = getProtocols(exchange);
        if(!protocols.isEmpty()) {
            exchange.getRequestHeaders().add(HttpString.tryFromString("X-Processed-Protocols"), String.join(",", protocols));
            Collection<Handshake> handshakes = new ArrayList<>();
            handshakes.add(new Hybi13Handshake(protocols, true));
            handshakes.add(new Hybi08Handshake(protocols, true));
            handshakes.add(new Hybi07Handshake(protocols, true));
            new WebSocketProtocolHandshakeHandler(handshakes, wsHandshakeCallback, wsHandshakeNext).handleRequest(exchange);
        } else {
            new WebSocketProtocolHandshakeHandler(wsHandshakeCallback, wsHandshakeNext).handleRequest(exchange);
        }

        LOG.trace("End WebSocketRouterHandler for {}", exchange.getRequestPath());
    }

    @Override
    public HttpHandler getNext() {
        return next;
    }

    @Override
    public MiddlewareHandler setNext(HttpHandler next) {
        Handlers.handlerNotNull(next);
        this.next = next;
        return this;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    private Set<String> getProtocols(HttpServerExchange exchange) {
        Set<String> protocols = new LinkedHashSet<>();

        HeaderValues protocolHeader = exchange.getRequestHeaders().get("Sec-WebSocket-Protocol");
        if(protocolHeader != null && !protocolHeader.isEmpty()) {
            for(String headerValue : protocolHeader) {
                String[] tokens = headerValue.split("\\s*,\\s*");
                for(String protocol : tokens) {
                    if(!protocol.isBlank()) {
                        protocols.add(protocol);
                    }
                }
            }
            LOG.trace("{} protocol(s) found on request {}", protocols.size(), exchange.getRequestPath());
        }

        return protocols;
    }

    private DiscoverableHost getDownstreamService(WebSocketHttpExchange exchange) {
        String protocol = config.getDefaultProtocol();
        String envTag = config.getDefaultEnvTag();
        String serviceId = null;

        // service id priority 1: header
        String headerValue = firstNonBlankHeader(exchange, "Service-Id", "service_id", "serviceId");
        if(headerValue != null) {
            serviceId = headerValue;
            LOG.trace("Found service id {} in header", serviceId);
        } else if(exchange.getRequestHeaders().containsKey("Service-Id")
                || exchange.getRequestHeaders().containsKey("service_id")
                || exchange.getRequestHeaders().containsKey("serviceId")) {
            LOG.warn("Request contains a service id header, but its value is null or empty");
        }

        // service id priority 2: query parameter
        if(serviceId == null) {
            serviceId = firstNonBlankParameter(exchange, "service_id", "serviceId");
            if(serviceId != null) {
                LOG.trace("Found service id {} in query parameter", serviceId);
            } else if(exchange.getRequestParameters().containsKey("service_id")
                    || exchange.getRequestParameters().containsKey("serviceId")) {
                LOG.warn("Query string contains a service id parameter, but its value is null or empty");
            }
        }

        // service id priority 3: prefix path
        if(serviceId == null) {
            String requestURI = exchange.getRequestURI();
            int questionMarkIndex = requestURI.indexOf('?');
            String path = questionMarkIndex != -1 ? requestURI.substring(0, questionMarkIndex) : requestURI;

            DiscoverableHost pathPrefixDiscovery = pathMatcher.match(path).getValue();
            if(pathPrefixDiscovery != null) {
                serviceId = pathPrefixDiscovery.serviceId();
                LOG.trace("Found service id {} in path prefix service", serviceId);

                protocol = pathPrefixDiscovery.protocol() != null && !pathPrefixDiscovery.protocol().isEmpty() ?
                        pathPrefixDiscovery.protocol() : protocol;
                envTag = pathPrefixDiscovery.envTag() != null && !pathPrefixDiscovery.envTag().isEmpty() ?
                        pathPrefixDiscovery.envTag() : envTag;
            }
        }

        if(serviceId != null) {
            String requestedProtocol = firstNonBlankParameter(exchange, "protocol");
            if(requestedProtocol != null) {
                protocol = requestedProtocol;
            }
            String requestedEnvTag = firstNonBlankParameter(exchange, "env_tag", "envTag");
            if(requestedEnvTag != null) {
                envTag = requestedEnvTag;
            }

            LOG.trace("DiscoverableHost: protocol - {}, service id - {}, env tag - {}", protocol, serviceId, envTag);
            return new DiscoverableHost(protocol, serviceId, envTag);
        } else {
            LOG.warn("Checked header, query string, and prefix path for service id but found none");
            return null;
        }
    }

    private String resolveWebSocketURL(String downstreamHost, WebSocketHttpExchange exchange) {
        String wsBaseURL = downstreamHost.startsWith("https://") ?
                "wss://" + downstreamHost.substring("https://".length()) :
                "ws://" + downstreamHost.substring("http://".length());

        String wsTargetURL;
        if(exchange.getQueryString().length() > 0) {
            String[] queryParams = exchange.getQueryString().split("&");

            StringBuilder cleanedQueryString = new StringBuilder("?");
            for(String s : queryParams) {
                String parameterName = s;
                int equalsIndex = s.indexOf('=');
                if(equalsIndex >= 0) {
                    parameterName = s.substring(0, equalsIndex);
                }
                if(isRouterParameter(parameterName)) {
                    continue;
                }
                if(cleanedQueryString.length() != 1) {
                    cleanedQueryString.append("&");
                }
                cleanedQueryString.append(s);
            }

            String requestPath = exchange.getRequestURI().substring(0,  exchange.getRequestURI().indexOf('?'));
            wsTargetURL = cleanedQueryString.length() > 1 ?
                    wsBaseURL + requestPath + cleanedQueryString :
                    wsBaseURL + requestPath;
        } else {
            wsTargetURL = wsBaseURL + exchange.getRequestURI();
        }

        LOG.trace("WebSocket URL resolved to {}", wsTargetURL);
        return wsTargetURL;
    }

    private String firstNonBlankHeader(WebSocketHttpExchange exchange, String... headerNames) {
        for(String headerName : headerNames) {
            String headerValue = exchange.getRequestHeader(headerName);
            if(headerValue != null && !headerValue.isBlank()) {
                return headerValue;
            }
        }
        return null;
    }

    private String firstNonBlankParameter(WebSocketHttpExchange exchange, String... parameterNames) {
        Map<String, List<String>> parameters = exchange.getRequestParameters();
        for(String parameterName : parameterNames) {
            List<String> values = parameters.get(parameterName);
            if(values == null) {
                continue;
            }
            for(String value : values) {
                if(value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private boolean isRouterParameter(String parameterName) {
        return "protocol".equals(parameterName)
                || "service_id".equals(parameterName)
                || "serviceId".equals(parameterName)
                || "env_tag".equals(parameterName)
                || "envTag".equals(parameterName);
    }

    private CompletableFuture<WebSocket> startDownstreamConnection(String wsURL, WebSocketHttpExchange exchange, String pairId, WebSocketChannel upstreamChannel) {
        WebSocket.Builder wsBuilder = httpClient.newWebSocketBuilder();

        String authHeader = exchange.getRequestHeader("Authorization");
        if (authHeader != null && !authHeader.isBlank()) {
            wsBuilder.header("Authorization", authHeader);
        }

        String protocolHeader = exchange.getRequestHeader("X-Processed-Protocols");
        if (protocolHeader != null && !protocolHeader.isBlank()) {
            String[] protocols = protocolHeader.split(",");
            if(protocols.length == 1) {
                wsBuilder.subprotocols(protocols[0]);
            } else {
                String firstProtocol = protocols[0];
                String[] otherProtocols = new String[protocols.length - 1];
                System.arraycopy(protocols, 1, otherProtocols, 0, protocols.length - 1);
                wsBuilder.subprotocols(firstProtocol, otherProtocols);
            }
        }

        try {
            return wsBuilder.buildAsync(new URI(wsURL), new DownstreamReceiveListener(pairId, upstreamChannel))
                    .whenComplete((downstream, throwable) -> {
                        if(throwable != null) {
                            LOG.error("Failed to connect to downstream server at {}", wsURL, throwable);
                            WebSockets.sendClose(CloseMessage.UNEXPECTED_ERROR, "Failed to connect to downstream server", upstreamChannel, null);
                            return;
                        }

                        upstreamChannel.getReceiveSetter().set(new UpstreamReceiveListener(pairId, downstream));
                        upstreamChannel.resumeReceives();
                        LOG.trace("Established pair {} for {}", pairId, exchange.getRequestURI());
                    });
        } catch(Exception e) {
            LOG.error("Failed to create downstream connection builder for {}", wsURL, e);
            return null;
        }
    }
}
