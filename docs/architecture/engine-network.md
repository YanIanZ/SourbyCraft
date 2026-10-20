# Aurora Network Engine — Ownership

> **Role:** T5 ownership document for Network. **Status:** Active; patch-count sentence corrected on 2026-10-07.
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

T5 deliverable for §11.5 of `docs/AURORA-FULL-TRANSITION.md`.

## Summary

**Aurora owns no network policy today.** It measures: the network lane's CPU, and (since Build 47
work) wire bytes, protocol packets and connections through `perf/NetworkCounters`, shown by
`/perf network`. The counting handlers are installed through Paper's `ChannelInitializeListenerHolder`,
which Paper marks as unofficial; the handlers are pass-through and their per-packet cost has not
been measured. This
document exists because the T5 gate asks for a boundary per domain, and a domain with an empty
implementation is still a boundary worth writing down — it is what stops the next reader
assuming the work was done.

## 1. What Aurora owns

| Service | Owns |
|---|---|
| `execution/ExecutionLane.NETWORK` | Attribution of packet encode, decode and socket work, by thread name: `Netty Epoll IO`, `Netty Kqueue IO`, `Netty NIO IO`, `Netty Server IO` |
| `perf/NetworkCounters`, `perf/NetworkMetrics` | Wire bytes in/out (pipeline head, after compression/encryption), packets in/out (beside `packet_handler`), connections opened; per-second rates sampled by the metrics collector |

There is no Aurora packet path, no compression policy, no queue-health
service, and no network *performance policy* in any feature patch. The Minecraft feature patches
that do edit network classes are the security/admission guards (0034 `PacketDecoder` + NBT/codec
hooks, 0036 `ServerConnectionListener`/`ServerLoginPacketListenerImpl` admission, 0037
`ServerHandshakePacketListenerImpl` literal-only addresses, 0040 `PacketDecoder` rejected frames,
0041 proxy-aware admission keys); none touches `ServerGamePacketListenerImpl` (checked 2026-10-20,
superseding the 2026-10-13 "no patch edits network classes" statement, which was true then).

## 2. Metrics

| Metric | Source | Surfaced by |
|---|---|---|
| Network lane CPU share | `LaneCpuSampler` / `ExecutionLane.NETWORK` | `/perf lanes` |
| Bytes and packets per second, each direction; totals; connections | `NetworkCounters` | `/perf network` |

Observed on the deployment server (idle, 0 players): `Network: 0.01 cores (5.6% of used)`. The
lane resolves and reports, so the measurement surface is real — it has simply never been put
under load.

## 3. What Aurora does not own

Everything §11.5 lists:

- per-packet-type instrumentation (counts are totals, not by packet type)
- allocation/copy *measurement* (the static code read is done: [network-allocation-copy-audit.md](network-allocation-copy-audit.md); no allocation rate or copy cost has been measured)
- compression policy implementation
- chunk packet path
- entity tracking packet path
- queue health

§11.5 also fixes one constraint in advance: **operator-visible compression settings remain
operator-owned.** Whatever Aurora eventually implements here, it does not get to quietly choose
an operator's compression threshold.

## 4. Why this domain is not started

Two reasons, both measurement:

1. **There is no network workload evidence.** `network-stress` exists in
   `run_baseline.py --workload`, but no certified run of it exists, and §4.5 allows no
   optimization without one.
2. **Network cost scales with connected clients**, which is the same constraint that blocks the
   entity domain ([client gap](engine-entity-ai.md#4-the-measurement-constraint-this-domain-has)).
   A network baseline with zero players measures an idle socket.

## 5. Gate status

| T5 requirement | Network |
|---|---|
| Ownership document | this file |
| Metrics | network lane CPU (`/perf lanes`) |
| Implementation boundary | §1 — measurement only, no policy |

Honest status: the lane is instrumented, the domain is unowned, and the first real step is a
certified `network-stress` run with clients attached, not code.

## 6. Event-loop blocking I/O audit (DEV-H P0, static read 2026-10-20)

Scope: every SourbyCraft-owned call that runs on a Netty event loop (`Netty * IO` threads):
the pipeline handlers in `src/main` and the added lines of the five network feature patches
(0034, 0036, 0037, 0040, 0041). Paper/Mojang code on the loop is upstream and not re-audited;
plugins (ViaVersion, ProtocolLib) are outside this audit. This is a code read, not a runtime
trace or a measurement.

| Event-loop entry | Work on the loop | Blocking I/O? |
|---|---|---|
| `NetworkMetrics.WireCounter` / `PacketCounter` (`ChannelDuplexHandler`, installed by Paper's `ChannelInitializeListenerHolder`) | `readableBytes()` + `LongAdder` increments, then `fireChannelRead`/`write` | none |
| 0036 `ServerConnectionListener.initChannel` → `SecurityGuards.connectionOpened` | `admission(...)`: numeric peer bytes, `AdmissionRateLimiter.record` (a `synchronized` O(1) map window shared by every event loop; one LRU eviction at most), `check` | none; the monitor is the only cross-loop contention point and holds no I/O |
| 0036/0041 `ServerLoginPacketListenerImpl.handleHello` → `loginStarted`, Velocity forwarding → `loginForwarded` | same `admission` path keyed on `Connection.getRemoteAddress()`; the `InetSocketAddress` is used as bytes, never resolved (`getHostName` is not called anywhere under `security/`) | none |
| 0034 `PacketDecoder.decode` → `SecurityGuards.begin` + per-packet `check`/`operation`/`packet`; NBT, payload, creative-item and recipe hooks | `ThreadLocal` scope, per-connection `long[]` windows in a channel attribute, atomic violation counters | none |
| 0034/0040 explicit KICK → `Rejected` (`DecoderException`) | `input.skipBytes`, rethrow; Netty closes the channel | none |
| 0037 `ServerHandshakePacketListenerImpl` | `HandshakeAddresses.literalSocketAddress`: Guava `InetAddresses.isInetAddress/forString` (string parsing), scope ids refused | none — this patch exists because `new InetSocketAddress(String, int)` resolved host names on the loop before it |
| 0041 `startTcpServerListener` → `configureProxyMode` | synchronized settings swap + one log line, on the thread starting the listener (server init), not an event loop | n/a |
| Guard logging (`SecurityGuards` logger → `SourbyLogger.warn`) | JUL → log4j `<Async>` appender (Paper's `log4j2.xml`); at most one line per limit per 30 s (`LOG_INTERVAL`) | none in the normal case; log4j's async queue blocks the caller only when its buffer is full, which the per-limit budget keeps the guards from causing on their own |
| `SecurityGuards.load` (`sourbycraft-security.yml`, file I/O, `synchronized`) | called from `SourbyCraftConfig.applyLiveConfig` (boot and `/reload`), never from the pipeline; the packet path reads the `volatile settings` snapshot and does not take that monitor | not on the loop |
| `ConnectionQueueHealth.sample` | runs on the metrics collector, reads `ChannelOutboundBuffer` counters off-loop | not on the loop |

Finding: no SourbyCraft code performs file, DNS, database or future-join I/O on a Netty event
loop. The one historical violation (host-name resolution in the handshake) is fixed by patch 0037
and pinned by `HandshakeAddressesTest`. Placement of the admission hooks before codecs and
authentication is pinned by `AdmissionHookBytecodeTest`; the decoder scope and KICK path by
`PacketDecoderHookTest` and `SecurityNbtHookTest`. Not covered: a runtime assertion (e.g. a
BlockHound-style detector) on a loaded server — that needs a live server and remains open.
