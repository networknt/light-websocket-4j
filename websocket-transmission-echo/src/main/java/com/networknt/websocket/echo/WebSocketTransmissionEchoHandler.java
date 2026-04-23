package com.networknt.websocket.echo;

import io.undertow.websockets.WebSocketConnectionCallback;

import io.undertow.websockets.core.*;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketTransmissionEchoHandler implements WebSocketConnectionCallback {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketTransmissionEchoHandler.class);

    public WebSocketTransmissionEchoHandler() {
        LOG.info("WebSocketTransmissionEchoHandler loaded");
    }

    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        LOG.info("Received onConnect for a new websocket connection!");

        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(final WebSocketChannel channel, final BufferedTextMessage message) {
                final String messageData = message.getData();
                LOG.info("*----< onFullTextMessage >----*");
                LOG.info("Data Received: '{}'", messageData);
                LOG.info("Source Address: '{}'", channel.getSourceAddress());
                LOG.info("Destination Address: '{}'", channel.getDestinationAddress());
                LOG.info("*-----------------------------*");

                if(messageData.equalsIgnoreCase("close")) {
                    LOG.info("Client requested server to close the connection. Closing connection...");
                    WebSockets.sendClose(CloseMessage.NORMAL_CLOSURE, "Closing connection as requested by client", channel, null);
                } else {
                    LOG.info("Echoing text data back to client...");
                    WebSockets.sendText("echo: " + messageData, channel, null);
                }
            }

            @Override
            protected void onFullBinaryMessage(final WebSocketChannel channel, final BufferedBinaryMessage message) {
                LOG.info("*---< onFullBinaryMessage >---*");
                LOG.info("Data Received: '{}'", message);
                LOG.info("Source Address: '{}'", channel.getSourceAddress());
                LOG.info("Destination Address: '{}'", channel.getDestinationAddress());
                LOG.info("*-----------------------------*");

                LOG.info("Echoing binary data back to client...");
                WebSockets.sendBinary(message.getData().getResource(), channel, null);
            }
        });

        channel.addCloseTask(c -> {
            LOG.info("WebSocket was closed by peer: {}", c.isCloseInitiatedByRemotePeer());
            LOG.info("Close code: {}", c.getCloseCode());
            LOG.info("Close reason: {}", c.getCloseReason());
        });

        channel.resumeReceives();
    }
}
