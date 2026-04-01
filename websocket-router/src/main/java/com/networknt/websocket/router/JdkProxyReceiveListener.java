package com.networknt.websocket.router;

import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedBinaryMessage;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Pooled;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Undertow receive listener for the frontend (client-to-proxy) side that forwards
 * messages to the backend via JDK HttpClient WebSocket.
 */
public class JdkProxyReceiveListener extends AbstractReceiveListener {
    private static final Logger LOG = LoggerFactory.getLogger(JdkProxyReceiveListener.class);

    private final Map<String, WebSocket> backendChannels;
    private final String channelId;

    /**
     * Constructs a new JdkProxyReceiveListener.
     *
     * @param backendChannels the map of channelId to JDK WebSocket backend connections
     * @param channelId the channel identifier for this connection pair
     */
    public JdkProxyReceiveListener(Map<String, WebSocket> backendChannels, String channelId) {
        this.backendChannels = backendChannels;
        this.channelId = channelId;
    }

    @Override
    protected void onFullTextMessage(final WebSocketChannel channel, final BufferedTextMessage message) throws IOException {
        WebSocket backend = backendChannels.get(channelId);
        if (backend != null) {
            String data = message.getData();
            if (LOG.isTraceEnabled()) LOG.trace("Forwarding text from frontend to backend for channelId: {}, length: {}", channelId, data.length());
            backend.sendText(data, true);
        } else {
            LOG.warn("No backend WebSocket found for channelId: {}", channelId);
        }
    }

    @Override
    protected void onFullBinaryMessage(final WebSocketChannel channel, final BufferedBinaryMessage message) throws IOException {
        WebSocket backend = backendChannels.get(channelId);
        if (backend != null) {
            if (LOG.isTraceEnabled()) LOG.trace("Forwarding binary from frontend to backend for channelId: {}", channelId);
            Pooled<ByteBuffer[]> pooled = message.getData();
            try {
                ByteBuffer[] buffers = pooled.getResource();
                for (int i = 0; i < buffers.length; i++) {
                    ByteBuffer buf = buffers[i];
                    boolean last = (i == buffers.length - 1);
                    backend.sendBinary(buf, last);
                }
            } finally {
                pooled.free();
            }
        } else {
            LOG.warn("No backend WebSocket found for channelId: {}", channelId);
        }
    }

    @Override
    protected void onCloseMessage(CloseMessage cm, WebSocketChannel channel) {
        if (LOG.isDebugEnabled()) LOG.debug("Frontend close for channelId: {}, code: {}, reason: {}", channelId, cm.getCode(), cm.getReason());
        WebSocket backend = backendChannels.remove(channelId);
        if (backend != null) {
            backend.sendClose(cm.getCode(), cm.getReason() != null ? cm.getReason() : "");
        }
    }

    @Override
    protected void onError(final WebSocketChannel channel, final Throwable error) {
        LOG.error("Frontend WebSocket error for channelId: {}", channelId, error);
        WebSocket backend = backendChannels.remove(channelId);
        if (backend != null) {
            backend.sendClose(WebSocket.NORMAL_CLOSURE, "Frontend error");
        }
        try {
            channel.sendClose();
        } catch (IOException e) {
            LOG.error("Failed to close frontend channel for channelId: {}", channelId, e);
        }
    }
}
