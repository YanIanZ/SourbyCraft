# Network security guards

SEC-1 adds SourbyCraft-owned observation in `dev.iyanz.sourbycraft.security` and a
Minecraft feature patch `0034-SourbyCraft-security-guard-hooks.patch`. SEC-11 adds a
separate `SourbyCraft - connection rate limit hook` commit; the integrator must export
that commit as a feature patch before rematerializing sources.

Every leaf of `sourbycraft-security.yml` has a Java consumer. Each guard group defaults
to `mode: LOG`: count violations and emit at most one log per limit per 30 seconds across
all connections. A violation counts once per packet or admission event per limit. Logs contain the limit,
measured number and cumulative count; no packet content, player name or remote address.
Counts are available through `SecurityGuards.INSTANCE.violations(Limit)`.

`OFF` suppresses observations for the group. `KICK` is explicit opt-in: throws a Netty
`DecoderException` and uses the existing connection error/disconnect path. The native
codec limits still apply in every mode; LOG does not relax Minecraft's hard limits.
LOG is observation only and does not prevent oversized packets from reaching handlers.
Packet guards are separate from the TCP/login admission policy below. Neither protects
the OS TCP backlog or substitutes for a network firewall.

| YAML group | Runtime measurement |
| --- | --- |
| `crash-prevention.nbt` | NBT accounter allocation estimate and depth; decoded string/key UTF-16 chars; list/array element counts |
| `crash-prevention.sign` | Decoded per-line and total UTF-16 chars |
| `crash-prevention.anvil` | Decoded rename UTF-16 chars |
| `crash-prevention.recipe-book` | Encoded size of incoming recipe settings, seen-recipe and place-recipe packets |
| `crash-prevention.creative-item` | Encoded item/component bytes in the creative slot codec (legacy `max-nbt-size` name retained) |
| `packet-guard.book-edit` | Sum of decoded page and optional title UTF-16 chars |
| `packet-guard.custom-payload` | Payload data bytes after identifier, before byte-array allocation |
| `packet-guard.container-click` | Click packets per channel per monotonic 50 ms bucket |
| `packet-guard.recipe-book` | Recipe packets per channel per monotonic 50 ms bucket |
| `packet-guard.connection` | Decoded packet count and decompressed bytes per channel per monotonic second |
| `connection-guard.global` | Accepted TCP sockets and initial Login Hello starts across all TCP peers per monotonic second |
| `connection-guard.per-address` | The same events grouped by numeric transport IP, independent of source port |

The legacy `max-per-tick` spelling means a fixed 50 ms time bucket, independent of
region TPS. Windows are fixed buckets, not a sliding rate bound; traffic can burst
across a bucket boundary. Packet state belongs to the channel event loop and survives protocol
swaps through a channel attribute. It is reclaimed with the channel. Admission counters
use a separate bounded process-wide table shared by Netty loops. No new executors,
threads, world access or cross-region ownership transitions are introduced.

NBT observations run only inside PacketDecoder's try-with-resources scope, including
exceptional decode paths. Storage/plugin NBT operations outside that scope are unaffected.
Checks before custom-payload/list/array allocation can reject early in KICK mode; book,
sign, rename, creative-item and string checks observe after their bounded native decode.
NBT `max-bytes` is Minecraft's allocation accounting, not raw wire size. Modern component
codecs do not all use NBT; this does not claim to cover arbitrary component complexity.

All limits and modes are **LIVE after `/sourbycraft reload`**, loaded atomically together
with the utility reload path. A packet uses one immutable settings snapshot. Unknown
keys, malformed YAML, nonpositive/noninteger limits or invalid modes retain the previous
settings and report a partial reload. An absent file is seeded when the security loader
runs on boot or reload, with LOG defaults;
existing files are preserved, and missing new keys use defaults. Installing these Java/
Minecraft hooks initially is **RESTART_REQUIRED**.

Tests cover every limit, LOG/OFF/KICK, per-channel time windows, scope cleanup, invalid
reload retention, and complete shipped YAML leaf coverage. Build/test/boot evidence is
recorded in the current Codex block of `AGENT-COORDINATION.md`; these checks do not
establish adversarial soak or production qualification.

