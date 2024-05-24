package com.networknt.websocket.client;

import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.IoUtils;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public class WsClient {
    private static final Logger LOG = LoggerFactory.getLogger(WsClient.class);
    private final WebSocketChannel channel;

    public WsClient(WebSocketChannel channel, long idleTimeout) {
        this.channel = channel;
        this.channel.setIdleTimeout(idleTimeout);
    }

    public WsClient(WebSocketChannel channel) {
        this(channel, -1L);
    }

    public void sendTextToAllPeers(String text) {
        this.sendTextToAllPeers(text, null);
    }

    public Set<WebSocketChannel> getConnectedPeers() {
        return this.channel.getPeerConnections();
    }

    public void sendTextToAllPeers(String text, WebSocketCallback callback) {
        if (channel.isOpen()) {
            for (final var peer : this.channel.getPeerConnections()) {
                WebSockets.sendText(text, peer, new io.undertow.websockets.core.WebSocketCallback<Void>() {
                    @Override
                    public void complete(WebSocketChannel channel, Void ignore) {
                        if (callback != null) {
                            callback.complete(null);
                        }
                    }

                    @Override
                    public void onError(WebSocketChannel channel, Void ignore, Throwable throwable) {
                        if (callback != null) {
                            callback.complete(throwable);
                        }
                    }
                });
            }
        }
    }

    public boolean hasPeers() {
        return !this.channel.getPeerConnections().isEmpty();
    }

    public boolean send(String text) {
        return this.send(text, null);
    }

    public void safeCloseChannel() {
        IoUtils.safeClose(this.channel);
    }

    public void sendCloseFrame(final int closeCode, String closeReason) {
        this.channel.setCloseCode(closeCode);
        this.channel.setCloseReason(closeReason);
        this.sendCloseFrame();
    }

    public void sendCloseFrame() {
        try {
            this.channel.sendClose();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public WebSocketChannel getChannel() {
        return channel;
    }

    public boolean send(String text, WebSocketCallback callback) {
        if (this.channel.isOpen()) {
            WebSockets.sendText(text, this.channel, new io.undertow.websockets.core.WebSocketCallback<Void>() {
                @Override
                public void complete(WebSocketChannel channel, Void ignore) {
                    if (callback != null) {
                        callback.complete(null);
                    }
                }

                @Override
                public void onError(WebSocketChannel channel, Void ignore, Throwable throwable) {
                    if (callback != null) {
                        callback.complete(throwable);
                    }
                }
            });
            return true;
        } else {
            return false;
        }
    }
}
