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

The source-build workflow resolves yaml-rule #36 and light-4j #2815 to their
latest heads while open, then follows their base branches after merge. Other
prerequisites use master. Each run logs the exact source commits tested and uses
a fresh Maven repository. Rerun this check after prerequisite changes and before
merging: an earlier green run qualifies only its recorded commits. Manual
workflow dispatch is available. Source-build success does not qualify published
artifact resolution.

Prerequisites are resolved to full commit SHAs before checkout. To replay a
failed dependency combination, manually dispatch the source-build workflow with
its uploaded `source-refs.json` contents as the `source_refs` input. Omitted
repositories resolve their current source head; supplied values must be full
commit SHAs. Each green result qualifies only the recorded combination.

GitHub manual dispatch requires this workflow to exist on the default branch.
Before merge, use the recorded commits to reproduce with local source checkouts.
