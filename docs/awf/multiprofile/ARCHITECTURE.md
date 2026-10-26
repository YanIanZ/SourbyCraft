# AWF MultiProfile — Architecture

Build 50, PR #62 (2026-10-25): code written and statically reviewed; not built; not tested by the
owner. The prerelease tag `v26.2-r50` (5f5a167) predates PR #61 and PR #62, so the fenced Redis
repository, the new switch order and the other changes on this page are in no published build
(publication route: owner decision PM-011). Evidence: [readiness](../../releases/BUILD-50-READINESS.md),
[PM matrix](../../releases/BUILD-50-PM-MATRIX.md) and [Tester Owner](../../tester%20owner.md).

Status: **implemented, disabled by default, not qualified for production**. Standalone with the file
repository is the supported shape. The shared Redis repository (`repository: redis`, and
`mode: distributed` on top of it) is EXPERIMENTAL and unqualified, with the gaps listed below. This page
describes what the code does on this branch, not a roadmap.

## What exists

```
sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/
  awf/redis/fabric/        Aurora Redis Fabric (sessions, coordination, events, cache, routing, telemetry, node identity)
  awf/multiprofile/        AWF MultiProfile (profiles, snapshots, switching, worlds, file and Redis repositories)
sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/profile/
                            public AuroraProfiles service and value types
```

The feature is split so a standalone server carries no Redis dependency:

| Shape | Ownership | Durable state | Redis | Status |
|---|---|---|---|---|
| Standalone (default) | `LocalProfileOwnership` (process) | `FileProfileRepository` (exclusive directory) | not required | implemented, unqualified |
| Standalone, Redis ownership | `RedisProfileOwnership` (session fabric) | `FileProfileRepository` | required | single node only (startup WARN) |
| Shared repository: `repository: redis`, `mode: standalone` or `distributed` | `RedisProfileOwnership` (session epoch is the fence) | `RedisProfileRepository` (fenced Lua commits) | required, 7.0+ primary | EXPERIMENTAL |
| `mode: distributed` + `repository: file` | — | — | — | refused at startup |

`aurora.awf.multiprofile.enabled: false` creates nothing: no directory, no listener, no service
registration, no thread. `AuroraProfiles` is then absent from the services manager, exactly as on a
server without the feature.

## Components

- **ProfileEngine** — the only writer of profile state. Every repository and ownership step runs
  through **ProfileIoQueue**: a bounded FIFO (4096 steps; refused with `INTERNAL_ERROR` when full) that
  hands one step at a time to the Resource Governor's STORAGE lane. Profile traffic therefore holds at
  most one lane slot and no lane worker waits on a profile lock. Ordering is FIFO across all accounts:
  there is no per-account parallelism, so one slow Redis call delays every profile step on the server.
  Player reads and writes are delegated to `PlayerStatePort`.
- **PlayerStatePort / BukkitPlayerStatePort** — captures and applies state inside one
  `EntityScheduler` task for the player (or synchronously on the owning thread inside
  `PlayerQuitEvent`), so a region tick cannot interleave with a capture.
- **ProfileRepository** — the authoritative commit point. Every mutation carries a
  **ProfileWriteGuard**: the claim it is made under and the revision the caller read.
  - **FileProfileRepository** — framed, checksummed, atomically renamed records under `awf/profiles/`
    (accounts, pre-profile archives, profiles, bindings, snapshots, transition journals). It takes an
    exclusive OS lock on `repository.lock` and compares revisions under its own lock; it does not check
    claims and is not a distributed fence: one server per directory.
  - **RedisProfileRepository** — the same records (`ProfileRecords`) under `<prefix>fabric:mp:*`; each
    mutation is one Lua script that checks the claim against the session fabric and the revision, then
    writes ([DISTRIBUTED-OWNERSHIP.md](DISTRIBUTED-OWNERSHIP.md)). Durability is Redis's (AOF, optional
    `WAITAOF`).
  - **ProfileRepositoryMigration** — one-way, verified copy of a file repository into an empty Redis
    repository (`/awf multiprofile migrate-repository confirm`).
