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
| `redis.wait-for-aof` | `true` | `repository: redis` only. Writes wait for `WAITAOF` (Redis 7.2+ with appendonly on, up to half of `aurora.awf.redis.timeout-ms`) on the write's own connection. **Format-1 store** (Build 50 behaviour): snapshot, archive, account, profile and binding writes and the journal written before a target state is applied are confirmed; one not confirmed in time is still applied, logged and counted in `/awf multiprofile status`; when Redis refuses `WAITAOF` the confirmation is turned off for this process with one WARN. **Format-2 store** (Build 51): every format-2 write is confirmed; one not confirmed in time, or refused by Redis, is applied but reported UNKNOWN to its caller (the engine re-reads and re-confirms before any effect; a login or save then fails) and the confirmation is never turned off by itself. At startup `WAITAOF` is probed once: when Redis cannot answer it (older than 7.2, or appendonly off), opening a format-2 store and starting a format-2 migration are refused with an ERROR naming this key, before any marker is written; set `false` to acknowledge writes from Redis's memory |

### Build 51: store format 2, names and types

Build 51 code written; not built by an agent; not tested by the owner. All five keys are
**RESTART_REQUIRED** (read early with the rest; the repository is opened once at startup). Co-op
membership and its keys are Build 52 (next section); nothing in this table enables it.

| Key | Default | Meaning |
|---|---|---|
| `types.allowed` | `[classic, ironman, stranded, bingo]` | policy types a create request may name (a list, or a comma-separated string). Another type is refused with `TYPE_NOT_ALLOWED`. It does not restrict the free consumer namespace (`ProfileRequest.type`). The type is stored and returned only; no ironman, stranded or bingo rule is enforced before Build 55, so list only `classic` if profiles that look restricted but are not are unwanted. An unknown name or an empty list: WARN, the default list is used |
| `types.default` | `classic` | the type of a profile created without one; must be in `types.allowed`. If it is not, the first allowed type is used and a WARN names this key |
| `names.policy` | `cute` | `cute` is the only policy: the server assigns a fruit or vegetable name from its pool at creation; unique per account (ignoring case), immutable, numeric suffix only when the pool is exhausted for the account. A request may ask for a specific pool name (`NAME_INVALID` for other text, `NAME_TAKEN` for a used one) |
| `migration.mode` | `off` | what happens to a store written before Build 51 (record format 1) at startup. `off`: nothing, the store stays format 1. `dry-run`: reads the store and reports what a migration would do, writes nothing. `apply`: copies the store to `migration.backup-dir`, then converts it to format 2 (one way, see below). Quote the value: YAML reads an unquoted `off` as `false`, which is accepted as `off`. `apply` without a valid `backup-dir` falls back to `off` with a WARN. Ignored while `enabled: false` (nothing is touched) |
| `migration.backup-dir` | empty | backup target for `apply`: a path relative to the server directory, or absolute. No `..` segment; it must not be inside or contain `directory` (checked textually when both are relative; the runtime that opens the path must resolve it and check again). Not used by `off` and `dry-run` |

There is deliberately **no** `storage.format` key. The store format is a property of the data, not a
setting: it is reported (format 1 = written before Build 51, format 2 = written by Build 51) and
cannot be chosen. A server with `enabled: false` reports nothing and reads nothing. A
`storage.format` line in the file is an undocumented key: one WARN per load, ignored.

### Build 52: co-op membership

Build 52 code written and compiled by an agent; not tested by the owner. All six keys are
**RESTART_REQUIRED**: they are read early with the rest, and no consumer re-reads them while the server
runs, so a change needs a restart (`/sourbycraft reload` does not apply it). Co-op is off by default.

| Key | Default | Meaning |
|---|---|---|
| `coop.enabled` | `false` | `true` accepts the co-op membership operations (convert, invite, accept, decline, cancel, leave, kick vote, label) and registers `/sourbycraft:coop`; the bare `/coop` is claimed after the plugins enabled (`ServerLoadEvent` STARTUP) only if no plugin registered one (otherwise an INFO names the holder). `false`: every one is refused with `COOP_DISABLED`, `/coop` is not registered and no co-op record is written; a store that already holds co-op profiles stays readable. Needs `enabled: true` (with `enabled: false` it is ignored with a WARN) and a format-2 store (`MIGRATION_REQUIRED` otherwise) |
| `coop.max-members` | `5` | members of one co-op, owner included; allowed `2` to `8`. Applies to new invitations and accepts; a co-op already above a lowered cap keeps its members. Outside the range: WARN, `5` |
| `coop.invite-expiry-minutes` | `5` | invitation lifetime, `1` to `1440` minutes, judged at commit time against the repository clock. Outside the range: WARN, `5` |
| `coop.kick-vote.expiry-minutes` | `10` | kick-vote lifetime, `1` to `1440` minutes. Outside the range: WARN, `10` |
| `coop.kick-vote.inactive-days` | `35` | in a two-member co-op, days the other member must have been inactive before a kick vote may start against them (`TARGET_NOT_INACTIVE` until then); `1` to `3650`. Build 52 records a member's last activity at join time and on membership changes, not from gameplay. Outside the range: WARN, `35` |
| `coop.allowed-types` | `[classic]` | policy types whose solo profile may be converted to co-op (a list, or a comma-separated string); checked at conversion only, so narrowing it later does not touch existing co-ops. A profile of another type: `TYPE_NOT_ALLOWED`. Build 51 enforces no ironman, stranded or bingo rule, so adding them gives those types co-op without any of their intended restrictions. An unknown name or an empty list: WARN, `[classic]` |

