package com.networknt.websocket.client;

import io.undertow.websockets.core.WebSocketChannel;

public class WsProxyClientPair {
    public enum SocketFlow {
        CLIENT_TO_PROXY,
        PROXY_TO_DOWNSTREAM
    }
    private final WsClient clientToProxyClient;
    private final WsClient proxyToDestinationClient;
    public WsProxyClientPair(final WsClient clientToProxyClient, final WsClient proxyToDestinationClient) {
        this.clientToProxyClient = clientToProxyClient;
        this.proxyToDestinationClient = proxyToDestinationClient;
    }

    public WsProxyClientPair(final WebSocketChannel clientToProxyChannel, final WebSocketChannel proxyToDestinationChannel) {
        this(new WsClient(clientToProxyChannel), new WsClient(proxyToDestinationChannel));
    }

    public WsClient getClientForChannel(final WebSocketChannel channel) {
        if (channel == null)
            return null;

        final var direction = channel.getAttribute(WsAttributes.CHANNEL_DIRECTION);
        if (direction instanceof SocketFlow) {
            switch ((SocketFlow)direction) {
                case CLIENT_TO_PROXY:
                    return this.proxyToDestinationClient;
                case PROXY_TO_DOWNSTREAM:
                    return this.clientToProxyClient;
                default:
                    throw new IllegalStateException("Unknown enum option");
            }
        } else return null;
    }

    public void safeClosePairs() {
        this.clientToProxyClient.safeCloseChannel();
        this.proxyToDestinationClient.safeCloseChannel();
    }
}
