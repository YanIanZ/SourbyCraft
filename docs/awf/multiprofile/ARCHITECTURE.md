# AWF MultiProfile — Architecture

Status: **implemented, disabled by default, not qualified for production**. This page describes what
the code does on this branch, not a roadmap.

## What exists

```
sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/
  awf/redis/fabric/        Aurora Redis Fabric (sessions, coordination, events, cache, routing, telemetry)
  awf/multiprofile/        AWF MultiProfile (profiles, snapshots, switching, worlds)
sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/profile/
                            public AuroraProfiles service and value types
```

The feature is split so a standalone server carries no Redis dependency:

| Mode | Ownership | Durable state | Redis |
|---|---|---|---|
| Standalone (default) | `LocalProfileOwnership` (process) | `FileProfileRepository` | not required |
| Distributed (experimental) | `RedisProfileOwnership` (session fabric, epoch/fence) | `FileProfileRepository` + fenced writes | required |

`aurora.awf.multiprofile.enabled: false` creates nothing: no directory, no listener, no service
registration, no thread. `AuroraProfiles` is then absent from the services manager, exactly as on a
server without the feature.

## Components

- **ProfileEngine** — the only writer of profile state. It runs every repository operation on the
  Resource Governor's bounded STORAGE lane and delegates player reads/writes to `PlayerStatePort`.
- **ProfileStatePort / BukkitPlayerStatePort** — captures and applies state inside one
  `EntityScheduler` task for the player, so a region tick cannot interleave with a capture.
- **ProfileRepository / FileProfileRepository** — framed, checksummed, atomically renamed records under
  `awf/profiles/` (accounts, profiles, bindings, snapshots, transition journals). Distributed nodes
  must mount the **same** directory; the ownership claim is the per-profile write lock, and the
  repository is not a multi-writer database (unique temp files prevent torn records, but two
  simultaneous writers would still lose one update — the claim is what prevents that).
- **ProfileStateCodec** — versioned binary codec; item bytes are opaque (Paper
  `ItemStack.serializeAsBytes`), bounds are checked before allocation, no reflective deserialisation.
- **ProfileOwnership** — local and Redis implementations; a claim carries a monotonic fence that the
  durable write path validates.
- **ProfileWorldPort / AwfProfileWorldPort** — uses only the public `AuroraWorlds` API: create from
  template, load, save, unload; never `Bukkit.unloadWorld`.
- **ProfileRuntime** — boot wiring, services-manager registration, join/quit integration, idle-world
  sweep, shutdown drain.

## What it deliberately does not do

- No PostgreSQL, no cross-database transaction. The switch is **recoverable, not atomic**.
- No SkyBlock/OneBlock/RPG mechanics. `type` is a consumer namespace; the core never interprets it.
- No advancement/statistics/recipe-book isolation. Those need an adapter (see
  `PROFILE-ISOLATION.md`).
- No remote command execution over Redis. Events carry data; a consumer decides what to do.
- No claim of split-brain safety beyond the enforced fence check. Distributed mode is marked
  experimental until a multi-node qualification exists.

## Data flow of a switch (summary)

```
player thread      profile I/O (storage lane)                     Redis (distributed only)
     |                     |                                            |
     |  switchProfile ----> validate account/target/journal             |
     |                     |  capture old state (entity task)            |
     |                     |  write snapshot + profile revision         |
     |                     |  claim target ownership ------------------>| epoch/fence
     |                     |  ensure AWF world ready                    |
     |  apply target <-----|                                            |
     |  teleport <---------|                                            |
     |                     |  validate fence, write active profile ---->| fence check
     |                     |  release previous ownership                |
```

If the commit write (`active profile`) did not happen, recovery rolls back to the old snapshot; if it
did happen, recovery finishes forward. The journal makes each step observable.

See `PROFILE-LIFECYCLE.md` for the state machine, `REDIS-FABRIC.md` for the Redis side and
`docs/awf/multiprofile/TESTING.md` for exactly what was and was not run.
