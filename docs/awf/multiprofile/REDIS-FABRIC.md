# Aurora Redis Fabric

Status: **implemented, disabled by default**. Real-Redis integration tests exist (last run on the
historical tree c117739, see [TESTING.md](TESTING.md)); no production soak. The Build 50 PR #62 changes
on this page (node identity, the MultiProfile repository namespace, event backpressure, pipelined
renewals) are code written and statically reviewed; not built; not tested by the owner.

## Namespace and keys

Everything lives under `<key-prefix>fabric:` (default `sourbycraft:awf:fabric:`); the world store keeps
its own keys and formats and is never migrated.

| Key | Purpose |
|---|---|
| `session:<profile>` | hash: session (`node/incarnation/owner`), node, incarnation, owner, epoch, expiresAt, state |
| `session-epoch:<profile>` | counter that never expires (the session epoch) |
| `ownership:<kind>:<id>` | hash: owner, node, fence, expiresAt (`CoordinationFabric`, API-only) |
| `fence:<kind>:<id>` | counter that never expires (`CoordinationFabric`) |
| `events` | the event stream (`MAXLEN ~ retention`) |
| `events:dead` | dead-letter stream |
| `seen:<group>:<event>` | idempotency marker: `pending` or `done`, TTL 1 day |
| `cache-version:<namespace>` | invalidation version |
| `node:<id>`, `nodes` | node directory |
| `mp:*` | the MultiProfile Redis repository (`repository: redis`); layout in [DISTRIBUTED-OWNERSHIP.md](DISTRIBUTED-OWNERSHIP.md) and below |

`mp:` holds `meta`, `account:<player>`, `profile:<profile>`, `binding:<profile>`, `snaps:<profile>`,
`snapidx:<profile>`, `legacy:<player>`, `stage:<snapshot>` (TTL 10 min), `journals:<player>`,
`jmeta:<player>`, `journal-player`, `journal-pending` and `journal-failed`. Records are the same framed,
checksummed records as the file repository, verified on every read. One commit touches several of these
keys and a session key, which a Redis Cluster puts in different slots, so a cluster is refused.

## Pools and workloads

One `RedisClient` per workload, created once, never resized:

| Workload | Default | Used by |
|---|---|---|
| `STORAGE` | `pools.storage` (8) | AWF Redis world backend (shared; replaces its own client when the fabric runs) and the MultiProfile Redis repository |
| `COORDINATION` | 4 | session and coordination scripts |
| `EVENTS` | 2 | stream producers and blocking consumers |
| `CONTROL` | 2 | health, node directory, cache version reads, the repository's startup `INFO` probe |

Every pool is blocking RESP2 with the existing connect/read/write-progress timeouts and the existing
shutdown deadline (`RedisClient.deadline`). There is no non-blocking claim: calls must run on an I/O
worker. A disabled service is refused at its accessor, not silently downgraded; MultiProfile with
`coordination.provider: redis` therefore needs `services.sessions: true`. An unusable
`aurora.awf.redis.uri` (or `SOURBYCRAFT_AWF_REDIS_URI`) keeps the fabric off; it never falls back to the
default server.

## Node identity

`NodeIdentity` gives this server a stable node id and a per-JVM incarnation:

- `awf/node-id` holds a persisted random id (an operator may write any non-blank text). The node id is
  `node-` plus 12 hex digits of SHA-256 over that id, the host name and the server directory; it names
  the server in claims, routing records and `/awf redis`. It is not proof of being one process: a copied
  directory on a host with the same name and path has the same id.
- The incarnation is a random UUID per JVM. Every session claim and every AWF Redis lease token carries
  it, and only the incarnation that made a live claim may refresh, validate or release it.
