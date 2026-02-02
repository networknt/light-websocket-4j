package com.networknt.websocket.rendezvous;

import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.websocket.client.WsAttributes;
import com.networknt.websocket.client.WsProxyClientPair;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketRendezvousHandler implements MiddlewareHandler, WebSocketConnectionCallback {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketRendezvousHandler.class);
    private static final WebSocketRendezvousConfig config = WebSocketRendezvousConfig.load();
    // Shared map for rendezvous channels. Since this is a singleton handler, this map works.
    // If we had multiple instances, we might need a shared registry or singleton bean.
    private static final Map<String, WsProxyClientPair> CHANNELS = new ConcurrentHashMap<>();

    private volatile HttpHandler next;

    public WebSocketRendezvousHandler() {
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (config.isEnabled()) {
            new WebSocketProtocolHandshakeHandler(this).handleRequest(exchange);
        } else {
            // If disabled, skip to next handler?
            // Usually if a handler is disabled, it shouldn't be processing. 
            // In MiddlewareHandler pattern, "isEnabled" controls if it's executed in the chain.
            // But if we are called, we should check it.
            // Delegate to next.
            Handler.next(exchange, next);
        }
    }

    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        // Logic extracted from WebSocketRouterHandler's rendezvous section
        String channelId = exchange.getRequestHeader(WsAttributes.CHANNEL_GROUP_ID);
        if (channelId == null) {
             // Manual parse from request URI
             String requestURI = exchange.getRequestURI();
             if (requestURI.contains("channelId=")) {
                 String[] parts = requestURI.split("\\?");
                 if (parts.length > 1) {
                     String[] params = parts[1].split("&");
                     for (String param : params) {
                         if (param.startsWith("channelId=")) {
                             channelId = param.substring("channelId=".length());
                             break;
                         }
                     }
                 }
             }
        }
        
        if (channelId == null) {
            LOG.error("ChannelId missing for Rendezvous connection.");
            try { channel.sendClose(); } catch (IOException e) { e.printStackTrace(); }
            return;
        }

        if(LOG.isTraceEnabled()) LOG.trace("Rendezvous channelId = {}", channelId);

        channel.setAttribute(WsAttributes.CHANNEL_GROUP_ID, channelId);
        
        // Determine role based on path
        boolean isBackend = false;
        String backendPath = config.getBackendPath();
        if (backendPath != null && exchange.getRequestURI().contains(backendPath)) {
            isBackend = true;
        }

        synchronized (CHANNELS) {
            WsProxyClientPair pair = CHANNELS.get(channelId);
            if (pair == null) {
                // First peer arriving
                if (isBackend) {
                    // Backend arrived first
                    LOG.warn("Backend connected before Client for channelId: {}. Dropping.", channelId);
                    try { channel.sendClose(); } catch (IOException e) {}
                    return;
                } else {
                     // Client arrived first (Expected)
                     pair = new WsProxyClientPair(channel);
                     channel.setAttribute(WsAttributes.CHANNEL_DIRECTION, WsProxyClientPair.SocketFlow.CLIENT_TO_PROXY);
                }
                CHANNELS.put(channelId, pair);
                
                channel.getReceiveSetter().set(new WebSocketRendezvousReceiveListener(CHANNELS));
                channel.resumeReceives();
                
            } else {
                // Second peer arriving
                if (isBackend) {
                    // Backend arrived second. Set Destination.
                    pair.setProxyToDestinationClient(channel);
                    channel.setAttribute(WsAttributes.CHANNEL_DIRECTION, WsProxyClientPair.SocketFlow.PROXY_TO_DOWNSTREAM);
                    // Use local Listener
                    channel.getReceiveSetter().set(new WebSocketRendezvousReceiveListener(CHANNELS));
                    channel.resumeReceives();
                } else {
                    LOG.error("Duplicate client connection for channelId: {}", channelId);
                    try { channel.sendClose(); } catch (IOException e) {}
                }
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
