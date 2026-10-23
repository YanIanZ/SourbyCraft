# Validation checklist

This docs/metrics packet ran no Gradle suite, endpoint, boot or performance test. The table is a checklist, not passing evidence. Current results and remaining gates belong in the [parent workflow report](../../.workflow/ultracode/abysslink-v1/final-report.md), with commands, revision/JDK, actual output and artifact paths. Record unavailable checks as skipped/pending.

The reference contract is the concurrent, initially untracked Leviathan work at `/Volumes/Ext/REA/Leviathan/docs/abysslink/PROTOCOL-v1.md` and its `world/leviathan/abysslink` sources. The protocol document now exists; initial discovery's absence note is historical. Do not infer deployed compatibility.

| Area | Required checks |
| --- | --- |
| Framing | Exact golden bytes; fragmented/coalesced frames; bad magic/version/type/flags; unsigned oversize rejection before allocation; zero/max payload; request-ID high bit preservation |
| TLV | Truncation, duplicates, invalid UTF-8, fixed widths, strict booleans, required/optional fields, byte-length boundaries and encoder oversize |
| Negotiation | Heartbeat required; lowercase `ping` maps to semantic PROXY_PING; malformed tokens rejected; unknown valid tokens ignored; minor 0 selection |
| Authentication | Verified CA and DNS/IP SAN; missing/wrong client chain/key; node certificate fingerprint mismatch; duplicate active node; no trust-all/plaintext fallback |
| Requests | Pending/cache caps 1/32/256 and 64-query/second budget; saturated writes; timeout; cache disabled/hit/miss; out-of-order and duplicate replies; request+UUID+session correlation |
| Freshness | Old/future samples and clock skew; invalid latency; found=false; reconnect invalidates pending/cache; old callback cannot mutate new generation |
| Heartbeat | Response-only backend; exact one echo per proxy request; no originating probe/echo loop; idle timeout; RTT remains unavailable, never shown as measured zero |
| Commands | Permission before lookup/work; self/other/console/command-block forms; immediate scheduler rejection; queued retirement; different sender/target regions; no Player getters in worker |
| Lifecycle | Disabled creates no I/O/timers; unavailable endpoint leaves startup/gameplay responsive; disconnected sender drops output without leaking admission; bounded/idempotent shutdown; world unload remains independent |
| Configuration | YAML defaults/unknown keys/limits; a legacy TOML sibling is read until converted; the loader never writes; restart-only behavior |
| Metrics/security | Immutable observational snapshots; frame bytes include 20-byte header but exclude TLS/TCP; no gameplay-counter mixing, identity leaks, command execution or threat-driven rule changes |

Golden zero-payload HEARTBEAT request 1: `4142594c01000300000000000000000000000001`.

Run the normal suites (the server module discovers tests through suite classes):

```bash
./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:test
```

A raw `--tests '*AbyssLink*'` filter also selects unrelated suite containers and can fail with `NoTestsDiscoveredException`. The integration run used a scoped init script selecting direct AbyssLink/command/I/O-boundary test classes; its exact command and evidence are recorded in the workflow report. On this macOS host, test-plugin's existing lsof fixture needs a canonical directory on Ext as `java.io.tmpdir`; `/var` versus `/private/var` aliases otherwise cause an unrelated false negative.

The repeatable real-peer harness is `scripts/abysslink/run_interop.py --help`. Pass the actual Leviathan Git root, a file containing the current server test runtime classpath, the Temurin 25 bin directory, and an output report path. It creates temporary test certificates, compiles the actual peer control classes, and runs the real client/server over mTLS. The injected native ping provider and entitlement supplier are test doubles; this does not qualify either production proxy distribution or real gameplay forwarding.

Actual verification and remaining gates are recorded below after final checks. Unit/build/boot results establish neither performance nor production qualification.

## Latest evidence

- **2026-10-24** — real-peer mTLS interoperability, SourbyCraft client at commit
  `97d2026e` against Leviathan AbyssLink source digest `a24002ac5139…`:
  **16/16 checks PASS** (certificates/pin, HELLO/auth, heartbeat RTT, ping
  found/absent/stale, reconnect, negative wrong-hostname/wrong-pin, cleanup).
  JDK 25.0.2; harness `scripts/abysslink/run_interop.py`. Report:
  Leviathan `build/verification/abysslink-interop.json`.
- Harness fix in the same change: the absent/stale expectations used
  `Optional.filter(predicate -> false)`, a condition that can never pass; they
  now assert `Optional.empty()` explicitly.
- Scope limit: entitlement and native ping are test doubles. This run does not
  qualify a production proxy distribution, gameplay forwarding or real player
  latency.