Not configurable in Build 52 (unchanged in Build 53): the per-account membership limit (it is the account's profile slot limit,
`default-slots`/`max-slots`), the size of a page (fixed, at most 32), the number of open invitations and
votes (store bounds, `CAPACITY_EXCEEDED`), and the salvage window (salvage delivery is Build 54).

### Build 53: authoritative login, shutdown capture, adapters

Build 53 code written; not tested by the owner. All three keys are **RESTART_REQUIRED** (read early with the rest;
`/sourbycraft reload` does not apply them). They apply to every profile, not only co-op. Accessors in
`ProfileSettings`: `login().authoritative()` (the `Authoritative` enum) / `authoritativeLogin()` (resolved against `coop.enabled`), `shutdown().captureDeadlineMillis()`,
`adapters().required()`.

| Key | Default | Meaning |
|---|---|---|
| `login.authoritative` | `auto` | `auto`, `true` or `false`. `auto` = authoritative exactly when `coop.enabled` is true, so a server without co-op joins exactly as Build 52 did (vanilla playerdata, saved by the server with the chunks). While authoritative login applies: a join restores the committed state of the player's active profile (for a co-op the member head) or, after an unclean previous session or the upgrade from Build 52, adopts a newer vanilla playerdata into it once ([PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md#authoritative-login-build-53)); every vanilla player save of the server (autosave) publishes a profile snapshot in lockstep; a join whose state cannot be restored or adopted is refused (`HEAD_UNREADABLE`, `RECOVERY_REQUIRED`, `ADAPTER_MISSING`, `ADAPTER_REQUIRED_FAILED`, `OWNERSHIP_CONFLICT`, `STATE_TOO_LARGE`, `WRONG_NODE`) instead of loading vanilla playerdata; a save that cannot succeed freezes and disconnects the player. `false` is the Build 52 behaviour with co-op on too: the join keeps vanilla playerdata, a quit that was not captured can let a player rejoin with items their profile snapshot does not hold, and co-op members cannot switch into a co-op (`COOP_SWITCH_UNAVAILABLE`). Not `auto`/`true`/`false` (a YAML boolean counts): WARN, `auto`. With `enabled: false` it does nothing; `false` with `coop.enabled: true` logs a WARN at every start, `auto` logs which way it resolved |
| `shutdown.capture-deadline-ms` | `10000` | at shutdown MultiProfile captures and saves the online players before the server removes them. This is how long it waits, `1000` to `60000`. At the deadline unsaved players keep their last committed snapshot and pending intents for recovery and shutdown continues (see [OPERATIONS.md](OPERATIONS.md#shutdown-capture-build-53)). Raising it can delay process exit by up to that long. Outside the range: WARN, `10000` |
| `adapters.required` | `[]` | profile state adapter ids (a list, or a comma-separated string; `[a-z0-9_.:-]{1,64}`, at most 32) treated as `REQUIRED` whatever the adapter's descriptor says ([PROFILE-ISOLATION.md](PROFILE-ISOLATION.md#adapter-contract-build-53)). It only raises strictness. A malformed entry is dropped with a WARN and the well-formed entries stay in force (a typo never relaxes the others). An id no plugin registers does nothing until a plugin registers it |

Not changed by Build 53: the `coop.*` keys keep their Build 52 meaning, with `coop.enabled` now also allowing member
play. `isolation.*` still decides which categories are captured; a disabled category stays in vanilla
playerdata, and authoritative login does not restore it.

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

Build 51 adds a one-way store migration (format 1 to format 2); see
[OPERATIONS.md](OPERATIONS.md#format-2-migration-and-rollback-build-51) for the procedure and the
rollback rule (restore the pre-migration backup into an isolated store; builds older than Build 51
refuse a format-2 store, and there is no in-place downgrade). The paragraphs below describe the
pre-Build-51 state of the stores and still hold for the Redis copy.

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
