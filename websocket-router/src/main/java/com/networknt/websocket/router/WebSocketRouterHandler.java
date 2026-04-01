package com.networknt.websocket.router;

import com.networknt.client.Http2Client;
import com.networknt.cluster.Cluster;
import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.router.RouterConfig;
import com.networknt.service.SingletonServiceFactory;
import com.networknt.websocket.client.WsAttributes;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.PathMatcher;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.protocol.Handshake;
import io.undertow.websockets.core.protocol.version07.Hybi07Handshake;
import io.undertow.websockets.core.protocol.version08.Hybi08Handshake;
import io.undertow.websockets.core.protocol.version13.Hybi13Handshake;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket router handler that proxies WebSocket connections from the frontend
 * (client) to the backend service. Uses JDK HttpClient for the backend WebSocket
 * connection to support TLS 1.3.
 */
public class WebSocketRouterHandler implements MiddlewareHandler, WebSocketConnectionCallback {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketRouterHandler.class);
    private static final Cluster CLUSTER = SingletonServiceFactory.getBean(Cluster.class);
    private static final RouterConfig CONFIG = RouterConfig.load();
    private static final WebSocketRouterConfig WS_CONFIG = WebSocketRouterConfig.load();
    private static final Map<String, WebSocket> BACKEND_CHANNELS = new ConcurrentHashMap<>();
    private static final PathMatcher<String> pathMatcher = new PathMatcher<>();

    private volatile HttpHandler next;

    static {
        if (WS_CONFIG.getPathPrefixService() != null) {
            for (Map.Entry<String, String> entry : WS_CONFIG.getPathPrefixService().entrySet()) {
                pathMatcher.addPrefixPath(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Default constructor for WebSocketRouterHandler.
     */
    public WebSocketRouterHandler() {
        // Initialize config or logger if needed
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        // Determine if this request is targeted for the WebSocket router
        boolean isWsRequest = false;
        
        // Check for service_id header
        if (exchange.getRequestHeaders().contains("service_id")) {
            isWsRequest = true;
        } else {
            // Check path mapping
            String path = exchange.getRequestPath();
             PathMatcher.PathMatch<String> match = pathMatcher.match(path);
             if (match.getValue() != null) {
                 isWsRequest = true;
             }
        }

        if (isWsRequest) {
            // Delegate to Undertow's WebSocketProtocolHandshakeHandler
            // which handles the upgrade and calls our onConnect callback
            Collection<String> protocolHeaders = exchange.getRequestHeaders().get("Sec-WebSocket-Protocol");
            if (protocolHeaders != null && !protocolHeaders.isEmpty()) {
                Set<String> subprotocols = new LinkedHashSet<>();
                for (String headerValue : protocolHeaders) {
                    if (headerValue != null) {
                        for (String token : headerValue.split(",")) {
                            String trimmed = token.trim();
                            if (!trimmed.isEmpty()) {
                                subprotocols.add(trimmed);
                            }
                        }
                    }
                }
                if (!subprotocols.isEmpty()) {
                    Collection<Handshake> handshakes = new ArrayList<>();
                    handshakes.add(new Hybi13Handshake(subprotocols, true));
                    handshakes.add(new Hybi08Handshake(subprotocols, true));
                    handshakes.add(new Hybi07Handshake(subprotocols, true));
                    new WebSocketProtocolHandshakeHandler(handshakes, this).handleRequest(exchange);
                } else {
                    new WebSocketProtocolHandshakeHandler(this).handleRequest(exchange);
                }
            } else {
                new WebSocketProtocolHandshakeHandler(this).handleRequest(exchange);
            }
        } else {
            // Not a websocket request for us, pass to next handler
            Handler.next(exchange, next);
        }
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
        return WS_CONFIG.isEnabled();
    }
    
    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        String serviceId = exchange.getRequestHeader("service_id");
        if(serviceId == null) {
            String requestURI = exchange.getRequestURI();
            String path = requestURI;
            int questionMarkIndex = requestURI.indexOf('?');
            if (questionMarkIndex != -1) {
                path = requestURI.substring(0, questionMarkIndex);
            }
            PathMatcher.PathMatch<String> match = pathMatcher.match(path);
            if (match.getValue() != null) {
                serviceId = match.getValue();
            }
        }
        
        if (serviceId != null) {
            // Discover the backend URL using "https" as the service discovery protocol,
            // since services register with https. Then derive ws/wss from the scheme.
            final var downstreamUrl = CLUSTER.serviceToUrl("https", serviceId, null, null);
            if (LOG.isTraceEnabled()) LOG.trace("Discovered downstreamUrl: {} for serviceId: {}", downstreamUrl, serviceId);
            if (downstreamUrl != null) {
                String channelId = exchange.getRequestHeader(WsAttributes.CHANNEL_GROUP_ID);
                if (channelId == null) {
                    channelId = java.util.UUID.randomUUID().toString();
                }
                
                if (LOG.isTraceEnabled()) LOG.trace("channelId = {}", channelId);
                
                // Convert http/https scheme to ws/wss for WebSocket connection
                String wsBaseUrl;
                if (downstreamUrl.startsWith("https://")) {
                    wsBaseUrl = "wss://" + downstreamUrl.substring("https://".length());
                } else if (downstreamUrl.startsWith("http://")) {
                    wsBaseUrl = "ws://" + downstreamUrl.substring("http://".length());
                } else {
                    wsBaseUrl = downstreamUrl;
                }
                final var targetUri = wsBaseUrl + exchange.getRequestURI();
                if (LOG.isDebugEnabled()) LOG.debug("Connecting to backend WebSocket: {}", targetUri);

                // Set the frontend receive listener to forward messages to the backend
                channel.getReceiveSetter().set(new JdkProxyReceiveListener(BACKEND_CHANNELS, channelId));
                
                try {
                    // Build JDK HttpClient with SSL support
                    HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
                    if (targetUri.startsWith("wss://")) {
                        SSLContext sslContext = Http2Client.createSSLContext();
                        if (sslContext != null) {
                            httpClientBuilder.sslContext(sslContext);
                        }
                    }
                    HttpClient httpClient = httpClientBuilder.build();

                    // Build the WebSocket connection with authorization header if present
                    java.net.http.WebSocket.Builder wsBuilder = httpClient.newWebSocketBuilder();
                    String authorization = exchange.getRequestHeader("Authorization");
                    if (authorization != null && !authorization.isBlank()) {
                        wsBuilder.header("Authorization", authorization);
                    }
                    // Forward subprotocols if present
                    String subprotocolHeader = exchange.getRequestHeader("Sec-WebSocket-Protocol");
                    if (subprotocolHeader != null && !subprotocolHeader.isBlank()) {
                        String[] subprotocols = subprotocolHeader.split(",");
                        for (String sp : subprotocols) {
                            String trimmed = sp.trim();
                            if (!trimmed.isEmpty()) {
                                wsBuilder.subprotocols(trimmed);
                                break; // JDK WebSocket only supports one subprotocol at a time
                            }
                        }
                    }

                    // Connect to the backend using JDK WebSocket client
                    final String finalChannelId = channelId;
                    WebSocket backendWs = wsBuilder
                            .buildAsync(new URI(targetUri), new JdkBackendWebSocketListener(channel, channelId))
                            .join();

                    // Store the backend connection
                    BACKEND_CHANNELS.put(channelId, backendWs);
                    if (LOG.isDebugEnabled()) LOG.debug("Backend WebSocket connected for channelId: {}", channelId);

                    // Set up close listener on the frontend channel to clean up
                    channel.addCloseTask(ch -> {
                        if (LOG.isDebugEnabled()) LOG.debug("Frontend channel closed, cleaning up channelId: {}", finalChannelId);
                        WebSocket removed = BACKEND_CHANNELS.remove(finalChannelId);
                        if (removed != null && !removed.isOutputClosed()) {
                            removed.sendClose(WebSocket.NORMAL_CLOSURE, "Frontend closed");
                        }
                    });

                    channel.resumeReceives();

                } catch (Exception e) {
                    LOG.error("Failed to create backend WebSocket connection to: {}", targetUri, e);
                    BACKEND_CHANNELS.remove(channelId);
                    try {
                        channel.sendClose();
                    } catch (IOException ex) {
                        LOG.error("Failed to close frontend channel", ex);
                    }
                }
            } else {
                LOG.error("Could not find downstream URL for serviceId: {}", serviceId);
                try {
                    channel.sendClose();
                } catch (IOException e) {
                    LOG.error("Failed to close channel", e);
                }
            }
        }
    }
}
