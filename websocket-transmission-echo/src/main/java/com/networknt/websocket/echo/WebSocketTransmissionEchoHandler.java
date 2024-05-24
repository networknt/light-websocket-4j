package com.networknt.websocket.echo;

import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.*;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class WebSocketTransmissionEchoHandler implements WebSocketConnectionCallback {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketTransmissionEchoHandler.class);
    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        LOG.info("Received onConnect for a new websocket connection!");
        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(final WebSocketChannel channel, final BufferedTextMessage message) throws IOException {
                final var msg = "echo! " + message.getData();
                LOG.info("*----< onFullTextMessage >----*");
                LOG.info("Data Received: '{}'", msg);
                LOG.info("Source Address: '{}'", channel.getSourceAddress());
                LOG.info("Destination Address: '{}'", channel.getDestinationAddress());
                LOG.info("*-----------------------------*");

                LOG.info("Echoing back to '{}' connected peers.", channel.getPeerConnections().size());
                for (final var peer : channel.getPeerConnections()) {
                    WebSockets.sendText(msg, peer, new WebSocketCallback<>() {
                        @Override
                        public void complete(WebSocketChannel channel, Void context) {
                            LOG.info("Successfully sent an echo back to {}", channel.getDestinationAddress());
                        }

                        @Override
                        public void onError(WebSocketChannel channel, Void context, Throwable throwable) {
                            LOG.error("Failed to send an echo to {}", channel.getDestinationAddress());
                        }
                    });
                }
            }

            @Override
            protected void onFullBinaryMessage(final WebSocketChannel channel, final BufferedBinaryMessage message) throws IOException {
                LOG.info("*---< onFullBinaryMessage >---*");
                LOG.info("Data Received: '{}'", message);
                LOG.info("Source Address: '{}'", channel.getSourceAddress());
                LOG.info("Destination Address: '{}'", channel.getDestinationAddress());
                LOG.info("*-----------------------------*");
                LOG.info("Echoing back to '{}' connected peers.", channel.getPeerConnections().size());
                final var msg = message.getData().getResource();

                for (final var peer : channel.getPeerConnections()) {
                    WebSockets.sendBinary(msg, peer, null);
                }
            }
        });
        channel.resumeReceives();
    }
}
