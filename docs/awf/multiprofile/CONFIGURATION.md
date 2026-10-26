# Configuration

Both features live in `sourbycraft_config/aurora.yml` (Build 49 layout). Every key is
**RESTART_REQUIRED**: the repository, ownership provider, pools and consumers are created once at
startup and cannot be resized or moved by `/sourbycraft reload`. Keys are read early, before the
configuration system loads, through `ProfileConfig.readEarly` / `FabricConfig.readEarly`.

The key table, defaults and seeded comments are in `AuroraKeys` (`AWF_MULTIPROFILE_KEYS`,
`AWF_REDIS_FABRIC_KEYS`). `ConfigExamplesTest` compares those keys and defaults with
`docs/config-examples/aurora.yml`; it does not read the tables on this page, so keep them in step by
hand.

The Build 50 PR #62 keys and descriptions below (the three repository keys and the changed meanings)
are code written and statically reviewed; not built; not tested by the owner, and not in the published
`v26.2-r50` prerelease.

## `aurora.awf.multiprofile.*`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | master switch; false creates nothing |
| `mode` | `standalone` | `standalone` or `distributed`. `distributed` starts only with `repository: redis`, `coordination.provider: redis`, `fencing-enabled: true` and a running fabric; `distributed` + `file` is refused. Distributed, like any `repository: redis` setup, is EXPERIMENTAL and not qualified (see [DISTRIBUTED-OWNERSHIP.md](DISTRIBUTED-OWNERSHIP.md)) |
| `default-slots` | `3` | slots for a new account (1–64, at most `max-slots`) |
| `max-slots` | `8` | hard ceiling, 1–64; `/awf multiprofile slots <player> <n>` sets one account's limit within 1..`max-slots` |
| `switching.enabled` | `true` | allow switches |
| `switching.timeout-ms` | `15000` | one switch's budget (1000–600000): checked at every phase and bounding world readiness, capture, apply and teleport; each rollback apply gets its own budget of the same length; also bounds a join's world preparation. A boot WARN fires when it is not below the claim lease (local 60 s, Redis `sessions.ttl-seconds`) |
| `switching.require-durable-save` | `true` | refuse a switch without a durable rollback point: the previous profile's snapshot, or on a player's first switch the pre-profile archive. `false` continues without one after a failed write or an oversized state |
| `isolation.inventory` | `true` | the 36 hotbar and storage slots |
| `isolation.armor` | `true` | armor and off-hand; `false` = they stay with the player across switches |
| `isolation.ender-chest` | `true` | ender chest |
| `isolation.experience` | `true` | level and progress |
| `isolation.health` | `true` | health |
| `isolation.food` | `true` | food and saturation |
| `isolation.potion-effects` | `true` | potion effects, infinite ones included; an amplifier outside 0–255 or an unknown key is skipped with a warning |
| `isolation.location` | `true` | respawn point (restored as a forced one, or cleared) and last location; destination order last location, respawn point, PRIMARY world spawn; join return (see [PROFILE-ISOLATION.md](PROFILE-ISOLATION.md)) |
| `worlds.idle-unload-seconds` | `300` | save+unload an idle world, checked every 30 s; `0` disables. Only AWF worlds bound to a profile, never the main world; a world with players counts as used. Worlds load on activation and on join, never at boot |
| `coordination.provider` | `local` | `local`: process-local claims (60 s lease, renewed every 15 s). `redis`: the fabric's session fabric, renewed every `sessions.heartbeat-seconds`; required by `distributed` and `repository: redis`. `redis` with `standalone` + `file` starts with a single-node WARN |
| `coordination.fencing-enabled` | `true` | validate the claim before snapshot and commit writes; `false` is refused with `distributed` and with `repository: redis` |
| `coordination.single-active-session` | `true` | when a join cannot claim the active profile (another holder), kick the joining player; `false` lets them play without a session claim: no quit snapshot is written for them, and an API save is refused while another process holds the profile (with the Redis provider and fencing on, always) |
| `directory` | `awf/profiles` | file repository directory; relative path, no `..`, no absolute path. Exclusive to one server (`repository.lock`). With `repository: redis` it is only read, and profile writes are refused while Redis is empty and this directory holds profiles, until `migrate-repository` copies them |
| `repository` | `file` | `file` = the directory, one server. `redis` = the Aurora Redis Fabric (STORAGE pool, keys `<prefix>fabric:mp:*`): each write is one Lua script that checks the writer's session claim (when the write carries one) and the record revision (when the record has one). Needs `coordination.provider: redis`, `fencing-enabled: true`, a running fabric and a Redis 7.0+ primary (no cluster, no replica endpoint). EXPERIMENTAL |
| `redis.require-aof` | `true` | `repository: redis` only. `true` refuses to start when Redis reports `appendonly` off. Even with AOF, `appendfsync everysec` can lose about 1 s of acknowledged writes, and a failover to a replica can lose acknowledged writes; one primary is the supported topology. `false` starts with a WARN |
| `redis.wait-for-aof` | `true` | `repository: redis` only. Snapshot, archive, account, profile and binding writes and the journal written before a target state is applied wait for `WAITAOF` (Redis 7.2+, up to half of `aurora.awf.redis.timeout-ms`) on the write's own connection. A write not confirmed in time is still applied, logged and counted in `/awf multiprofile status`; when Redis refuses `WAITAOF` it is turned off with one WARN |

