package com.networknt.websocket.router;

import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

/**
 * JDK WebSocket.Listener that receives messages from the backend server
 * and forwards them to the Undertow frontend WebSocketChannel (client).
 */
public class JdkBackendWebSocketListener implements WebSocket.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(JdkBackendWebSocketListener.class);

    private final WebSocketChannel frontendChannel;
    private final String channelId;
    private final StringBuilder textBuffer = new StringBuilder();

    /**
     * Constructs a new JdkBackendWebSocketListener.
     *
     * @param frontendChannel the Undertow WebSocketChannel to forward messages to
     * @param channelId the channel identifier for logging
     */
    public JdkBackendWebSocketListener(WebSocketChannel frontendChannel, String channelId) {
        this.frontendChannel = frontendChannel;
        this.channelId = channelId;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        if (LOG.isTraceEnabled()) LOG.trace("Backend WebSocket connection opened for channelId: {}", channelId);
        // Request the first message
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuffer.append(data);
        if (last) {
            String message = textBuffer.toString();
            textBuffer.setLength(0);
            if (LOG.isTraceEnabled()) LOG.trace("Received text from backend for channelId: {}, length: {}", channelId, message.length());
            if (frontendChannel.isOpen()) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                WebSockets.sendText(message, frontendChannel, new io.undertow.websockets.core.WebSocketCallback<Void>() {
                    @Override
                    public void complete(WebSocketChannel channel, Void context) {
                        future.complete(null);
                    }

                    @Override
                    public void onError(WebSocketChannel channel, Void context, Throwable throwable) {
                        LOG.error("Failed to forward text message to frontend for channelId: {}", channelId, throwable);
                        future.complete(null);
                    }
                });
                webSocket.request(1);
                return future;
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        if (LOG.isTraceEnabled()) LOG.trace("Received binary from backend for channelId: {}", channelId);
        if (frontendChannel.isOpen()) {
            // Forward binary data to the frontend as-is via pooled buffer
            WebSockets.sendBinary(data, frontendChannel, null);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (LOG.isDebugEnabled()) LOG.debug("Backend WebSocket closed for channelId: {}, status: {}, reason: {}", channelId, statusCode, reason);
        try {
            if (frontendChannel.isOpen()) {
                frontendChannel.setCloseCode(statusCode);
                frontendChannel.setCloseReason(reason != null ? reason : "");
                frontendChannel.sendClose();
            }
        } catch (Exception e) {
            LOG.error("Error closing frontend channel for channelId: {}", channelId, e);
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        LOG.error("Backend WebSocket error for channelId: {}", channelId, error);
        try {
            if (frontendChannel.isOpen()) {
                frontendChannel.sendClose();
            }
        } catch (Exception e) {
            LOG.error("Error closing frontend channel after backend error for channelId: {}", channelId, e);
        }
    }
}
