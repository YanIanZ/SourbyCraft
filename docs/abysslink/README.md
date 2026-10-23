# AbyssLink v1

AbyssLink is an optional SourbyCraft client for Leviathan's separate control listener. It carries handshake, heartbeat, proxy player-latency queries, health reports and advisory threat signals. It is disabled by default. Gameplay forwarding and admission keep their existing behavior.

The local settings, codec, metrics and client sources are implementation work. Published/deployed interoperability is not established by their presence. Verification results belong in the [workflow report](../../.workflow/ultracode/abysslink-v1/final-report.md); open checks are in [TESTING.md](TESTING.md).

## Wire agreement

Frames use a 20-byte big-endian header: `ABYL`, major/minor `1/0`, type, zero flags, unsigned32 payload length, unsigned64 request ID. Payloads are limited to 65,536 bytes, including TLV headers. TLVs use `id:u8 | length:u16 BE | value`. Unknown optional fields are skipped; duplicate/truncated fields, malformed UTF-8, incorrect fixed widths and invalid booleans are rejected.

Types are HELLO 1, HELLO_ACK 2, HEARTBEAT 3, PING_QUERY 4, PING_RESULT 5, HEALTH_REPORT 6, THREAT_SIGNAL 7 and ERROR 255. Request IDs preserve their 64-bit pattern in Java longs.

The design label **PROXY_PING** maps to the wire capability **`ping`**. It is never sent literally. Supported case-sensitive lowercase tokens are `heartbeat`, `ping`, `health`, `threat`; heartbeat is required.

The v1 backend responds to proxy heartbeats without originating its own probes. Echo each peer request once. Backend control RTT therefore remains unavailable (`rttNanos=0`); do not render zero as a measured latency. Earlier bidirectional-probe work is superseded by this response-only policy.

## Identity and privacy

SourbyCraft validates the proxy certificate chain and expected DNS/IP SAN. Leviathan validates the backend client chain and pins its leaf SHA-256 to the claimed node ID. This authenticates control nodes; it provides no Minecraft forwarding/player identity proof. PING_QUERY/RESULT carry the player's UUID, and the result does not prove attachment to the requesting backend.

Telemetry/logs exclude player identifiers, raw addresses, secrets and certificate material. Threat signals are bounded advisory aggregates; no arbitrary command execution or automatic rule changes are exposed.

Metrics expose label-free counters and an immutable observational snapshot. `bytesIn`/`bytesOut` count application frames including the 20-byte header, excluding TLS/TCP overhead; they remain separate from gameplay NetworkCounters. Counter reads are independently summed, not an atomic transaction. `rttNanos` is reserved for measured control RTT; v1 response-only mode leaves it unavailable (zero).

See [INTEGRATION.md](INTEGRATION.md) for setup and [FOLIA-SAFETY.md](FOLIA-SAFETY.md) for ownership.

## Compatibility reference

`/Volumes/Ext/REA/Leviathan/docs/abysslink/PROTOCOL-v1.md` now exists; it was absent at initial discovery. That document and `/Volumes/Ext/REA/Leviathan/src/main/java/world/leviathan/abysslink/` were concurrent untracked work during this integration. Review current source and recorded harness evidence; these docs do not pin or certify an external release.
