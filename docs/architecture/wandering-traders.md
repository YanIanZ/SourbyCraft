# Wandering traders (feature patch 0068)

Status: unit and patch-shape tests pass (`WanderingTraderCoordinatorTest`, 2026-10-22).
Nothing trader-specific was exercised on a server. Not qualified or performance-proven.

Each eligible world has one `WanderingTraderCoordinator` attached to its
`WanderingTraderData`. The global tick calls it after advancing game time, alongside
scheduled functions (0065), and passes the number of game ticks it advanced
(`tickCount`, more than one when the global tick catches up); the minute countdown
drops by that many ticks, so catch-up does not slow the trader clock. Region-local trader ticks return immediately. Region
merges and splits therefore do not reset or multiply countdowns.

The existing custom-spawner world selection is preserved. Debug worlds, disabled
`spawn_mobs` or `spawn_wandering_traders`, and empty worlds pause the countdown.
Paper's existing wandering-trader minute/day/chance settings are used; this change
adds no configuration surface. Gamerules are checked live, including on delivery.
The region callback uses vanilla's inclusive `nextInt(100) <= chance` comparison,
then the existing one-in-ten roll, POI search, biome/footprint checks and llama spawn.

## Ownership and failure

Entry requires `RegionOwnership.ensureGlobalTickThread`. The global tick copies
references from `ServerLevel.players()` (a copy-on-write world-wide list) and selects
one uniformly by index. It reads no player positions, health or connection state.
`OwnerHandoff` delivers to the chosen player's current entity owner. That callback
checks ownership, world membership, alive/removed/connection state and gamerules.
A dimension transfer is not redirected into spawning in another world.

Only one handoff may be pending per world. While pending, the countdown pauses;
slow or stopped regions cannot accumulate an unbounded attempt backlog. A rejected
handoff consumes its interval and retains the increased failure chance. A missing
or retired player consumes its interval; if the chance gate passes, chance resets
to minimum, matching vanilla's successful no-player return without spawning.
Completions return to global before modifying saved state. A runtime exception in
the owner callback releases pending bookkeeping in `finally` and remains visible
to the scheduler. Pending work is not persisted or replayed across restart.

Before vanilla's POI search, the helper requires ownership of a conservative
104-block radius around the player (POI 48 + spawn 48 + llama 4 + footprint margin).
A boundary attempt fails instead of loading or reading another region. Once the
trader is in the world the attempt always counts as a spawn (the chance resets),
even if a spawn-event plugin moved or removed it. Its home and wander target are set
first, then up to two llamas, each step only while this region still owns the trader
(`WanderingTraderCoordinator.completeSpawn`). A trader a plugin moved out of the
region keeps no home or llamas, because writing them would cross regions. This conservative boundary refusal can reduce successful spawns;
its gameplay effect has not been measured. No guard severity is relaxed.

## Persistence

World `data/minecraft/wandering_trader.dat` stores `spawn_delay`, `spawn_chance`
and the added `tick_delay`. Setters mark changes dirty. The coordinator and pending
flag have the lifetime of the world's saved data; there is no static world cache.
New data uses Paper defaults. Existing files without `tick_delay` initialize only
the minute countdown, retaining saved delay and chance. Old region-only counters
were never saved and cannot be recovered.

`WorldMetadataAutosave.WORLD_CONTAINED_TYPES` includes trader data, encoded on
global alongside other global-owned per-world saves; clean shutdown also saves it.
The due interval is consumed before dispatch. A restart while work is pending does
not retry that interval; an unacknowledged spawn may retain the failure chance.
As with other saved data, a crash can lose changes since the last completed save.

## Pending validation

The coordinator tests use a fake tick clock, deterministic RNG and ownership/handoff
backend; codec and autosave tests cover persistence. They cover catch-up countdown
by elapsed ticks and spawn completion after an ownership loss. A patch-shape test
pins the feature path, subject and hooks, and the removal of the dead region-local
`getTraderData()` / `RegionizedWorldData.wanderingTraderData`.

Status 2026-10-25: patch 0068 is in every published build since `v26.2-r48`; those builds passed CI's
build job, which applies the patches and runs `:sourbycraft-server:test` (this suite included). PR #62
does not change this feature. What follows is still open and belongs to the owner.

The PM must apply patches, compile and run server tests, then verify multi-region
worlds, merges/splits, join/leave/death and dimension transfers during handoff,
gamerule changes, plugin spawn cancellation/teleports, boundary footprints,
save/reload, autosave, shutdown with pending work and tick freeze/unfreeze on a
server. No server, persistence or plugin compatibility claim is made here.