## `aurora.awf.redis.fabric.*`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | master switch; false opens no socket |
| `services.sessions` | `true` | session claims (the MultiProfile fence); required by `coordination.provider: redis` |
| `services.coordination` | `true` | fenced claims for API users; no MultiProfile code writes them |
| `services.events` | `false` | streams; off until a consumer is deployed. With a consumer group that keeps up, acknowledged entries are trimmed and publishing continues; an unconsumed stream reaches retention and refuses publishes |
| `services.cache` | `false` | metadata cache for API users; no MultiProfile code uses it |
| `services.routing` | `true` | node directory; MultiProfile registers and heartbeats this node and removes it at shutdown |
| `services.telemetry` | `true` | render aggregate counters in `/awf redis status` |
| `pools.storage` | `8` | connections shared with the AWF world backend and the MultiProfile Redis repository |
| `pools.coordination` | `4` | connections for session and coordination scripts |
| `pools.events` | `2` | connections for streams |
| `pools.control` | `2` | connections for probes/directory/cache versions and the repository's startup probe |
| `cache.max-entries` | `10000` | per node |
| `cache.ttl-seconds` | `60` | bounds missed invalidations |
| `sessions.heartbeat-seconds` | `10` | claim renewal and node re-advertising |
| `sessions.ttl-seconds` | `45` | claim lifetime; must exceed the heartbeat |
| `events.retention` | `10000` | approximate stream length; publishing is refused when a group's lag plus pending reaches it, or with no group when the stream holds it |
| `events.batch-size` | `32` | entries per read |
| `events.max-deliveries` | `5` | retries before dead-lettering |
| `events.read-block-ms` | `500` | server-side block per read |
| `coordination.claim-seconds` | `45` | lease of a `CoordinationFabric` claim (API-only); MultiProfile claims use `sessions.ttl-seconds` |

Connection settings are **not duplicated**. The fabric reuses `aurora.awf.redis.uri`, `key-prefix`,
`timeout-ms`, TLS (`rediss://`), ACL credentials and `SOURBYCRAFT_AWF_REDIS_URI`. When the fabric is
off, the AWF Redis world backend opens its own pool sized by `aurora.awf.redis.pool-size`; when it is
on, it shares `pools.storage`. An invalid `aurora.awf.redis.uri` (or `SOURBYCRAFT_AWF_REDIS_URI` when the
key is absent) disables both the fabric and the Redis world backend; neither falls back to the default
server.

## Invalid values

An unusable value falls back to its default on its own and logs one warning naming the key. A fabric
whose every service is disabled is refused at startup. `mode: distributed` and `repository: redis` are
refused unless the fabric runs, `coordination.provider: redis` and `fencing-enabled: true`;
`mode: distributed` is also refused with `repository: file`. `standalone` + `repository: redis` is not
limited to one server. A refusal fails the MultiProfile boot stage with the reason; the server keeps
running without MultiProfile and never falls back to local ownership.

## Migration and rollback

There is nothing to migrate for existing AWF data: no world key, store format, template or registry
entry changes. New keys are additive and default to off (file repository, standalone, local provider).

Moving profiles into Redis is one-way: `/awf multiprofile migrate-repository confirm` copies the file
repository into an empty Redis repository and verifies it (procedure in
[OPERATIONS.md](OPERATIONS.md#migrating-to-the-redis-repository)). There is no export back: returning
to `repository: file` uses the untouched directory, so changes made in Redis after the migration are
lost on that path.

Rollback: set `multiprofile.enabled: false` (and optionally `fabric.enabled: false`) and restart. The
`awf/profiles` directory and the Redis keys are left untouched; existing profiles and snapshots stay
readable by the feature when it is re-enabled. Deleting that directory (or the `fabric:mp:*` keys) is
the only destructive step and it is never performed automatically.

Each server with the file repository must have its own exclusive profile directory. Its
`repository.lock` refuses a second server, but network filesystems that do not implement OS file locks
may not enforce it. Redis ownership coordination does not make file snapshots a shared, fenced
repository; only `repository: redis` is shared.
