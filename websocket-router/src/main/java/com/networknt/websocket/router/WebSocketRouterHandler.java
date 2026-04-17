package com.networknt.websocket.router;

import com.networknt.client.Http2Client;
import com.networknt.cluster.Cluster;
import com.networknt.cluster.DiscoverableHost;
import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket router handler that proxies WebSocket connections from the frontend
 * (client) to the backend service. Uses JDK HttpClient for the backend WebSocket
 * connection to support TLS 1.3.
 */
public class WebSocketRouterHandler implements MiddlewareHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketRouterHandler.class);

    private final Cluster CLUSTER = SingletonServiceFactory.getBean(Cluster.class);
    private final PathMatcher<DiscoverableHost> PATH_MATCHER = new PathMatcher<>();
    private final WebSocketRouterConfig WS_CONFIG = WebSocketRouterConfig.load();
    private final WebSocketProtocolHandshakeHandler WS_HANDSHAKE_HANDLER;

    private volatile HttpHandler next;

    public WebSocketRouterHandler() {
        // build path prefix mappings
        if (WS_CONFIG.getPathPrefixService() != null) {
            for (Map.Entry<String, DiscoverableHost> entry : WS_CONFIG.getPathPrefixService().entrySet()) {
                PATH_MATCHER.addPrefixPath(entry.getKey(), entry.getValue());
            }
        }

        // build ws handshake handler
        WebSocketConnectionCallback wsCallback = (exchange, channel) -> {
            // get service name

            // discover downstream url

            // connect to downstream server

            // setup receive listeners for upstream and downstream
        };
        HttpHandler nextHandlerCallback = exchange -> Handler.next(exchange, next);
        WS_HANDSHAKE_HANDLER = new WebSocketProtocolHandshakeHandler(wsCallback, nextHandlerCallback);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        LOG.trace("Start WebSocketRouterHandler for {}", exchange.getRequestPath());
        WS_HANDSHAKE_HANDLER.handleRequest(exchange);
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
        return WS_CONFIG.isEnabled();
    }
}
