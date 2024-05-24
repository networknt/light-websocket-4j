package com.networknt.websocket.session;


import com.networknt.websocket.client.WsAttributes;
import com.networknt.websocket.client.WsClient;
import com.networknt.websocket.client.WsProxyClientPair;
import io.undertow.server.HttpServerExchange;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.client.WebSocketClient;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketSessionHandler implements WebSocketConnectionCallback {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketSessionHandler.class);
    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel inComingChannel) {
        LOG.trace("On connect received -- router-session");
        final var channelId = exchange.getRequestHeader(WsAttributes.CHANNEL_GROUP_ID);
        if (channelId != null) {
            inComingChannel.setAttribute(WsAttributes.CHANNEL_GROUP_ID, channelId);
            inComingChannel.setAttribute(WsAttributes.CHANNEL_USER_ID, getUUID());
            inComingChannel.getReceiveSetter().set(new AbstractReceiveListener() {
                @Override
                protected void onFullTextMessage(final WebSocketChannel channel, final BufferedTextMessage message) throws IOException {
                    final var channelId = channel.getAttribute(WsAttributes.CHANNEL_GROUP_ID);
                    final var senderId = channel.getAttribute(WsAttributes.CHANNEL_USER_ID);
                    final var data = message.getData();

                    if (channelId != null && (!channel.getPeerConnections().isEmpty())) {

                        for (final var peer : channel.getPeerConnections()) {
                            final var peerChannelId = peer.getAttribute(WsAttributes.CHANNEL_GROUP_ID);

                            if (peerChannelId instanceof String && peerChannelId.equals(channelId))
                                WebSockets.sendText(senderId + ": " + data, peer, null);

                        }

                    } else WebSockets.sendText(senderId + ": " + data, channel, null);


                }
            });
            inComingChannel.resumeReceives();
        }
    }

    public static String getUUID() {
        UUID id = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return Base64.encodeBase64URLSafeString(bb.array());
    }


}
