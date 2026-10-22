# Multiverse-Core 5.8.1 and world settings during startup (2026-10-22)

## Finding

Sourby Demo panel (Epic Skyblock v9.7, v26.2-r48.1, `aurora.bridge.mode = "safe"`): Multiverse-Core 5.8.1 enables on
the server thread during startup (`initPostWorld -> enablePlugins`), loads each world's config and applies it through
`CraftWorld` (`setPVP -> setGameRule`, `setTicksPerSpawns`, ...). Every call was refused with
`Cannot modify server settings off of the global region` (`RegionizedServer.ensureGlobalTickThread`), and Multiverse
logged `Failed to load configuration` for SuperiorWorld, Dungeon, Mine and Spawn, so no per-world setting applied.

## Decision

At that point the region scheduler has not started: `RegionizedServer.init()` (which calls `TickRegions.start()`)
runs in `runServer` after `initServer` has loaded the worlds and enabled the plugins. Nothing ticks, and the server
thread is the only owner of world state, as on Paper. Paper feature patch 0025 therefore routes the 12 `CraftWorld`
world-settings setters (`setTime`, `setFullTime`, `setStorm`, `setWeatherDuration`, `setThundering`,
`setThunderDuration`, `setClearWeatherDuration`, `setHardcore`, `setTicksPerSpawns`, `setSpawnLimit`,
`setGameRuleValue`, `setGameRule`) through `dev.iyanz.sourbycraft.guards.WorldSettingsOwner.ensure`, same messages:

| caller | before | after |
| --- | --- | --- |
| server's running thread, scheduler not started (plugin enable) | refused | allowed (Paper behaviour) |
| any other thread, scheduler not started | refused | refused (`IllegalStateException`, logged) |
| any thread after the scheduler started | global tick only | global tick only (unchanged) |
| shutdown thread | refused | allowed (it owns everything once regions stop) |

Not changed: force-loaded chunks and world metadata stay global-tick-only; region ownership checks
(`TickThread.ensureTickThread`, ledger family `F-TICKTHREAD-ENSURE`) are untouched. Ledger: `F-GLOBAL-TICK` 29 -> 17
sites, note in `build-data/inherited-guards.json`. Tests: `WorldSettingsOwnerTest` (decision table + patch shape).

## Evidence

Agent test server, fresh world, `aurora.bridge.mode = "safe"`, Multiverse-Core 5.8.1 only:

- control (`opus-panel-fixes-mv-control`, jar without 0025): `Cannot modify server settings off of the global region`,
  `[Multiverse-Core] Failed to load configuration`;
- fix (`opus-panel-fixes-mv-fix`): no world-settings refusal; `mv modify world set pvp false` from the console after
  startup succeeds; stop exit 0.

## Remaining limit (not changed)

With the settings accepted, Multiverse's import of a world that has no spawn in its config
(`LoadedMultiverseWorld.setupSpawnLocation -> readSpawnFromWorld -> BlockSafety -> CraftBlock.getType`) reads blocks on
the server thread before any region owns them and is refused by the region ownership check (`Cannot read world
asynchronously`), so on a fresh server Multiverse reports `Failed to load worlds from config` and lists `world` as
UNLOADED. Worlds whose Multiverse config already stores a spawn (bytecode: `getSpawnLocation()` non-null and not
`NullSpawnLocation`) skip that read. Relaxing region ownership for startup block reads is a separate decision (it
would read and possibly load chunks off any region); Multiverse-Core declares no `folia-supported`.
