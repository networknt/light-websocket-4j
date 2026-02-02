package com.networknt.websocket.client;

import io.undertow.websockets.core.WebSocketChannel;

public class WsProxyClientPair {
    public enum SocketFlow {
        CLIENT_TO_PROXY,
        PROXY_TO_DOWNSTREAM
    }
    private WsClient clientToProxyClient;
    private WsClient proxyToDestinationClient;

    public WsProxyClientPair(final WsClient clientToProxyClient, final WsClient proxyToDestinationClient) {
        this.clientToProxyClient = clientToProxyClient;
        this.proxyToDestinationClient = proxyToDestinationClient;
    }

    public WsProxyClientPair(final WebSocketChannel clientToProxyChannel, final WebSocketChannel proxyToDestinationChannel) {
        this(clientToProxyChannel != null ? new WsClient(clientToProxyChannel) : null, 
             proxyToDestinationChannel != null ? new WsClient(proxyToDestinationChannel) : null);
    }

    // Constructor for waiting/rendezvous mode
    public WsProxyClientPair(final WebSocketChannel clientToProxyChannel) {
        this.clientToProxyClient = new WsClient(clientToProxyChannel);
    }

    public void setProxyToDestinationClient(final WebSocketChannel proxyToDestinationChannel) {
        this.proxyToDestinationClient = new WsClient(proxyToDestinationChannel);
    }
    
    public void setClientToProxyClient(final WebSocketChannel clientToProxyChannel) {
        this.clientToProxyClient = new WsClient(clientToProxyChannel);
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
        if(this.clientToProxyClient != null) this.clientToProxyClient.safeCloseChannel();
        if(this.proxyToDestinationClient != null) this.proxyToDestinationClient.safeCloseChannel();
    }
}
