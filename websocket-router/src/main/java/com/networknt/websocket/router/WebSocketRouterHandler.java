package com.networknt.websocket.router;

import com.networknt.cluster.Cluster;
import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.router.RouterConfig;
import com.networknt.service.SingletonServiceFactory;
import com.networknt.websocket.client.WsAttributes;
import com.networknt.websocket.client.WsProxyClientPair;
import io.undertow.Handlers;
import io.undertow.client.ProxiedRequestAttachments;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.PathMatcher;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.client.WebSocketClient;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketRouterHandler implements MiddlewareHandler, WebSocketConnectionCallback {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketRouterHandler.class);
    private static final Cluster CLUSTER = SingletonServiceFactory.getBean(Cluster.class);
    private static final RouterConfig CONFIG = RouterConfig.load();
    private static final WebSocketRouterConfig WS_CONFIG = WebSocketRouterConfig.load();
    private static final Map<String, WsProxyClientPair> CHANNELS = new ConcurrentHashMap<>();
    private static final PathMatcher<String> pathMatcher = new PathMatcher<>();

    private volatile HttpHandler next;

    static {
        if (WS_CONFIG.getPathPrefixService() != null) {
            for (Map.Entry<String, String> entry : WS_CONFIG.getPathPrefixService().entrySet()) {
                pathMatcher.addPrefixPath(entry.getKey(), entry.getValue());
            }
        }
    }

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
            new WebSocketProtocolHandshakeHandler(this).handleRequest(exchange);
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
        String protocol = null;
        if (exchange.getRequestURI().startsWith(WsAttributes.WEBSOCKET_SECURE_PROTOCOL)) {
            protocol = WsAttributes.WEBSOCKET_SECURE_PROTOCOL;
        } else {
            protocol = WsAttributes.WEBSOCKET_PROTOCOL;
        }

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
            final var downstreamUrl = CLUSTER.serviceToUrl(protocol, serviceId, null, null);
            if (downstreamUrl != null) {
                String channelId = exchange.getRequestHeader(WsAttributes.CHANNEL_GROUP_ID);
                if (channelId == null) {
                    channelId = java.util.UUID.randomUUID().toString();
                }
                
                if(LOG.isTraceEnabled()) LOG.trace("channelId = {}", channelId);
                
                if (channelId != null) {
                    channel.setAttribute(WsAttributes.CHANNEL_GROUP_ID, channelId);
                    channel.setAttribute(WsAttributes.CHANNEL_DIRECTION, WsProxyClientPair.SocketFlow.CLIENT_TO_PROXY);
                    channel.getReceiveSetter().set(new WebSocketSessionProxyReceiveListener(CHANNELS));
                    
                    if (!CHANNELS.containsKey(channelId)) {
                        try {
                            final var targetUri = downstreamUrl + exchange.getRequestURI();
                            final var address = channel.getSourceAddress();
                            final String remoteHost;

                            /* add remote host and remote address attachments */
                            if (address != null) {
                                remoteHost = address.getHostString();
                                if (!address.isUnresolved()) {
                                    exchange.putAttachment(ProxiedRequestAttachments.REMOTE_ADDRESS, address.getAddress().getHostAddress());
                                }
                            } else remoteHost = "localhost";
                            exchange.putAttachment(ProxiedRequestAttachments.REMOTE_HOST, remoteHost);

                            /* add server name attachment */
                            String host;
                            if (CONFIG.isReuseXForwarded()) {
                                host = exchange.getRequestHeader(Headers.X_FORWARDED_SERVER_STRING);
                            } else {
                                host = exchange.getRequestHeader(Headers.HOST_STRING);
                                if (host == null || "".equals(host.trim())) {
                                    host  = channel.getDestinationAddress().getHostString();
                                } else {
                                    if (host.startsWith("[")) {
                                        host = host.substring(1, host.indexOf(']'));
                                    } else if (host.indexOf(':') != -1) {
                                        host = host.substring(0, host.indexOf(':'));
                                    }
                                }
                            }
                            exchange.putAttachment(ProxiedRequestAttachments.SERVER_NAME, host);

                            /* attach x-forwarded-port */
                            String port;
                            if (CONFIG.isReuseXForwarded() && exchange.getRequestHeader(Headers.X_FORWARDED_PORT_STRING) != null) {
                                try {
                                    port = exchange.getRequestHeader(Headers.X_FORWARDED_PORT_STRING);
                                    exchange.putAttachment(ProxiedRequestAttachments.SERVER_PORT, Integer.parseInt(port));

                                } catch (NumberFormatException e) {
                                    port = String.valueOf(channel.getDestinationAddress().getPort());
                                    exchange.putAttachment(ProxiedRequestAttachments.SERVER_PORT, Integer.parseInt(port));
                                }
                            } else {
                                port = String.valueOf(channel.getDestinationAddress().getPort());
                                exchange.putAttachment(ProxiedRequestAttachments.SERVER_PORT, Integer.parseInt(port));
                            }

                            /* create new connection to downstream */
                            final var webSocketConnection = new WebSocketClient.ConnectionBuilder(
                                    channel.getWorker(),
                                    channel.getBufferPool(),
                                    new URI(targetUri)
                            );
                            final var outChannel = webSocketConnection.connect().get();

                            outChannel.setAttribute(WsAttributes.CHANNEL_GROUP_ID, channelId);
                            outChannel.setAttribute(WsAttributes.CHANNEL_DIRECTION, WsProxyClientPair.SocketFlow.PROXY_TO_DOWNSTREAM);
                            outChannel.getReceiveSetter().set(new WebSocketSessionProxyReceiveListener(CHANNELS));
                            CHANNELS.put(channelId, new WsProxyClientPair(channel, outChannel));
                            outChannel.resumeReceives();
                        } catch (URISyntaxException | IOException e) {
                            LOG.error("Failed to create connection to the backend.", e);
                            try {
                                channel.sendClose();
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                            exchange.endExchange();
                        }
                    }
                    channel.resumeReceives();
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
