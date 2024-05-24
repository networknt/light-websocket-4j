package com.networknt.websocket.router;

import com.networknt.websocket.client.WsAttributes;
import com.networknt.websocket.client.WsProxyClientPair;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.IoUtils;

import java.io.IOException;
import java.util.Map;

public class WebSocketSessionProxyReceiveListener extends AbstractReceiveListener {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketSessionProxyReceiveListener.class);

    private final Map<String, WsProxyClientPair> proxyChannels;

    public WebSocketSessionProxyReceiveListener(Map<String, WsProxyClientPair> proxyChannels) {
        this.proxyChannels = proxyChannels;
    }

    @Override
    protected void onFullTextMessage(final WebSocketChannel channel, final BufferedTextMessage message) throws IOException {
        final var channelId = channel.getAttribute(WsAttributes.CHANNEL_GROUP_ID);
        if (channelId instanceof String && proxyChannels.containsKey(channelId)) {
            final var clientPair = proxyChannels.get(channelId);

            if (clientPair != null) {
                final var client = clientPair.getClientForChannel(channel);

                if (client != null) {
                    LOG.trace("Received text data from {} and forwarding to {}", channel.getSourceAddress(), client.getChannel().getDestinationAddress());

                    if (client.hasPeers()) {
                        final var peers = client.getConnectedPeers();

                        for (final var peer : peers) {
                            final var peerChannelId = peer.getAttribute(WsAttributes.CHANNEL_GROUP_ID);

                            if (peerChannelId.equals(channelId))
                                WebSockets.sendText(message.getData(), peer, null);

                        }

                    } else client.send(message.getData());
                }
            }
        }
    }

    @Override
    protected void onError(final WebSocketChannel channel, final Throwable error) {
        final var channelId = channel.getAttribute(WsAttributes.CHANNEL_GROUP_ID);

        if (channelId instanceof String) {
            final var pair = proxyChannels.get(channelId);

            if (pair != null) {
                pair.safeClosePairs();
                return;
            }
        }
        IoUtils.safeClose(channel);

    }
}
