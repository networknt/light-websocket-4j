package com.networknt.websocket.client;

/**
 * Close codes are provided in the closeFrame.
 * They let the user know why a websocket connection was closed.
 *
 * See <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4.1">RFC-6455 Close Codes</a> for more information.
 */
public enum WebSocketCloseCodes {

    /**
     * Indicates a normal closure, meaning that the purpose for which the connection was established has been fulfilled.
     */
    CLOSE_NORMAL(1000),

    /**
     * indicates that an endpoint is "going away", such as a server going down or a browser having navigated away from a page.
     */
    GOING_AWAY(1001),

    /**
     * Indicates that an endpoint is terminating the connection due to a protocol error.
     */
    PROTOCOL_ERROR(1002),

    /**
     * Indicates that an endpoint is terminating the connection because it has received a type of data it cannot accept
     * (e.g., an endpoint that understands only text data MAY send this if it receives a binary message).
     */
    UNSUPPORTED_DATA(1003),

    /**
     * Reserved. The specific meaning might be defined in the future.
     */
    RESERVED(1004),

    /**
     * Reserved value and MUST NOT be set as a status code in a Close control frame by an endpoint.
     * It is designated for use in applications expecting a status code to indicate that no status code was actually present.
     */
    NO_STATUS_RECEIVED(1005),

    /**
     * Reserved value and MUST NOT be set as a status code in a Close control frame by an endpoint.
     * It is designated for use in applications expecting a status code to indicate that the connection was closed abnormally,
     * e.g., without sending or receiving a Close control frame.
     */
    ABNORMAL_CLOSURE(1006),

    /**
     * Connection was terminated because of the data format within the sent message
     * (i.e. non-UTF-8 data within text).
     */
    INVALID_FRAME_PAYLOAD_DATA(1007),

    /**
     * Indicates that an endpoint is terminating the connection because it has received a message that violates its policy.
     * This is a generic status code that can be returned when there is no other more suitable status code or if there
     * is a need to hide specific details about the policy.
     */
    POLICY_VIOLATION(1008),

    /**
     * Indicates that an endpoint is terminating the connection
     * because it has received a message that is too big for it to process.
     */
    MESSAGE_TOO_BIG(1009),

    /**
     * Indicates that an endpoint (client) is terminating the connection because it has expected the server to negotiate one or more extension,
     * but the server didn't return them in the response message of the WebSocket handshake.
     * The list of extensions that are needed SHOULD appear in the 'reason' part of the Close frame.
     */
    MANDATORY_EXTENSION(1010),

    /**
     * Indicates that a server is terminating the connection because it encountered an unexpected condition that prevented it from fulfilling the request.
     */
    INTERNAL_ERROR(1011),

    /**
     * Reserved value and MUST NOT be set as a status code in a close control frame by an endpoint.
     * It is designated for use in applications expecting a status code to indicate that the connection was closed due to a failure to perform a TLS handshake
     * (e.g., the server certificate can't be verified).
     */
    TLS_HANDSHAKE(1015);

    private final int closeCode;

    WebSocketCloseCodes(int closeCode) {
        this.closeCode = closeCode;
    }

    public int getCloseCode() {
        return closeCode;
    }
}
