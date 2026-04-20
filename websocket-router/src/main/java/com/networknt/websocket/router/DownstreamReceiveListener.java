package com.networknt.websocket.router;

import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketCallback;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * JDK WebSocket.Listener for the downstream (backend-to-proxy) that forwards
 * messages to the upstream (proxy-to-client) via Undertow WebSocketChannel.
 */
public class DownstreamReceiveListener implements WebSocket.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(DownstreamReceiveListener.class);

    private final String pairId;
    private final WebSocketChannel upstreamChannel;
    private final StringBuilder textBuffer = new StringBuilder();
    private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

    public DownstreamReceiveListener(String pairId, WebSocketChannel upstreamChannel) {
        this.pairId = pairId;
        this.upstreamChannel = upstreamChannel;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        LOG.trace("Downstream connection established for {}", pairId);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuffer.append(data);
        if(!last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        String message = textBuffer.toString();
        textBuffer.setLength(0);

        if(!upstreamChannel.isOpen()) {
            LOG.warn("Upstream is closed. Cannot forward text message for {}", pairId);
            return CompletableFuture.completedFuture(null);
        }

        LOG.trace("Forwarding text from downstream to upstream for {}", pairId);
        CompletableFuture<Void> future = new CompletableFuture<>();
        WebSockets.sendText(message, upstreamChannel, new WebSocketCallback<>() {
            @Override
            public void complete(WebSocketChannel channel, Void context) {
                webSocket.request(1);
                future.complete(null);
            }

            @Override
            public void onError(WebSocketChannel channel, Void context, Throwable throwable) {
                LOG.error("Failed to forward text message to upstream for {}", pairId, throwable);
                webSocket.request(1);
                future.complete(null);
            }
        });
        return future;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        binaryBuffer.write(chunk, 0, chunk.length);
        if(!last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        byte[] bytes = binaryBuffer.toByteArray();
        binaryBuffer.reset();

        if(!upstreamChannel.isOpen()) {
            LOG.warn("Upstream is closed. Cannot forward binary message for {}", pairId);
            return CompletableFuture.completedFuture(null);
        }

        LOG.trace("Forwarding binary from downstream to upstream for {}", pairId);
        CompletableFuture<Void> future = new CompletableFuture<>();
        WebSockets.sendBinary(ByteBuffer.wrap(bytes), upstreamChannel, new WebSocketCallback<>() {
            @Override
            public void complete(WebSocketChannel channel, Void context) {
                webSocket.request(1);
                future.complete(null);
            }

            @Override
            public void onError(WebSocketChannel channel, Void context, Throwable throwable) {
                LOG.error("Failed to forward binary message to upstream for {}", pairId, throwable);
                webSocket.request(1);
                future.complete(null);
            }
        });
        return future;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if(!upstreamChannel.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }

        if(!reason.isEmpty()) {
            LOG.trace("Downstream closed {}. Code: {}. Reason: {}", pairId, statusCode, reason);
        } else {
            LOG.trace("Downstream closed {}. Code: {}. No reason given", pairId, statusCode);
        }
        LOG.trace("Closing upstream for {} due to downstream close", pairId);
        WebSockets.sendClose(statusCode, reason, upstreamChannel, null);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        LOG.error("Downstream error for {}", pairId, error);

        if (upstreamChannel.isOpen()) {
            LOG.trace("Closing upstream for {} due to downstream error", pairId);
            WebSockets.sendClose(CloseMessage.UNEXPECTED_ERROR, "Downstream encountered error", upstreamChannel, null);
        }
    }
}
