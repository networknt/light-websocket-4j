package com.networknt.websocket.router;

import com.networknt.cluster.Cluster;
import com.networknt.router.RouterConfig;
import com.networknt.service.SingletonServiceFactory;
import com.networknt.websocket.client.WsAttributes;
import com.networknt.websocket.client.WsProxyClientPair;
import io.undertow.client.ProxiedRequestAttachments;
import io.undertow.util.Headers;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.client.WebSocketClient;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketRouterHandler implements WebSocketConnectionCallback {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketRouterHandler.class);
    private static final Cluster CLUSTER = SingletonServiceFactory.getBean(Cluster.class);
    private static final RouterConfig CONFIG = RouterConfig.load();
    private static final Map<String, WsProxyClientPair> CHANNELS = new ConcurrentHashMap<>();

    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {

        String protocol = null;
        if (exchange.getRequestURI().startsWith(WsAttributes.WEBSOCKET_SECURE_PROTOCOL)) {
            protocol = WsAttributes.WEBSOCKET_SECURE_PROTOCOL;
        } else {
            protocol = WsAttributes.WEBSOCKET_PROTOCOL;
        }

        final var serviceId = exchange.getRequestHeader("service_id");
        if (serviceId != null) {
            final var downstreamUrl = CLUSTER.serviceToUrl(protocol, serviceId, null, null);
            if (downstreamUrl != null) {
                final var channelId = exchange.getRequestHeader(WsAttributes.CHANNEL_GROUP_ID);
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
                                    exchange.getRequestHeaders().put(Headers.X_FORWARDED_PORT_STRING, Collections.singletonList(port));
                                    exchange.putAttachment(ProxiedRequestAttachments.SERVER_PORT, Integer.parseInt(port));
                                }
                            } else {
                                port = String.valueOf(channel.getDestinationAddress().getPort());
                                exchange.getRequestHeaders().put(Headers.X_FORWARDED_PORT_STRING, Collections.singletonList(port));
                                exchange.putAttachment(ProxiedRequestAttachments.SERVER_PORT, Integer.parseInt(port));
                            }

                            // TODO - attach SSL info?

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
                            LOG.error("Failed to create connection to the backend.");
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
            }
        }
    }

}
