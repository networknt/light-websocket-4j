package com.networknt.websocket.client;

public final class WsAttributes {

    public static final String CHANNEL_GROUP_ID = "x-group-id";
    public static final String CHANNEL_USER_ID = "x-user-id";
    public static final String CHANNEL_DIRECTION = "x-socket-vector";
    public static final String WEBSOCKET_PROTOCOL = "ws";
    public static final String WEBSOCKET_SECURE_PROTOCOL = "wss";

    private WsAttributes() {
        throw new IllegalStateException("WsAttributes is a utility class.");
    }


}