- `awf/node.lock` is held by the process for its whole life; while holding it the process writes its
  incarnation and a fingerprint (the lock file's device and inode plus the kernel boot id) to
  `awf/incarnation`. The next process takes the recorded incarnation as its proven-stopped predecessor
  only when the fingerprint is its own: same lock file, same boot. Only then may it take over that
  predecessor's live claims (with a new epoch). A copied directory, a cloned volume, another host or a
  reboot gives another fingerprint and waits for expiry.

The lock proves a stopped predecessor for processes on one host, and across hosts only on a network
filesystem that implements OS file locks. Deleting `awf/node-id` changes the node id, so earlier claims
wait for expiry. `awf/` must not be shared by two running servers; a second one gets no predecessor and
logs a WARN.

## Sessions

Lua compares with Redis `TIME`, so node clock skew does not affect expiry. A live claim is exclusive: a
claim by another node, or by another incarnation of this node id that is not its proven-stopped
predecessor, is refused as `duplicate`. The same session refreshes with the same epoch; the same node and
incarnation with a new owner token replaces the token and keeps the epoch; a takeover after expiry, or of
the proven-stopped predecessor, increments the epoch. `reclaim` requires the exact current epoch and
increments it (`/awf multiprofile reclaim`). `refreshAll` renews many claims in one pipeline; an error
reply for any claim fails the whole call, and the caller then keeps every claim. `foreignHolder` treats a
holder with another incarnation (or a record without one, written before Build 50) as another process.
The `RedisProfileRepository` write scripts read this hash to fence each write; see
[DISTRIBUTED-OWNERSHIP.md](DISTRIBUTED-OWNERSHIP.md).

## Coordination and fences

`CoordinationFabric` is **API-only**: no MultiProfile code writes a coordination claim; profile claims
live in the session fabric. The claim script refreshes without changing the fence for the same owner; a
different owner with a live lease is refused (`held`); the fence counter is incremented only on a real
claim. `/awf redis ownership <uuid>` shows such a claim and labels it API-only; profile holders are shown
by `/awf multiprofile owner <profile>` (or `/awf redis sessions <profile>`).

## Events

**Disabled by default.** This build ships no event consumer; enable `services.events` only together
with a consumer. When enabled:

- `XADD ... MAXLEN ~ retention`. Trimming drops acknowledged history, so a stream whose consumer groups
  keep up keeps accepting events. Publishing is refused with `FabricBackpressureException` when a
  group's unfinished entries reach `retention`: `XINFO GROUPS` `lag` (not yet delivered) plus `pending`
  (delivered, not acknowledged); a group whose lag Redis cannot compute counts as the larger of the
  stream length and its pending count. With no group (or `XINFO` refused), the stream length is the
  bound, so an unconsumed stream still fills to retention and then refuses every publish. The check and
  the `XADD` are separate calls, so concurrent publishers can overshoot it slightly. The bound is a
  count, not a position: an entry left pending while `retention` newer entries are published can be
  trimmed before it is reclaimed.
- Consumer groups start at the oldest retained entry. Each consumer owns one thread; `pollOnce`
  blocks for at most `read-block-ms`. The consumer-lag metric is the polling group's lag plus pending.
- Retry: an unacknowledged entry is reclaimed with `XAUTOCLAIM` after an idle time and retried up to
  `max-deliveries` (the entry is dead-lettered on the delivery after the budget), then copied to
  `events:dead` and acknowledged.
- Idempotency: `SET seen pending NX`. A `done` marker means the effect already ran; `pending` means a
  previous delivery died before acknowledging, so the effect is attempted again. Effects must
  therefore be idempotent.
- An unknown event type or a malformed entry is acknowledged, never retried forever.

MultiProfile publishes `profile created`, `save completed`, `switch completed` and `recovery required`
inside its I/O steps when `services.events` is on; a failed publish is logged and never fails the
operation.

## Cache

**API-only and off by default**: no MultiProfile code reads or writes it. Bounded (`max-entries`),
TTL-limited, single-flight per key, and version-aware: invalidation increments
`cache-version:<namespace>` in Redis; each node re-reads that version at most once a second and drops
the namespace when it changed. Version polling is used instead of Pub/Sub so a lost notification
cannot make a node permanently stale, and no blocking subscriber connection is needed.

The cache is never a source of truth. A Redis failure during a version check is a cache miss.

## Routing and telemetry

`RoutingFabric` keeps a node directory (`address`, `capacity`, `players`, `state`, `heartbeat`) with a
TTL of three heartbeats (at least 15 s), and answers profile/world ownership from the claims themselves.
MultiProfile registers this node and re-advertises it every `sessions.heartbeat-seconds`; the address is
always empty (no setting exists yet), and `ProfileRuntime.close` removes the entry at shutdown (best
effort; it also expires on its TTL). It never opens a connection to another node and does not depend on
Leviathan or AbyssLink; there is no proxy transport.

`services.telemetry` controls whether `/awf redis status` renders the counters. The operation,
failure, timeout and pool-saturation counters are fed by one observer on each `RedisClient`, so the
status line reports measured calls rather than constants. `FabricMetrics` is aggregate only:
operations, failures, timeouts, mean latency, pool saturation, fence rejections, lease-renew
failures, stream backlog/lag, cache hits/misses/evictions, publish / consume / retry / dead-letter /
duplicate counts. No per-player or per-profile labels. `/awf redis` diagnostics run on the
administrative I/O executor (64 tasks, no queue), not the storage lane, and answer `Busy: ...; try again
shortly` when it is full.

## Redis ACL for MultiProfile

Commands called inside a Lua script are checked against the ACL user too. The Redis repository and the
session fabric need: `EVAL`, `EVALSHA`, `INFO` (startup refuses without it), `TIME`, `HGET`, `HMGET`,
`HSET`, `HSETNX`, `HDEL`, `HGETALL`, `HLEN`, `HVALS`, `HEXISTS`, `HINCRBY`, `GET`, `SET` (with `PX` and
`NX`), `DEL`, `ZADD`, `ZRANGE`, `ZREVRANGE`, `ZREM`, `ZREMRANGEBYRANK`, `SADD`, `SREM`, `SSCAN`, `INCR` and
`PEXPIRE`. `WAITAOF` and `CONFIG GET` are optional (without them the AOF wait turns itself off and the
fsync policy is shown as unknown). The routing directory, events and `/awf redis` use further commands
(`PING`, `EXISTS`, `SMEMBERS`, stream commands).
