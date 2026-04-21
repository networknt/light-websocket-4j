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
            String downstreamHost = discoverDownstreamHost(downstreamService);
            if(downstreamHost == null || downstreamHost.isBlank()) {
                LOG.warn("Failed to discover downstream host from service entry");
                WebSockets.sendClose(CloseMessage.UNEXPECTED_ERROR, "Failed to discover downstream host from service entry", channel, null);
                return;
            }
            LOG.trace("Discovered downstream host {} for service {}", downstreamHost, downstreamService.serviceId());

            // start connecting to downstream server
            String wsURI = resolveWebSocketURI(downstreamHost, exchange);
            String pairId = UUID.randomUUID().toString();
            if(startDownstreamConnection(wsURI, exchange, pairId, channel) == null) {
                LOG.warn("Failed to initiate connection to downstream server at {}", wsURI);
                WebSockets.sendClose(CloseMessage.UNEXPECTED_ERROR, "Failed to initiate connection to downstream server", channel, null);
                return;
            }
            LOG.trace("Starting connection to downstream server at {}", wsURI);
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
        String requestURI = exchange.getRequestURI();
        int questionMarkIndex = requestURI.indexOf('?');
        String path = questionMarkIndex != -1 ? requestURI.substring(0, questionMarkIndex) : requestURI;
        return pathMatcher.match(path).getValue();
    }

    private String discoverDownstreamHost(DiscoverableHost downstreamService) {
        String serviceId = downstreamService.serviceId();
        if(serviceId == null || serviceId.isBlank()) {
            LOG.error("Downstream service entry's serviceId cannot be null or blank");
            return null;
        }

        String protocol = downstreamService.protocol() != null && !downstreamService.protocol().isEmpty() ?
                downstreamService.protocol() :
                config.getDefaultProtocol();
        String envTag = downstreamService.envTag() != null && !downstreamService.envTag().isEmpty() ?
                downstreamService.envTag() :
                config.getDefaultEnvTag();

        return cluster.serviceToUrl(protocol, serviceId, null, envTag);
    }

    private String resolveWebSocketURI(String downstreamHost, WebSocketHttpExchange exchange) {
        String wsBaseURL = downstreamHost.startsWith("https://") ?
                "wss://" + downstreamHost.substring("https://".length()) :
                "ws://" + downstreamHost.substring("http://".length());
        String wsTargetURI = wsBaseURL + exchange.getRequestURI();
        LOG.trace("WebSocket URI resolved to {}", wsTargetURI);
        return wsTargetURI;
    }

    private CompletableFuture<WebSocket> startDownstreamConnection(String wsURI, WebSocketHttpExchange exchange, String pairId, WebSocketChannel upstreamChannel) {
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
            return wsBuilder.buildAsync(new URI(wsURI), new DownstreamReceiveListener(pairId, upstreamChannel))
                    .whenComplete((downstream, throwable) -> {
                        if(throwable != null) {
                            LOG.error("Failed to connect to downstream server at {}", wsURI, throwable);
                            WebSockets.sendClose(CloseMessage.UNEXPECTED_ERROR, "Failed to connect to downstream server", upstreamChannel, null);
                            return;
                        }

                        upstreamChannel.getReceiveSetter().set(new UpstreamReceiveListener(pairId, downstream));
                        upstreamChannel.resumeReceives();
                        LOG.trace("Established pair {} for {}", pairId, exchange.getRequestURI());
                    });
        } catch(Exception e) {
            LOG.error("Failed to create downstream connection builder for {}", wsURI, e);
            return null;
        }
    }
}
