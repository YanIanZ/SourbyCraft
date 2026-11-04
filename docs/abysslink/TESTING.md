# Validation checklist

The checklist below was written with the 2026-10-24 integration. Each row now carries a status:
the unit-test classes that cover it, the real-peer interoperability run of 2026-10-24
(*Latest evidence*), or **pending**. A named test class is coverage, not a recorded result: the CI
build job runs the suites on every push, and no per-row result is kept here. This file is the
tracked home of AbyssLink verification results; record new ones under *Latest evidence* with the
command, revision, JDK, actual output and artifact paths, and record unavailable checks as
skipped/pending.

**Status 2026-10-25 (PR #62):** PR #62 changed `AbyssLinkRuntime`, `AbyssLinkConnectionManager` and
`AbyssLinkClient` (configuration and connection-failure diagnostics, status wording; see
[INTEGRATION.md](INTEGRATION.md)). Code written and statically reviewed; not built; not tested by
the owner; no test was added for it. Every result below was produced on an older tree and needs a
rerun on a build that contains PR #62 (NEEDS RETEST).

The reference contract is Leviathan's `docs/abysslink/PROTOCOL-v1.md` and its
`world/leviathan/abysslink` sources, in the separate Leviathan repository (not in this tree; at
integration time they were concurrent, initially untracked work). Do not infer deployed
compatibility.

| Area | Required checks | Status |
| --- | --- | --- |
| Framing | Exact golden bytes; fragmented/coalesced frames; bad magic/version/type/flags; unsigned oversize rejection before allocation; zero/max payload; request-ID high bit preservation | Unit tests: `AbyssLinkFrameCodecTest` (golden vector both ways, header splits, byte-wise fragments, coalesced frames, magic/major/type/flags rejection, oversize before the body, maximum payload, scalar round trips). Golden heartbeat also in the interop run |
| TLV | Truncation, duplicates, invalid UTF-8, fixed widths, strict booleans, required/optional fields, byte-length boundaries and encoder oversize | Unit tests: `AbyssLinkFrameCodecTest` (duplicate ids, truncated headers/values, bounds both ways, wire shape, exact widths, boolean range) |
| Negotiation | Heartbeat required; lowercase `ping` maps to semantic PROXY_PING; a token the backend did not offer, a duplicate or a malformed token rejects the handshake; minor 0 selection | Partial. Unit tests: `AbyssLinkClientTest.noPingCapabilityAndRejectedAckFallBack` (heartbeat-only and rejected acknowledgement). Rejection of an unoffered or duplicate token and of a missing `heartbeat`: in code (`AbyssLinkSession`), no unit test; pending |
| Authentication | Verified CA and DNS/IP SAN; missing/wrong client chain/key; node certificate fingerprint mismatch; duplicate active node; no trust-all/plaintext fallback | Interop 2026-10-24: certificates/pin, wrong hostname, wrong pin. Unit tests: `AbyssLinkSettingsTest` (plaintext refused, half-configured client identity refused). Missing/wrong client key at runtime and duplicate active node: pending |
| Requests | Pending/cache caps 1/32/256 and 64-query/second budget; saturated writes; timeout; cache disabled/hit/miss; out-of-order and duplicate replies; request+UUID+session correlation | Unit tests: `AbyssLinkClientTest` (deduplication and bound, correlation and current-session cache, mismatched UUID, timeout and late result, duplicate cancellation). 64-query/second budget and saturated writes: no test identified; pending |
| Freshness | Old/future samples and clock skew; invalid latency; found=false; reconnect invalidates pending/cache; old callback cannot mutate new generation | Unit tests: `AbyssLinkClientTest` (sample expiry, previous generation cannot satisfy a new session, peer error completes all requests). Interop 2026-10-24: ping found/absent/stale, reconnect |
| Heartbeat | Response-only backend; exact one echo per proxy request; no originating probe/echo loop; idle timeout; RTT remains unavailable, never shown as measured zero | Unit tests: `AbyssLinkClientTest` (echo of the proxy's value, handshake timeout, liveness expiry with `rttNanos` 0). Interop 2026-10-24: heartbeat observed |
| Commands | Permission before lookup/work; self/other/console/command-block forms; immediate scheduler rejection; queued retirement; different sender/target regions; no Player getters in worker | Unit tests: `PingCommandTest` (permission before lookup, own name, console and command block, sender hop, disconnected target/sender, proxy result then labelled fallback, stopped or saturated I/O executor). On a running server: pending (owner) |
| Lifecycle | Disabled creates no I/O/timers; unavailable endpoint leaves startup/gameplay responsive; disconnected sender drops output without leaking admission; bounded/idempotent shutdown; world unload remains independent | Interop 2026-10-24: cleanup. Unavailable endpoint and invalid configuration on a booted server (the PR #62 diagnostics): pending (owner) |
| Configuration | YAML defaults/unknown keys/limits; a legacy TOML sibling is read until converted; the loader never writes; restart-only behavior | Unit tests: `AbyssLinkSettingsTest` (defaults without writing, configured values, limits, unknown keys, legacy TOML sibling). `/sourbycraft reload` listing changed AbyssLink keys as RESTART_REQUIRED: pending (owner) |
| Metrics/security | Immutable observational snapshots; frame bytes include 20-byte header but exclude TLS/TCP; no gameplay-counter mixing, identity leaks, command execution or threat-driven rule changes | Unit tests: `AbyssLinkTelemetryTest` (unavailable is degraded, saturation is advisory overload, threat details sanitized without policy change). Byte accounting and log/identity leaks on a running server: pending |

Still unverified anywhere: a production Leviathan distribution, gameplay forwarding, real player
latency, and the `/ping` self/other/command-block forms on a running server. These are owner
checks, not yet listed as open items in [`docs/TODO.md`](../TODO.md).

Golden zero-payload HEARTBEAT request 1: `4142594c01000300000000000000000000000001`.

Run the normal suites (the server module discovers tests through suite classes):

```bash
./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:test
```

A raw `--tests '*AbyssLink*'` filter also selects unrelated suite containers and can fail with `NoTestsDiscoveredException`. The 2026-10-24 integration run used a scoped init script selecting the direct AbyssLink, command and I/O-boundary test classes; that script and its output were not committed. On the macOS host used then, test-plugin's existing lsof fixture needed a canonical directory as `java.io.tmpdir`; `/var` versus `/private/var` aliases otherwise cause an unrelated false negative.

The repeatable real-peer harness is `scripts/abysslink/run_interop.py --help`. Pass the actual Leviathan Git root, a file containing the current server test runtime classpath, the Temurin 25 bin directory, and an output report path. It creates temporary test certificates, compiles the actual peer control classes, and runs the real client/server over mTLS. The injected native ping provider and entitlement supplier are test doubles; this does not qualify either production proxy distribution or real gameplay forwarding.

Unit/build/boot results establish neither performance nor production qualification.

## Latest evidence

- **2026-10-24** — real-peer mTLS interoperability, SourbyCraft client at commit
  `97d2026e` against Leviathan AbyssLink source digest `a24002ac5139…`:
  **16/16 checks PASS** (certificates/pin, HELLO/auth, heartbeat RTT, ping
  found/absent/stale, reconnect, negative wrong-hostname/wrong-pin, cleanup).
  JDK 25.0.2; harness `scripts/abysslink/run_interop.py`. Report:
  Leviathan `build/verification/abysslink-interop.json` (in the Leviathan repository, not here).
  NEEDS RETEST on a build containing PR #62 (`97d2026e` predates it).
- Harness fix in the same change: the absent/stale expectations used
  `Optional.filter(predicate -> false)`, a condition that can never pass; they
  now assert `Optional.empty()` explicitly.
- Scope limit: entitlement and native ping are test doubles. This run does not
  qualify a production proxy distribution, gameplay forwarding or real player
  latency.