## TCP and login admission (SEC-11)

The accept hook is at the start of `ServerConnectionListener`'s TCP `initChannel`,
before packet codecs, plugin channel listeners and region connection registration.
An accepted TCP connection counts even if it never sends a packet; kernel SYNs and
socket backlog entries are outside this hook. The login hook is after the HELLO state
check in `ServerLoginPacketListenerImpl.handleHello`, before name/profile/authentication
work, and passes `Connection.getRemoteAddress()`. It counts a login start, not a completed
join; an invalid name may still count. With Velocity modern forwarding a third hook in
`handleCustomQueryPacket0` runs right after Paper stores the forwarded address and before
the profile/pre-login work (see the proxy paragraph below).

Defaults are **LOG**: global 1000 sockets/s and 100 login starts/s; per direct IPv4
address or IPv6 prefix 20 sockets/s and 5 login starts/s. These are fixed one-second
monotonic windows, so adjacent boundary bursts can approach twice the configured rate
in a rolling second. Delayed samples are charged to the newest process epoch already
observed; they cannot reopen an older window.

Every admission log line names the offending numeric IPv4 address or masked IPv6 prefix
(`peer=...`), and is still subject to the 30 s per-limit log budget.

For a tracked peer, the per-address check runs first. An explicit per-address
**KICK** rejection increments that peer's count but does not consume the global budget.
Peer **LOG/OFF** events still consume global budget, as do bypassed/untracked peers.
`OFF` suppresses its corresponding violation observations. Global KICK can still reject
all further admitted events in that second; set it for the intended reconnect workload,
including legitimate proxy-network restart bursts. The accept hook closes the new socket
before server registration; the login hook sends the normal login disconnect message.

Address keys use numeric address bytes only, with no DNS lookup: the TCP channel peer at
accept, `Connection.getRemoteAddress()` at login. Unresolved addresses bypass the policy.
Source ports share one budget; IPv4-mapped IPv6 shares its
IPv4 budget. IPv6 defaults to **/64**, controlled by
`connection-guard.per-address.ipv6-prefix-length` (**1..128**, LIVE after reload). Changing
the prefix re-keys IPv6 entries and can provide a new per-prefix allowance in the current
window. Ordinary mode/limit reloads retain tracked counters; global counters survive all
security reloads and prefix changes. Rules, prefix length and proxy policy are published
as one immutable snapshot per admission event; invalid configuration keeps the prior set.

Loopback (the TCP peer, or the forwarded address at login) skips per-address checks
while still counting globally, as Paper's `connection-throttle` does. At TCP listener
startup the native server passes Spigot `settings.bungeecord`, Paper
`proxies.velocity.enabled` and `proxies.proxy-protocol`. With any of them the TCP peer is
the proxy, so:

| Event | Direct | PROXY protocol / BungeeCord | Velocity modern forwarding |
|---|---|---|---|
| TCP accept, per-address | socket peer | **not applied** (peer is the proxy) | **not applied** |
| Login start, per-address | socket peer at Hello | forwarded address at Hello (the HAProxy handler / handshake split already replaced `Connection.address`) | forwarded address after the forwarding answer; Hello itself skips it |
| Global limits | counted | counted | counted once, at accept / Hello |

Paper's inherited throttle instead turns itself off entirely for BungeeCord/Velocity
(`CraftServer.getConnectionThrottle` returns -1) because it runs during the handshake,
before the BungeeCord address is applied; with PROXY protocol alone it stays on and keys
`getRemoteAddress()`. SEC-11 keeps per-address *login* limits behind proxies because its
hooks see the forwarded address. A forwarded address is only as trustworthy as the
forwarding: BungeeCord forwarding and PROXY protocol trust any client that can reach the
backend port directly, so the backend must be firewalled to the proxy (Paper's own
requirement). Spoofed forwarded addresses only spread over per-address entries; they cannot
grow the table or skip global limits. With Velocity enabled but online-mode on, Paper never
performs modern forwarding, so no per-address login limit applies. The resulting keying is
logged once when the listener starts (not on every reload). Proxy detection is
**RESTART_REQUIRED**, unlike the LIVE security limits/prefix; changing forwarding
configuration at runtime does not reconfigure this admission policy. Direct NAT users
still share their public IPv4/prefix allowance. The independent inherited handshake
throttle has its own semantics; see [LOGIN-GUARDS.md](LOGIN-GUARDS.md).
In-memory, Unix-domain and unresolved/non-socket channels have no TCP IP identity and
are skipped entirely.

