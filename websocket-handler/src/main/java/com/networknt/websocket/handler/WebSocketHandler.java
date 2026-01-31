package com.networknt.websocket.handler;

import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.server.ModuleRegistry;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.PathMatcher;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketHandler implements MiddlewareHandler, WebSocketConnectionCallback {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);
    private static final WebSocketHandlerConfig config = WebSocketHandlerConfig.load();
    private volatile HttpHandler next;
    
    // Cache instantiated handlers
    private final Map<String, WebSocketApplicationHandler> handlers = new ConcurrentHashMap<>();
    
    // Map prefix -> Handler Instance
    private final PathMatcher<WebSocketApplicationHandler> pathMatcher = new PathMatcher<>();

    public WebSocketHandler() {
        if (config.getPathPrefixHandlers() != null) {
            for (Map.Entry<String, String> entry : config.getPathPrefixHandlers().entrySet()) {
                try {
                    Class<?> clazz = Class.forName(entry.getValue());
                    WebSocketApplicationHandler handler = (WebSocketApplicationHandler) clazz.getDeclaredConstructor().newInstance();
                    handlers.put(entry.getKey(), handler);
                    pathMatcher.addPrefixPath(entry.getKey(), handler);
                    logger.info("Registered WebSocket handler {} for path prefix {}", entry.getValue(), entry.getKey());
                } catch (Exception e) {
                    logger.error("Failed to instantiate handler class {}", entry.getValue(), e);
                }
            }
        }
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        String path = exchange.getRequestPath();
        PathMatcher.PathMatch<WebSocketApplicationHandler> match = pathMatcher.match(path);
        
        if (match.getValue() != null) {
            // Found a match, delegate to handshake handler
            // The handshake handler will call our onConnect, where we need to find the correct handler again
            // To pass context, we could attach the matched handler to the exchange, but onConnect takes WebSocketHttpExchange
            // However, the handshake handler takes a callback. passing 'this' means 'this.onConnect' is called.
            
            // We can attach the target handler to the exchange attachment to retrieve it in onConnect
            exchange.putAttachment(AttachmentKey.create(WebSocketApplicationHandler.class), match.getValue());
            
            new WebSocketProtocolHandshakeHandler(this).handleRequest(exchange);
        } else {
            Handler.next(exchange, next);
        }
    }

    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        // Retrieve the delegate handler from the attachment is tricky because WebSocketHttpExchange wraps the underlying exchange 
        // but doesn't expose attachments directly in a standard way easily across versions or it might be different.
        // However, we can re-evaluate the path.
        
        String path = exchange.getRequestURI();
        // Remove query parameters
        int queryIndex = path.indexOf('?');
        if(queryIndex > 0) {
            path = path.substring(0, queryIndex);
        }
        
        PathMatcher.PathMatch<WebSocketApplicationHandler> match = pathMatcher.match(path);
        if (match.getValue() != null) {
            match.getValue().onConnect(exchange, channel);
        } else {
            logger.error("WebSocket connection established but no handler found for path: {}", path);
            try {
                channel.sendClose();
            } catch (Exception e) {
                logger.error("Error closing channel", e);
            }
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
        return config.isEnabled(); 
    }
}
