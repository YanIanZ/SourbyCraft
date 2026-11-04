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

## Build 51 additions

Build 51 code written; not built by an agent; not tested by the owner. The profile store moves to
record format 2 (file and Redis) with a one-way migration from format 1 and a backup first; builds older
than Build 51 refuse a format-2 store ([OPERATIONS.md](OPERATIONS.md#format-2-migration-and-rollback-build-51)).
Profiles carry a name (unique per account, immutable) and a policy type (`classic`, `ironman`,
`stranded`, `bingo`), exposed by `ProfileDetails`; the types are stored but their rules are not
enforced yet. Salvage delivery and the shared bank are planned for Build 54, type rules and the audit log
for Build 55 ([plan](../../releases/BUILD-51-55-PLAN.md), [design](COOP-DESIGN.md)); none of that exists
in Build 52. Co-op membership is Build 52 (next section).

## Build 52 additions

Build 52 code written and compiled by an agent; not tested by the owner. Co-op membership (COOP-DESIGN
slice 4): a solo profile converts one way to co-op; owners and managers invite; invitees accept or
decline; members leave; unanimous kick votes remove a member (the owner's approval comes with an
owner-started vote, and a two-member co-op needs the target to be inactive first). Every membership change
is a recoverable intent on a format-2 store, on the file and the Redis repository, using the Build 51
claims, revision vector and reservations. Public surface: the `Coop*` types and the co-op methods of
`AuroraProfiles`, three asynchronous events ([API.md](API.md#co-op-membership-build-52)); configuration:
six RESTART_REQUIRED keys under `coop.*` ([CONFIGURATION.md](CONFIGURATION.md#build-52-co-op-membership)),
default off. A removal durably consumes the membership epoch and records an unavailable-to-delivery
salvage entry.

Build 53 adds member play: a member switches into the co-op with their own head and snapshots, authoritative
login restores the committed head on join, an online member can be removed after a gated capture and switch-out,
and shutdown captures online players before the server removes them
([PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md#member-switching-and-play-build-53)); the adapter contract gains
REQUIRED and BEST_EFFORT ([PROFILE-ISOLATION.md](PROFILE-ISOLATION.md#adapter-contract-build-53)).

Deliberately not in Build 53: owner transfer and kicking the owner (refused, no planned build); the shared bank,
collections and salvage delivery (Build 54); type rules, the switch guard and the audit log (Build 55). Joining a co-op profile on a node other than the one holding
its bound world is refused (OD-1); the co-op metadata may be in a shared store, but the world is loaded on
one node.

## What it deliberately does not do

- No PostgreSQL, no cross-store transaction. The switch is **recoverable, not atomic**.
- No SkyBlock/OneBlock/RPG mechanics. `type` is a consumer namespace; the core never interprets it.
  The Build 51 policy type (`ProfileType`) is separate and also has no enforced rules yet.
- No advancement, statistics or recipe-book isolation: they are shared by every profile of a player.
  A plugin can store its own data through an adapter (see [PROFILE-ISOLATION.md](PROFILE-ISOLATION.md)).
- No remote command execution over Redis. Events carry data; a consumer decides what to do.
- No claim of split-brain safety beyond the fenced Redis commits, which are unqualified; distributed mode
  is EXPERIMENTAL until a multi-node qualification (MP-010) passes.

## Profile I/O queue (Build 55, PM-091)

Every repository and ownership step of the engine goes through `ProfileIoQueue` on the Resource Governor storage
lane. Build 51 gave the steps one FIFO holding one lane slot. Build 55 keeps that shape and adds **partitions**
(`io.partitions`, default 1 = unchanged): a step is keyed by the account and profile ids it touches, each id hashes
to a partition, and a step is handed to the lane when none of its partitions is held by a running step and no
earlier waiting step shares one (FIFO per partition, so per account; a later unrelated step may pass a blocked one).
Steps that touch unknown or many records — create, delete, lock/archive, every co-op, bank and salvage step,
recovery, startup, the heartbeat — are **barriers**: they wait for every running step and block every later one.
Keyed today: listing/reading, the switch run (player, target, previous profile), save and the autosave/quit capture,
admission, login restore, session release.

No deadlock: partitions are acquired all-or-nothing at dispatch under the queue lock; a running step never waits for
another (outcomes publish after its partitions are released); the oldest waiting step is shadowed by nothing, so it
runs once the running steps that hold its partitions finish, and by induction every step does. Bounded: 4096 waiting
steps (refused beyond), at most one running step per partition and never more in flight than the storage lane has
threads (`aurora.scheduler.storage-threads`; review P2-6: a step handed to the lane beyond its threads would only queue
in front of AWF world commits). A step the lane rejects is completed as refused and the dispatch loop continues without
recursing (review P3-13). Queue wait is sampled per dispatch for
`/perf multiprofile`. With one storage lane thread the partitions change nothing observable; the relief appears with
`aurora.scheduler.storage-threads` above 1 and `io.partitions` above 1, which is unqualified until the owner's soak.

Other Build 55 components: `ProfileAudit` (ring + daily files, its own single-slot queue on the lane),
`ProfileSwitchGuards` (guards and cooldown, in memory), `ProfileMetrics` (fixed rings and counters). None of them is
consulted by recovery or persists anything the store format knows.

## Known gaps

- A player's live state does not follow them between servers. While authoritative login applies (Build 53;
  `login.authoritative` `auto` with co-op enabled, or `true`) a join restores the committed state of the active
  profile on that server and every vanilla player save publishes a snapshot; otherwise it keeps that server's vanilla
  playerdata and only a switch, quit or shutdown writes a profile snapshot.
- Shutdown captures online players before they are removed (Build 53), bounded by
  `shutdown.capture-deadline-ms`; a player not saved by the deadline, or a killed process, keeps the last committed
  snapshot.
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