- **ProfileStateCodec** — versioned binary codec; item bytes are opaque (Paper
  `ItemStack.serializeAsBytes`), bounds are checked before allocation, no reflective deserialisation.
- **ProfileOwnership** — local and Redis implementations. A claim carries a monotonic fence (the Redis
  session epoch) and an owner token; the provider's `incarnation()` says when two fences are comparable
  (local: per process; Redis: shared).
- **NodeIdentity** (fabric) — stable node id plus a per-JVM incarnation; a restart takes over its own
  previous claims at once only when `awf/node.lock` proves the predecessor stopped
  ([REDIS-FABRIC.md](REDIS-FABRIC.md#node-identity)).
- **ProfileWorldPort / AwfProfileWorldPort** — uses the public `AuroraWorlds` API: create from template,
  load, unload with a durable save first; never `Bukkit.unloadWorld`. It manages only AWF worlds (AWF's
  own name check excludes the main world and vanilla dimension names).
- **ProfileRuntime** — boot wiring and the startup refusals, services-manager registration, the
  join/quit listener (recovery, session claim, world preparation and return on join, quit snapshot),
  the switch freeze listener, the heartbeat (claim renewal, node advertising), the idle-world sweep and
  the shutdown drain.

## What it deliberately does not do

- No PostgreSQL, no cross-store transaction. The switch is **recoverable, not atomic**.
- No SkyBlock/OneBlock/RPG mechanics. `type` is a consumer namespace; the core never interprets it.
- No advancement, statistics or recipe-book isolation: they are shared by every profile of a player.
  A plugin can store its own data through an adapter (see [PROFILE-ISOLATION.md](PROFILE-ISOLATION.md)).
- No remote command execution over Redis. Events carry data; a consumer decides what to do.
- No claim of split-brain safety beyond the fenced Redis commits, which are unqualified; distributed mode
  is EXPERIMENTAL until a multi-node qualification (MP-010) passes.

## Known gaps

- A player's live state does not follow them between servers: a join keeps that server's vanilla
  playerdata and only a switch applies a profile snapshot.
- No quit snapshot at shutdown (feature patch 0081 closes MultiProfile before players are saved).
- No proxy or portal handoff; `RoutingFabric` has no transport.
- A crash after the commit and before the server saves vanilla playerdata is not covered (PM-028,
  owner decision).
- No Redis Cluster or replica topology; no MultiProfile probe plugin; no multi-node run.

## Data flow of a switch (summary)

```
player thread      profile I/O (ProfileIoQueue -> storage lane)       Redis (redis provider/repository)
     |                     |                                            |
     |  switchProfile ----> validate account/target/pending journal     |
     |                     |  claim target ---------------------------->| epoch (fence)
     |                     |  ensure AWF world ready                    |
     |  freeze, capture <--|  claim previous profile ------------------>| epoch (fence)
     |                     |  write rollback point (snapshot/archive) ->| fenced script
     |  apply target <-----|  journal APPLYING_TARGET ----------------->| fenced, WAITAOF
     |  teleport <---------|                                            |
     |                     |  validate claim, write active profile ---->| fenced script (commit point)
     |                     |  release previous claim, delete journal    |
```

If the commit write (`active profile`) did not happen, a failure after the target state reached the
player rolls back to the captured state (or leaves the journal `RECOVERING` for the next join); a
failure before that applies nothing. If it did happen, recovery finishes forward. A lost Redis reply
to the commit is settled from the account before anything is rolled back. The journal makes each step
observable.

See [PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md) for the state machine, [REDIS-FABRIC.md](REDIS-FABRIC.md)
for the Redis side and [TESTING.md](TESTING.md) for exactly what was and was not run.
