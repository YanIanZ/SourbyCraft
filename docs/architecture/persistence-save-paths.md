# Persistence save paths under region threading

> **Role:** who writes which piece of world state, on which thread, and when. **Status:** Active, verified
> against the materialized sources on 2026-10-21 (Paper `9240f586`, engine baseline + feature patches
> 0027/0056). Line numbers are those of the materialized files on that day; re-check with `rg` before
> relying on one. Entry point: [AURORA.md](AURORA.md); storage ownership: [engine-storage.md](engine-storage.md).

Vanilla saves everything from one main-thread autosave (`MinecraftServer.tickServer` →
`saveEverything` → players, `saveGlobalData` (`level.dat` + the server `data/`), every level's
`saveLevelData` (its `data/`) and its chunks). Folia split that: chunks, entities, POI and players are
saved by the region that owns them, a short whitelist of global data is saved by the global tick, and
**everything else was written only at a clean stop** (`RegionShutdownThread`). Canvas widened the
whitelist; SourbyCraft widened it again (0027, 0056). This document is the ledger of what is left.

## 1. The five save paths

| Path | Thread | Trigger | What it saves | Evidence |
|---|---|---|---|---|
| Region autosave | each region tick | every tick, per region | **Chunks + entities + POI**: `ChunkHolderManager.autoSave` saves at most `max-auto-save-chunks-per-tick` (24) holders whose `lastAutoSave` is older than `chunks.auto-save-interval` (paper-world, 6000 ticks); a chunk save writes its entity chunk and POI chunk with it. **Players**: `PlayerList.saveAll(interval)` saves the region's local players whose last save is older than `player-auto-save.rate` (paper-global, `-1` → bukkit.yml `ticks-per.autosave` = 6000 ticks), at most `max-per-tick` per tick; a player save is NBT + stats + advancements. `saveIncrementally(false)` is otherwise a no-op. | `MinecraftServer.java:1723-1742` (`if (false && fullSave)` at 1733 — level data deliberately skipped), `ChunkMap.java:413`, `ChunkHolderManager.java:306-336`, `PlayerList.java:1137-1170`, `:487-500`, `ServerLevel.java:1611-1617` |
| Global-tick autosave | global tick | wall clock, `config/canvas-server.yml` `autosave.autosave-frequency` (default `5m`, LIVE on config reload; `AUTOSAVE_SPAN`) | **Server `data/`** (`autosaveSafeGlobalData`): scoreboard, stopwatches, Canvas pearls, custom boss events, the server clock when `time.affects-all-worlds`; 0056: random sequences, `/data storage` containers, `level.dat`. **Per world `data/`** (`autosaveSafeWorldData`): maps + map index, per-world clocks, weather, game rules; 0027: `paper:level_overrides` (game time, spawn, game type, difficulty); 0056: the world border. Each selection is a `SavedDataStorage.saveSpecific` call: encode on the global tick, file write on `Util.DIMENSION_DATA_IO_POOL`, not joined. | `RegionizedServer.java:262-313` (call at 308), `:335-398`, `:400-470` (called from `autoSaveMaps`, 649-651), `GlobalConfiguration.java:55,203,596-626`, `SavedDataStorage.java:192-241` |
| Dragon fight autosave | the region owning the fight | every `bukkit.yml ticks-per.autosave` ticks of wall time | `ender_dragon_fight.dat` (`saveSpecific(EnderDragonFight)`); `save-all` forces it on the next region tick. | `EnderDragonFight.java:226-252`, `ServerLevel.java:923-925`, `SaveAllCommand.java:47-50` |
| `/save-all [flush]`, `World#save` | global tick + every region | command / plugin | `save-all`: `autosaveSafeGlobalData(true, true)` and `autosaveSafeWorldData(true, true, world)` for every world (forced, joined; the 0027/0056 selections included), then a `SaveAllTicket` per region (chunks via `saveAllChunksRegionised`, local players). **Bukkit `World#save` only propagates the region tickets**: chunks and players, no level data of any kind. | `SaveAllCommand.java:32-80`, `MinecraftServer.java:1886-1897`, `CraftWorld.java:1147-1157` |
| Clean shutdown | `RegionShutdownThread` (after `TickRegionScheduler.halt`, so no tick runs) | `stop` | players + in-flight pearls; per world: chunks of every region, then `ServerLevel.saveLevelData(true)` = `scheduleSave().join()` of **every** dirty `SavedData` of that world; then `MinecraftServer.saveGlobalData(true)` = scoreboard store, **`level.dat`**, `saveAndJoin` of every dirty server `SavedData`; then `PlayerList.saveAll()`; `stopPart2` closes the storages (another `saveAndJoin`). | `RegionShutdownThread.java:273-357` (`saveLevelData` 72-77 and 338, `saveGlobalData(true)` 346), `MinecraftServer.java:1026-1036,1146-1152`, `SavedDataStorage.java:243-283,320-328` |

The vanilla `autoSave()` (`MinecraftServer.java:1772-1776`) is reachable only from the
`pause-when-empty` block, which is `if (false && …)` (`:1627`). `saveEverything` is never called during
a run.

