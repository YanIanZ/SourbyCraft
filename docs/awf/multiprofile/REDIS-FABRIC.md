# Aurora Redis Fabric

Status: **implemented, disabled by default**. Real-Redis integration tests exist; no production soak.

## Namespace and keys

Everything lives under `<key-prefix>fabric:`; the world store keeps its own keys and formats and is
never migrated.

| Key | Purpose |
|---|---|
| `session:<profile>` | hash: session, node, owner, epoch, expiresAt, state |
| `session-epoch:<profile>` | counter that never expires (the session epoch) |
| `ownership:<kind>:<id>` | hash: owner, node, fence, expiresAt |
| `fence:<kind>:<id>` | counter that never expires |
| `events` | the event stream (`MAXLEN ~ retention`) |
| `events:dead` | dead-letter stream |
| `seen:<group>:<event>` | idempotency marker: `pending` or `done`, TTL 1 day |
| `cache-version:<namespace>` | invalidation version |
| `node:<id>`, `nodes` | node directory |

## Pools and workloads

One `RedisClient` per workload, created once, never resized:

| Workload | Default | Used by |
|---|---|---|
| `STORAGE` | `pools.storage` (8) | AWF Redis world backend (shared; replaces its own client when the fabric runs) |
| `COORDINATION` | 4 | session and ownership scripts |
| `EVENTS` | 2 | stream producers and blocking consumers |
| `CONTROL` | 2 | health, node directory, cache version reads |

Every pool is blocking RESP2 with the existing connect/read/write-progress timeouts and the existing
shutdown deadline (`RedisClient.deadline`). There is no non-blocking claim: calls must run on an I/O
worker. A disabled workload is refused at the accessor, not silently downgraded.

## Sessions

Lua compares with Redis `TIME`, so node clock skew does not affect expiry. A live session is exclusive;
a second node is refused as `duplicate`. `reclaim` requires the exact current epoch and increments it.

## Coordination and fences

See `DISTRIBUTED-OWNERSHIP.md`. The claim script refreshes without changing the fence for the same
owner; a different owner with a live lease is refused (`held`); the fence counter is incremented only
on a real claim.

## Events

**Disabled by default.** This build ships no event consumer, and an unconsumed stream grows to its
retention limit and then refuses every publish; enable `services.events` only together with a
consumer. When enabled:

- `XADD ... MAXLEN ~ retention`; publishing is refused with `FabricBackpressureException` when the
  stream already holds `retention` entries.
- Consumer groups start at the oldest retained entry. Each consumer owns one thread; `pollOnce`
  blocks for at most `read-block-ms`.
- Retry: an unacknowledged entry is reclaimed with `XAUTOCLAIM` after an idle time and retried up to
  `max-deliveries` (the entry is dead-lettered on the delivery after the budget), then copied to
  `events:dead` and acknowledged.
- Idempotency: `SET seen pending NX`. A `done` marker means the effect already ran; `pending` means a
  previous delivery died before acknowledging, so the effect is attempted again. Effects must
  therefore be idempotent, which is why profile commits carry a fence and a revision.
- An unknown event type or a malformed entry is acknowledged, never retried forever.

The stream and the cache are **off by default**: this build ships no consumer that would populate
them. Enabling `services.events` without a consumer fills the stream to its retention bound and then
refuses publishes; enabling `services.cache` without a consumer only keeps the service constructible.

## Cache

Bounded (`max-entries`), TTL-limited, single-flight per key, and version-aware: invalidation increments
`cache-version:<namespace>` in Redis; each node re-reads that version at most once a second and drops
the namespace when it changed. Version polling is used instead of Pub/Sub so a lost notification
cannot make a node permanently stale, and no blocking subscriber connection is needed.

The cache is never a source of truth. A Redis failure during a version check is a cache miss.

## Routing and telemetry

`RoutingFabric` keeps a node directory (`address`, `capacity`, `players`, `state`, `heartbeat`) with a
TTL of three heartbeats, and answers profile/world ownership from the claims themselves. It never opens
a connection to another node and does not depend on Leviathan or AbyssLink.

`services.telemetry` controls whether `/awf redis status` renders the counters. The operation,
failure, timeout and pool-saturation counters are fed by one observer on each `RedisClient`, so the
status line reports measured calls rather than constants. `FabricMetrics` is aggregate only:
operations, failures, timeouts, mean latency, pool saturation, fence rejections, lease-renew
failures, stream backlog/lag, cache hits/misses/evictions, publish / consume / retry / dead-letter /
duplicate counts. No per-player or per-profile labels.