The process-wide limiter stores at most **8192 address/prefix entries** plus one global
bucket. The map is access-ordered LRU; a new peer can evict at most one stale least-recently
used entry. Active entries are never evicted to replenish budgets. If every entry is
active, an untracked peer skips the per-address check and still consumes global budget;
there is no shared synthetic offender/overflow address. Prefix changes can leave older
keys present until normal stale eviction. There is no timeout thread or full-table scan.
A short monitor guards bounded in-memory work; logging and connection handling happen
after releasing it. The winning rate-limited log formats only numeric IPv4 or masked
IPv6/prefix context lazily. This is a shared CPU point; thread-safe counting alone does
not establish scalability or DoS qualification.

Tests cover all four limits, LOG/KICK/OFF, direct/loopback policy, BungeeCord/PROXY
protocol keying of logins by forwarded address, Velocity's late per-address check without
a second global charge, once-per-change proxy logging, live prefix validation and reload
retention, IPv4-mapped normalization, IPv6 /1,/64,/65,/128 keys, immutable keys, LRU
expiry/saturation (including the "one active oldest peer pushes every new address into
overflow" attack, which fails 0/900 with insertion order), out-of-order timestamps,
concurrent updates and withholding global charges on per-address KICK. Bytecode tests
(`AdmissionHookBytecodeTest`) pin the native accept, Hello and Velocity-forwarding hooks,
their ordering, that the login hooks pass `Connection.getRemoteAddress()`, and the startup
proxy-policy call; they do not prove live socket behavior. Exact
runs and native commits are recorded in `AGENT-COORDINATION.md`.

## SEC-1 review corrections and operating limits

The container-click default is now **256 operations per 50 ms bucket**. A drag release
can legitimately send start + one continue per slot + end as one burst; the earlier
20-packet default produced false positives. Existing YAML values are preserved, so an
operator retaining 20 should review it before enabling KICK. No automatic rate floor is
applied: an explicit very low KICK threshold can disconnect ordinary players.

The NBT byte default (2,097,152) equals Minecraft's native default allocation quota.
`defaultQuota()` rejects an over-quota read before our post-accounting observation runs,
so an empty SourbyCraft byte-violation counter does not prove the absence of rejected
oversize NBT. This observation can trigger with a lower operator limit or a larger native
quota, and never relaxes the native quota. `max-depth` now counts actual no-argument
NBT container pushes; Paper's pre-seeded `codecDepth * 3` bias is excluded from our metric.
Minecraft's native combined complexity guard remains unchanged.

The default custom-payload observation threshold is 8 KiB; the native codec cap is
32,767 bytes. Legitimate plugin/mod channels (including a large `minecraft:register`)
can exceed 8 KiB. LOG allows them, while KICK rejects them. Calibrate against the actual
plugins/client protocols before opting into KICK. The current KICK semantics disconnect;
these guards do not reproduce the historical drop/clamp behavior or silent creative-item
cancellation.

The relative path `sourbycraft-security.yml` resolves against the server working directory.
Security loading is part of the utility TOML config apply path. If utility initialization
cannot produce a configuration and returns early, that loader is not invoked and the
built-in LOG defaults remain; investigate the utility initialization error first. If a
security file is deleted, a later reload can seed a fresh one. Source defaults and a file
successfully loaded at runtime are distinct states.

NBT accounting hooks also run on storage/region threads; outside network decode they
perform a ThreadLocal lookup and return without counting/logging. The separate actual
NBT-depth bookkeeping also runs there. This overhead has not been profiled or qualified;
there is no zero-overhead/performance claim for the inactive path.

Rejected packet input is consumed before rethrowing the explicit KICK exception. This
prevents Netty's close-time `decodeLast` pass from replaying and recounting the same
frame. EmbeddedChannel tests with the real Connection exception handler cover both
pre-decode and post-decode rejection and assert channel closure and scope cleanup.
