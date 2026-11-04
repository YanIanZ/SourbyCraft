# Plugin guidance: events that run on a Netty event loop

Audience: plugin authors and server operators.

Status: this is guidance from a **static source audit** (DEV-H, 2026-10-20, see
[region-io-audit.md](../architecture/region-io-audit.md#netty-event-loop-audit-dev-h-2026-10-20)).
It is not a measured benchmark. No server was run and no latency figure is claimed. The
file:line references are the audit's references in the materialized source tree. Updated
2026-10-25: H4/H5 are fixed (item 5 and *Open items*); H1 (the player-data read after
code-of-conduct acceptance) was fixed by Minecraft feature patch 0035 and is not a plugin concern.

## Why this matters

A Netty event loop is a network thread that serves many connections, not one. If a listener blocks
on it, every connection assigned to that loop waits, including players who are not involved in the
slow call. Work on these threads should be CPU-cheap and must not wait on the outside world.

## Events and handlers that run on the event loop

These run on the loop unless a listener moves the work off it. The audit found no thread hop in the
server code for these paths.

| Event or path | Where it fires (audit reference) | Notes |
| --- | --- | --- |
| `PlayerConnectionReconfigureEvent` | `server/network/ServerGamePacketListenerImpl.java:3861-3872` (H2) | Fired from `handleConfigurationAcknowledged`. Listeners run on the loop. |
| `PaperServerListPingEvent` (server-list ping) | `server/network/ServerStatusPacketListenerImpl.java:34-40` → `StandardPaperServerListPingEventImpl.java:66-68` (H3) | Every unauthenticated status ping. Any client that can open a socket can trigger it. |
| Legacy query ping | `server/network/LegacyQueryHandler.java:52,82,221` → `PaperLegacyStatusClient.java:46-51` (H3) | Same exposure as the status ping. |
| `PlayerHandshakeEvent` | `server/network/ServerHandshakePacketListenerImpl.java:122-148` (H4) | Fired from `handleIntention` when any listener is registered. |
| Cookie response futures | `io/papermc/paper/connection/ReadablePlayerCookieConnectionImpl.java:39-42`, reached from `ServerLoginPacketListenerImpl.java:456` and `ServerCommonPacketListenerImpl.java:263` (H6) | The future is completed on the loop, so a plain `thenAccept` runs inline there. |
| Third-party pipeline handlers | ProtocolLib and ViaVersion/ViaBackwards channel handlers (H8) | Their inbound packet handlers run on the loop. The audit did not read them. |

Things that are **not** on the loop, for contrast: async login and pre-login checks
(`AsyncPlayerPreLoginEvent` and session lookups run on `authenticatorPool`), `AsyncChatEvent`,
`AsyncTabCompleteEvent`, and most packet handling that calls `ensureRunningOnSameThread`. The
`PlayerLoginEvent` runs on the global tick thread.

## Why blocking there hurts

- A database, HTTP, Redis or DNS call on the loop stalls every connection on that loop until it
  returns. Latency is set by the slowest remote service, not by the server.
- Status pings are cheap for a client to send in bulk. A slow ping listener multiplies the cost.
- The server does not move your listener off the loop for you. For legacy (bridged) plugins, Paper
  feature patch 0026 wraps event dispatch only to record a scheduling context (the player the event is
  about) for tasks the listeners schedule; it does not hop threads, so the listeners still run on the
  loop.

## What to do instead

1. **Cache the data.** For MOTD, player count and favicon, refresh the values on a timer or on
   join/quit events, then read the cached value inside the ping listener. Keep the listener to a
   field read.
2. **Never do I/O inside these listeners.** No database, HTTP, Redis, DNS or file calls. If you
   need the result, run the call elsewhere and store the result for the next ping.
3. **Hand heavy work to your own executor or the async scheduler.** If the listener must wait for
   something, start the work on your own thread pool and only read its finished result on the loop.
4. **Give cookie continuations an explicit executor.** Use `thenAcceptAsync(fn, yourExecutor)` (or
   the `Async` variant of each stage) rather than `thenAccept`, so your code does not run on the
   loop when the cookie response arrives.
5. **Do not return hostnames from `PlayerHandshakeEvent`.** Return an IP literal. Before Minecraft
   feature patch 0037 a hostname made the server resolve it through DNS on the loop (H4). Since 0037 the
   server never resolves it: a socket address hostname that is not an IP literal is **ignored**, with one
   WARN for the whole run ("... not an IP literal; it is ignored ... (logged once)"), so your value has no
   effect. A BungeeCord-forwarded address that is not an IP literal is disconnected the way an invalid
   forward already was, also with `-DPaper.bypassHostCheck` (H5).
6. **Keep the handshake listener fast.** It runs for every new connection, before login.

## Operator notes

- Plugins that answer pings or handshakes with a remote lookup (MOTD services, proxy-style
  authentication, player-count sites) are the most likely source of loop stalls. Check their
  configuration first.
- ProtocolLib is auto-provisioned by default; see [PROTOCOLLIB.md](PROTOCOLLIB.md). ViaVersion and
  ViaBackwards are also auto-provisioned. Their handlers run on the loop, so review them with the
  same care as your own plugins.
- The audit did not measure any stall. If you suspect one, capture a thread dump during the
  incident and look for `Netty` event-loop threads in a blocking call.

## Open items

- No server change is made for H2, H3 or H6. Routing them off the loop changes Paper event
  semantics and needs its own review.
- H4 and H5 (hostname resolution in the handshake) are fixed by Minecraft feature patch 0037
  (IP literals only; pinned by `HandshakeAddressesTest`; static evidence, no runtime trace).
- Third-party plugin listeners were not read by the audit.
