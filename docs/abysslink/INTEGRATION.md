# Configuration and integration

AbyssLink lives in `sourbycraft_config/aurora.yml`, under `aurora.abysslink`, beside the other Aurora services (the whole file is YAML since Build 49). All AbyssLink keys are **RESTART_REQUIRED**; `/sourbycraft reload` does not apply them (since PR #62 it lists a changed AbyssLink key under RESTART_REQUIRED until the restart). `AuroraKeys.ABYSSLINK_KEYS` is part of the seeded key table, so a newly created `aurora.yml` carries the keys and their comments; an existing file is not reseeded, and missing keys take their defaults. `AbyssLinkSettings` only validates and reads them (it never writes the operator's file).

## Secure setup

Install a proxy certificate whose SAN matches `authenticated-identity`, its trusted CA, and a backend certificate/private key. Configure Leviathan's trusted node mapping with this backend certificate's leaf SHA-256 fingerprint and the same `node-id`. Protect the private key with server-account-only permissions. Keep disabled until both ends are configured; then enable and restart.

```yaml
aurora:
  abysslink:
    enabled: false
    host: proxy.internal.example
    port: 25580
    node-id: sourbycraft-1
    tls: true
    authenticated-identity: proxy.internal.example
    trust-certificate: /etc/sourbycraft/tls/proxy-ca.pem
    client-certificate: /etc/sourbycraft/tls/backend-chain.pem
    client-key: /etc/sourbycraft/tls/backend-key.pem
```

`host` is the listener address. `authenticated-identity` is the expected DNS/IP certificate SAN, not a node label. An empty trust-certificate uses JVM trust roots. Both client certificate and key are required when enabled. TLS cannot be disabled; there is no plaintext/trust-all fallback.

## Defaults and limits

The example overrides host, identity, node and paths. Actual defaults and loader bounds:

| Key | Default | Rule |
| --- | --- | --- |
| enabled | false | Boolean |
| host / authenticated-identity | localhost / localhost | Nonblank, <=253 characters each |
| port |25580|1–65535|
| node-id |sourbycraft|`[A-Za-z0-9_.-]{1,64}`|
| tls |true|Must remain true|
| trust-certificate / client-certificate / client-key |empty|PEM paths; client pair required when enabled|
| reconnect-min-ms / reconnect-max-ms |500 /30000|100–60000 /100–300000; minimum <= maximum|
| heartbeat-ms |5000|1000–60000; response-only backend, no originating probe|
| timeout-ms |750|100–2000|
| ping-cache-ms |250|0–2000; zero disables caching|
| max-pending-requests |32|1–256; request and cache cap|
| telemetry-ms |10000|1000–300000|
| threat-reporting |true|Advisory aggregate signals|
| max-sample-age-ms |2000|100–10000; synchronize clocks|

Unknown keys inside `aurora.abysslink` are rejected. Invalid configuration leaves the optional service unavailable without blocking gameplay startup, and `/ping` keeps its local fallback.

## Diagnostics

Behaviour since PR #62 (code written and statically reviewed; not built; not tested by the owner):

- **Unreadable settings** (an unknown key, a value outside its bounds, a half-configured client identity): one WARN `AbyssLink: unavailable (configuration): <exception class: message naming the key>; fix sourbycraft_config/aurora.yml and restart; gameplay remains available`. `/ping` and `/perf network` show `AbyssLink: unavailable (configuration)`; before PR #62 they showed `disabled`.
- **Failed connection attempts** with valid settings: a TLS/DNS setup failure (certificate or key files, host resolution), a connect failure or a TLS handshake failure is logged on the first failed attempt and then on every 10th consecutive one, as `AbyssLink: <stage> failed (attempt N): <exception class: message>; retrying with backoff`. The exception text has control characters removed and is capped at 240 characters; the count restarts after an authenticated session. The cadence of 10 is a constant, not a setting. Before PR #62 these attempts were retried silently.
- **Status line.** After a failed attempt, `/ping` and `/perf network` show `disconnected (<stage> failed)` until an attempt completes the TLS handshake or authenticates. The status never includes an address or exception text, because `/ping` is visible to every player; the detail is only in the server log. A peer that completes TLS and then rejects authentication shows plain `disconnected`.
- A failure while starting the client itself is logged as `AbyssLink: startup failed: <exception>; gameplay remains available`.

Scripts or monitors that compare `disabled` or `disconnected` literally see the new forms. Owner checks for these messages are listed in [TESTING.md](TESTING.md).

## Runtime boundaries

Disabled mode creates no network connection, I/O group or reconnect timer. Enabled connection/TLS work runs asynchronously. Retry uses bounded exponential backoff and jitter; shutdown stops retries, closes channels and completes pending work before releasing owned resources.

Ping replies must match request ID, UUID and session generation. A hard 64-query/second backend budget falls back locally on saturation; it stays below the peer's default 100-frame/second ceiling. Reconnect invalidates old pending work and cache; stale/future samples are rejected. `/ping` keeps permission checks before work and falls back to its local measurement when the proxy signal is unavailable. See [FOLIA-SAFETY.md](FOLIA-SAFETY.md).

`/perf network` integration reads `AbyssLinkMetrics.snapshot()`. Application-frame bytes include the 20-byte header, exclude TLS/TCP overhead, and are separate from gameplay NetworkCounters. Snapshots are immutable but non-atomic across counters. Response-only heartbeat mode leaves control RTT unavailable (`rttNanos=0`), which must not be displayed as a measured zero latency; advisory threat counts are not blocked attacks or punishments.

Control-node authentication grants no forwarding trust, player permission, bypass or proof of backend attachment. Never log player UUID/address, raw payloads, certificates or secrets. Remote text must remain bounded/sanitized; no frame may execute commands or alter guards.

Compatibility, deployment and verification status: [TESTING.md](TESTING.md) (per-check status and the latest interoperability evidence).

AbyssLink has no pre-YAML history: an old TOML file simply gets the `aurora.abysslink` defaults when it is converted, and a missing `aurora.yml` takes the documented defaults without writing anything. Match `heartbeat-ms` to Leviathan's `heartbeat-interval-ms`; liveness closes after three expected intervals plus `timeout-ms`. The backend requires existing BungeeCord or Velocity forwarding to be configured before native `/ping` requests proxy samples. That prerequisite still provides no proof of per-player backend attachment; protect the backend with the existing forwarding secret/firewall policy.

Commands admit one in-flight operation per sender and at most 128 total. Requests allow four subscriber views per UUID/request lifetime and 64 wire queries/second; saturation uses the labelled backend fallback. An already-pending command returns a short busy message. Command tickets expire after 30 seconds and are lazily reaped on admission; target retirement releases immediately, while a retired sender can retain a bounded ticket until expiry.

Health uses the canonical immutable metrics snapshot: unavailable/stale/warming is degraded; pending-cap saturation, observed process CPU >=95%, or heap usage >=95% is advisory overload. These thresholds change no admission or runtime policy. Under degraded/overload state only every third telemetry interval is considered, and unchanged healthy reports are suppressed. Guard signals aggregate existing violation counters, which include LOG observations and must not be called attacks or kicks.
