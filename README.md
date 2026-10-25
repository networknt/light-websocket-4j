# light-websocket-4j
WebSocket server and client for realtime communication between microservices


### Jackson 3 development migration

This development line uses the Jackson 3.2.3 BOM and migrated light-4j/http-client
snapshots. Annotation packages remain unchanged. Existing project versions are
unchanged during implementation; coordinate a next-major release before
publishing incompatible Jackson API dependencies.

The router and handler configuration readers now catch unchecked
JacksonException where their JSON parsing previously threw IOException.
Malformed pathPrefixService/pathPrefixHandlers JSON retains the handled empty
map fallback; valid routing maps and reload identity behavior are unchanged.
Other IOException handlers continue to handle actual socket/channel I/O.