AWF (`aurora.awf.worlds`) attaches to `RegionFileStorage`, i.e. the chunk, entity and POI region files
of the listed worlds (feature patch 0021); every `data/*.dat` file and `level.dat` in this document is
a plain file written by `SavedDataStorage.tryWrite` / `LevelStorageAccess.saveDataTag`, with or without
AWF.

## 2. Per kind of data

"Periodic" means written during a normal run without an operator command. "Shutdown" means only the
clean-stop path above; a crash (SIGKILL, OOM, power) loses every change since boot.

| Data | File | Owner thread of the mutation | Written | Since |
|---|---|---|---|---|
| Chunks, block entities | region files | owning region | periodic (region autosave), `save-all`, `World#save`, shutdown | baseline |
| Entities | `entities/` region files | owning region | with their chunk | baseline |
| POI | `poi/` region files | owning region | with their chunk | baseline |
| Player `.dat`, stats, advancements | `playerdata/`, `stats/`, `advancements/` | owning region | periodic (player autosave), `save-all`, `World#save`, shutdown, quit | baseline |
| Scoreboard (objectives, scores, teams) | `data/minecraft/scoreboard.dat` | global tick | periodic (global), `save-all`, shutdown | Canvas |
| Stopwatches, custom boss bars, Canvas pearls | `data/minecraft/{stopwatches,custom_boss_events}.dat`, `data/canvas/pearls.dat` | global tick / pearls thread-safe | periodic (global), `save-all`, shutdown | Canvas |
| Time: clocks (day/moon timelines) | `data/minecraft/world_clocks.dat` (server or per world, `time.affects-all-worlds`) | global tick | periodic, `save-all`, shutdown | Canvas |
| Game time, spawn, game type, difficulty, initialized, distance override | `dimensions/<dim>/data/paper/level_overrides.dat` (preferred over `level.dat` at load: `PaperWorldLoader.java:108-114`) | global tick (game time), commands | periodic (world), `save-all`, shutdown | **0027** |
| Weather | `dimensions/<dim>/data/minecraft/weather.dat` | global tick | periodic (world), `save-all`, shutdown | Canvas |
| Game rules | `dimensions/<dim>/data/minecraft/game_rules.dat` | any (synchronized map, copied for the codec) | periodic (world), `save-all`, shutdown | Canvas |
| Maps | `dimensions/<dim>/data/minecraft/map_*.dat`, `idcounts` | any (synchronized) | periodic (world), `save-all`, shutdown | Folia |
| **World border** | `dimensions/<dim>/data/minecraft/world_border.dat` | ticked on the global tick; set from commands/plugins on any thread (plain fields, extent swapped atomically; a concurrent change gives a stale snapshot, never a torn object) | periodic (world), `save-all`, shutdown | **0056** (was shutdown only) |
| **`level.dat`** (data-pack configuration, `allowCommands`, server brands, `LastPlayed`; the gameplay fields are shadowed by `level_overrides.dat`) | `level.dat` (+ `_old`) | global tick / `reloadResources` | periodic (global, synchronous write as vanilla), `save-all`, shutdown | **0056** (was shutdown only) |
| **Random sequences** (loot-table sequences) | `data/minecraft/random_sequences.dat` | any (concurrent map; a sequence's two seed longs are read outside the mutators' lock, so a sequence advanced at that instant can be encoded torn — the same read vanilla does single-threaded) | periodic (global), `save-all`, shutdown | **0056** (was shutdown only) |
| **`/data storage` containers** | `data/<namespace>/command_storage.dat` | any (plain `HashMap`; a concurrent `put` can make the encode throw, the container then stays dirty for the next autosave) | periodic (global), `save-all`, shutdown | **0056** (was shutdown only) |
| Dragon fight | `dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat` | owning region | periodic (region, `ticks-per.autosave`), `save-all` (next tick), shutdown | Canvas |
| Raids | `dimensions/<dim>/data/minecraft/raids.dat` | each raid on its region (`Raids.tick`), the map thread-safe but `Raid` fields and `heroes_of_the_village` (a `HashSet`) are not | **shutdown only** | Folia; **open** (`Raids.java:82-105`, `Raid.java:74-90`) |
| Forceload tickets | `dimensions/<dim>/data/minecraft/chunk_tickets.dat` | any region (`ServerChunkCache.addTicket`, `Long2ObjectOpenHashMap`) | **shutdown only** | Paper 26.x; **open** (`ServerChunkCache.java:212,257-261`, `TicketStorage.java:189,243,262`) |
| Wandering trader timers | — | per region, in memory (`RegionizedWorldData.wanderingTraderData`, created on first use) | **never** (not loaded, not written) | Folia (`WanderingTraderSpawner.java:74-83`) |
| Scheduled functions (`/schedule`) | `dimensions/<dim>/data/minecraft/scheduled_events.dat` | command thread | **shutdown only**, and the queue is **never ticked** (`ServerLevel.java:1027`, commented out by Folia with a TODO), so `/schedule` stores entries that never fire | Folia; **open** |
| World PDC (plugins) | `dimensions/<dim>/data/paper/persistent_data_container.dat` | any plugin thread | **shutdown only** (`ServerLevel.saveLevelData` copies the live PDC first) | Paper + Folia; left out of 0056 on purpose (a plugin writing while the global tick encodes would throw or copy a torn container). The comment at `awf/world/AuroraWorldsService.java:329-332` ("reaches level.dat at the world's next save (autosave, …)") is stale on both counts: the file is `persistent_data_container.dat` and only the clean stop writes it. |
| Structure data | written with the chunk (structure starts/references are chunk data) | owning region | with their chunk | baseline |

