package com.networknt.websocket.router;

import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedBinaryMessage;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.IoUtils;
import org.xnio.Pooled;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;

/**
 * Undertow receive listener for the upstream (client-to-proxy) that forwards
 * messages to the downstream (proxy-to-backend) via JDK HttpClient WebSocket.
 */
public class UpstreamReceiveListener extends AbstractReceiveListener {
    private static final Logger LOG = LoggerFactory.getLogger(UpstreamReceiveListener.class);

    private final String pairId;
    private final WebSocket downstreamChannel;

    public UpstreamReceiveListener(String pairId, WebSocket downstreamChannel) {
        this.pairId = pairId;
        this.downstreamChannel = downstreamChannel;
    }

    @Override
    protected void onFullTextMessage(final WebSocketChannel channel, final BufferedTextMessage message) {
        if(downstreamChannel.isInputClosed() || downstreamChannel.isOutputClosed()) {
            LOG.warn("Downstream is closed. Cannot forward text message for {}", pairId);
            return;
        }

        String data = message.getData();
        LOG.trace("Forwarding text from upstream to downstream for {}", pairId);
        downstreamChannel.sendText(data, true);
    }

    @Override
    protected void onFullBinaryMessage(final WebSocketChannel channel, final BufferedBinaryMessage message) throws IOException {
        if(downstreamChannel.isInputClosed() || downstreamChannel.isOutputClosed()) {
            LOG.warn("Downstream is closed. Cannot forward binary message for {}", pairId);
            return;
        }

        // Coalesce pooled buffers into a single owned ByteBuffer before freeing the pool.
        // sendBinary is asynchronous and may read the buffers after this method returns,
        // so we must not free the pooled buffers until the copy is made.
        Pooled<ByteBuffer[]> pooled = message.getData();
        ByteBuffer copy;
        try {
            ByteBuffer[] buffers = pooled.getResource();
            long totalBytes = 0L;
            for (ByteBuffer buf : buffers) {
                totalBytes += buf.remaining();
            }
            if (totalBytes > Integer.MAX_VALUE) {
                throw new IOException("WebSocket binary message too large: " + totalBytes + " bytes");
            }
            copy = ByteBuffer.allocate((int) totalBytes);
            for (ByteBuffer buf : buffers) {
                copy.put(buf);
            }
            copy.flip();
        } finally {
            pooled.free();
        }

        LOG.trace("Forwarding binary from upstream to downstream for {}", pairId);
        downstreamChannel.sendBinary(copy, true);
    }

    @Override
    protected void onCloseMessage(CloseMessage cm, WebSocketChannel channel) {
        if(downstreamChannel.isInputClosed() || downstreamChannel.isOutputClosed()) {
            return;
        }

        if(!cm.getReason().isEmpty()) {
            LOG.trace("Upstream closed {}. Code: {}. Reason: {}", pairId, cm.getCode(), cm.getReason());
        } else {
            LOG.trace("Upstream closed {}. Code: {}. No reason given", pairId, cm.getCode());
        }
        LOG.trace("Closing downstream for {} due to upstream close", pairId);
        downstreamChannel.sendClose(cm.getCode(), cm.getReason());
    }

    @Override
    protected void onError(final WebSocketChannel channel, final Throwable error) {
        LOG.error("Upstream error for {}", pairId, error);
        IoUtils.safeClose(channel);

        if(!downstreamChannel.isInputClosed() && !downstreamChannel.isOutputClosed()) {
            LOG.trace("Closing downstream for {} due to upstream error", pairId);
            downstreamChannel.sendClose(CloseMessage.UNEXPECTED_ERROR, "Upstream encountered error");
        }
    }
}