## 3. Feature patch 0056 (`save global-owned level data on autosave`)

Two added call sites in `RegionizedServer`, inside the interval-gated blocks and after the Canvas
`saveSpecific`, so they share `autosave.autosave-frequency`, run on the global tick and are forced and
joined by `save-all`: `WorldMetadataAutosave.saveServerOwnedData(server, sync)` in
`autosaveSafeGlobalData` and `WorldMetadataAutosave.saveWorldOwnedData(level.getDataStorage(), sync)`
in `autosaveSafeWorldData`. All logic is in
`sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/world/WorldMetadataAutosave.java`:

- one `saveSpecific` call per type (`saveContained`). `saveSpecific` clears a dirty flag before it
  encodes, so one shared call that throws leaves the earlier types cleared and unsaved; per-type calls
  limit a failing encode to its own type, whose instances are marked dirty again and logged.
- `level.dat` is written synchronously on the global tick through `LevelStorageAccess.saveDataTag`
  (temp file + atomic replace, `level.dat_old` kept), as the vanilla main-thread autosave does. Measured
  cost is not claimed; the file is a few KiB.
- shutdown ordering: the global tick is halted by `TickRegionScheduler.halt` before
  `RegionShutdownThread` saves, so no owned save runs concurrently with the shutdown save. The async
  `.dat` writes are chained through `SavedDataStorage.pendingWriteFuture`, which the shutdown
  `scheduleSave().join()` waits behind. If `halt` times out (60 s) the shutdown proceeds anyway — a
  pre-existing hazard shared by every Canvas autosave.
- no region tick does any additional I/O: both call sites run on the global tick.

Tests: `WorldMetadataAutosaveTest` (7: lists, real `SavedDataStorage` round trips for the world border,
random sequences, command storage, containment of a throwing codec, `level.dat` through a real
`LevelStorageAccess`), `WorldMetadataAutosavePatchShapeTest` (2: call-site placement, 0027 intact).

## 4. Functional proof (agent test server, 2026-10-21, `autosave-frequency: "20s"`)

Shared machine, functional evidence only (no performance claim). Runs under
`/Volumes/Ext/Sourby/agent-testservers/`.

**Before the fix** — `fable-autosave-base-1` (jar `6e5d70e7…`, built from `6163dc88` + the
uncommitted main tree, `older_than_head_commit` true because HEAD moved during the run), SIGKILL after
~2.5 min, then `fable-autosave-base-2 --world-from fable-autosave-base-1`:

| Probe | Set at | Read back after kill + reboot | Verdict |
|---|---|---|---|
| `worldborder set 1234` | 01:36:53 | `59999968` | **lost** (`world_border.dat` mtime stayed at boot) |
| `forceload add 0 0` | 01:36:55 | not marked | **lost** (`chunk_tickets.dat` mtime stayed at boot) |
| `level.dat` | boot | mtime unchanged through four autosaves | **not written** |
| `time set 6000` | 01:36:54 | `minecraft:day` at 12256 (ran on) | survived (`world_clocks.dat` rewritten) |
| `gamerule keep_inventory true` | 01:37:07 | `true` | survived (`game_rules.dat` rewritten) |
| `scoreboard objectives add sc_probe dummy` | 01:36:55 | listed | survived (`scoreboard.dat` rewritten) |

`/data … storage` commands did not parse through the harness console in this build (not investigated),
so command storage has unit-test evidence only.

**With the fix** — `fable-autosave-fix-1` (jar `32945854…`, `--rebuild-jar` from HEAD `26ec3cd4` + the
uncommitted main tree, which also carried opus-cmdblock's uncommitted command-block edits), same probes at
01:58:43-45, SIGKILL at 02:01:39, then `fable-autosave-fix-2 --world-from fable-autosave-fix-1`:

| Probe | Read back after kill + reboot | Verdict |
|---|---|---|
| `worldborder set 1234` | `1234` | **survived** (`world_border.dat` rewritten by the world autosave) |
| `level.dat` | mtime advanced with every global autosave (last 02:00:44 before the kill) | **written periodically** |
| `time set 6000` | `minecraft:day` at 9236 (ran on) | survived |
| `gamerule keep_inventory true` | `true` | survived |
| `scoreboard objectives add sc_probe dummy` | listed | survived |
| `forceload add 0 0` | not marked | **still lost** — not in 0056 (region-mutated `Long2ObjectOpenHashMap`, open item above) |

No `ERROR` line and no "Could not encode" warning in either run; both `stop`s exited 0 with the lock
released. Random sequences and command storage were not exercised on the server (no loot, `/data` did
not parse); their evidence is the unit round trip.
