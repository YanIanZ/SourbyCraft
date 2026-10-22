# Persistence save paths under region threading

> **Role:** who writes which piece of world state, on which thread, and when. **Status:** Active source ledger,
> reviewed against the materialized sources on 2026-10-21 (Paper `9240f586`, engine baseline + feature patches
> 0027/0056; 0067 source review only, not compiled). Line numbers are those of the materialized files on that day; re-check with `rg` before
> relying on one. Entry point: [AURORA.md](AURORA.md); storage ownership: [engine-storage.md](engine-storage.md).

Vanilla saves everything from one main-thread autosave (`MinecraftServer.tickServer` →
`saveEverything` → players, `saveGlobalData` (`level.dat` + the server `data/`), every level's
`saveLevelData` (its `data/`) and its chunks). Folia split that: chunks, entities, POI and players are
saved by the region that owns them, a short whitelist of global data is saved by the global tick, and
**everything else was written only at a clean stop** (`RegionShutdownThread`). Canvas widened the
whitelist; SourbyCraft widened it again (0027, 0056, 0067). This document is the ledger of what is left.

## 1. The five save paths

| Path | Thread | Trigger | What it saves | Evidence |
|---|---|---|---|---|
| Region autosave | each region tick | every tick, per region | **Chunks + entities + POI**: `ChunkHolderManager.autoSave` saves at most `max-auto-save-chunks-per-tick` (24) holders whose `lastAutoSave` is older than `chunks.auto-save-interval` (paper-world, 6000 ticks); a chunk save writes its entity chunk and POI chunk with it. **Players**: `PlayerList.saveAll(interval)` saves the region's local players whose last save is older than `player-auto-save.rate` (paper-global, `-1` → bukkit.yml `ticks-per.autosave` = 6000 ticks), at most `max-per-tick` per tick; a player save is NBT + stats + advancements. `saveIncrementally(false)` is otherwise a no-op. | `MinecraftServer.java:1723-1742` (`if (false && fullSave)` at 1733 — level data deliberately skipped), `ChunkMap.java:413`, `ChunkHolderManager.java:306-336`, `PlayerList.java:1137-1170`, `:487-500`, `ServerLevel.java:1611-1617` |
| Global-tick autosave | global tick | wall clock, `config/aurora-server.yml` `autosave.autosave-frequency` (default `5m`, LIVE on config reload; `AUTOSAVE_SPAN`) | **Server `data/`** (`autosaveSafeGlobalData`): scoreboard, stopwatches, Aurora pearls (`aurora:pearls`), custom boss events, the server clock when `time.affects-all-worlds`; 0056: random sequences, `/data storage` containers, `level.dat`. **Per world `data/`** (`autosaveSafeWorldData`): maps + map index, per-world clocks, weather, game rules; 0027: `paper:level_overrides` (game time, spawn, game type, difficulty); 0056: the world border; 0067: detached persistent tickets (including forced chunks). Whitelist selections use `SavedDataStorage.saveSpecific`; 0067 uses `sourbycraft$saveSnapshot`: encode on the global tick, file write on `Util.DIMENSION_DATA_IO_POOL`, not joined. | `RegionizedServer.java:262-313` (call at 308), `:335-398`, `:400-470` (called from `autoSaveMaps`, 649-651), `GlobalConfiguration.java:55,203,596-626`, `SavedDataStorage.java:192-241` |
| Dragon fight autosave | the region owning the fight | every `bukkit.yml ticks-per.autosave` ticks of wall time | `ender_dragon_fight.dat` (`saveSpecific(EnderDragonFight)`); `save-all` forces it on the next region tick. | `EnderDragonFight.java:226-252`, `ServerLevel.java:923-925`, `SaveAllCommand.java:47-50` |
| `/save-all [flush]`, `World#save` | global tick + every region | command / plugin | `save-all`: `autosaveSafeGlobalData(true, true)` and `autosaveSafeWorldData(true, true, world)` for every world (forced, joined; the 0027/0056 selections and 0067 snapshot included), then a `SaveAllTicket` per region (chunks via `saveAllChunksRegionised`, local players). **Bukkit `World#save` only propagates the region tickets**: chunks and players, no level data of any kind. | `SaveAllCommand.java:32-80`, `MinecraftServer.java:1886-1897`, `CraftWorld.java:1147-1157` |
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
| Stopwatches, custom boss bars, Aurora pearls | `data/minecraft/{stopwatches,custom_boss_events}.dat`, `data/aurora/pearls.dat` (saved data `aurora:pearls`; a Canvas-era `data/canvas/pearls.dat` is moved there once by `PearlsMigration`, 2026-10-21) | global tick / pearls thread-safe | periodic (global), `save-all`, shutdown | Canvas |
| Time: clocks (day/moon timelines) | `data/minecraft/world_clocks.dat` (server or per world, `time.affects-all-worlds`) | global tick | periodic, `save-all`, shutdown | Canvas |
| Game time, spawn, game type, difficulty, initialized, distance override | `dimensions/<dim>/data/paper/level_overrides.dat` (preferred over `level.dat` at load: `PaperWorldLoader.java:108-114`) | global tick (game time), commands | periodic (world), `save-all`, shutdown | **0027** |
| Weather | `dimensions/<dim>/data/minecraft/weather.dat` | global tick | periodic (world), `save-all`, shutdown | Canvas |
| Game rules | `dimensions/<dim>/data/minecraft/game_rules.dat` | any (synchronized map, copied for the codec) | periodic (world), `save-all`, shutdown | Canvas |
| Maps | `dimensions/<dim>/data/minecraft/map_*.dat`, `idcounts` | any (synchronized) | periodic (world), `save-all`, shutdown | Folia |
| **World border** | `dimensions/<dim>/data/minecraft/world_border.dat` | ticked on the global tick; set from commands/plugins on any thread (plain fields, extent swapped atomically; a concurrent change gives a stale snapshot, never a torn object) | periodic (world), `save-all`, shutdown | **0056** (was shutdown only) |
| **`level.dat`** (data-pack configuration, `allowCommands`, server brands, `LastPlayed`; the gameplay fields are shadowed by `level_overrides.dat`) | `level.dat` (+ `_old`) | global tick / `reloadResources` | periodic (global, synchronous write as vanilla), `save-all`, shutdown | **0056** (was shutdown only) |
| **Random sequences** (loot-table sequences) | `data/minecraft/random_sequences.dat` | any (concurrent map; a sequence's two seed longs are read outside the mutators' lock, so a sequence advanced at that instant can be encoded torn — the same read vanilla does single-threaded) | periodic (global), `save-all`, shutdown | **0056** (was shutdown only) |
| **`/data storage` containers** | `data/<namespace>/command_storage.dat` | global tick only (plain `HashMap`; plugins must also respect ownership). MC 0069: storage-only `/data` commands and storage suggestions run on the global tick (inline from console/RCON/global-tick functions with the real result; handed off from a region with result 0 and the outcome sent to the sender). A mixed `data modify … from` reads the source on its owner, copies it, and writes the target on its owner (storage on the global tick, a block on its region, an entity through its scheduler); a block or entity source read from the console is refused with a command error. `/execute … storage` and `/function … with storage` from a region are a command error, not routed. | periodic (global), `save-all`, shutdown | **0056** (was shutdown only) |
| Dragon fight | `dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat` | owning region | periodic (region, `ticks-per.autosave`), `save-all` (next tick), shutdown | Canvas |
| Raids | `dimensions/<dim>/data/minecraft/raids.dat` | each raid on its region (`Raids.tick`), the map thread-safe but `Raid` fields and `heroes_of_the_village` (a `HashSet`) are not | **still shutdown-only** | 0067 review: no owner-consistent snapshot of mutable `Raid` fields, hero set or raid PDC; concurrent raid-map iteration does not protect its values |
| Forceload tickets | `dimensions/<dim>/data/minecraft/chunk_tickets.dat` | regions via Moonrise ticket manager (concurrent index, section-locked ticket sets); startup activation of deactivated tickets | **periodic now** (global snapshot/encode), `save-all`, shutdown; code only, no compile/runtime evidence yet | **0067**; detached serialized fields under existing section locks, startup map locked separately; see §5 |
| Wandering trader timers (0068) | `dimensions/<dim>/data/minecraft/wandering_trader.dat` | global tick; region results handed back through `RegionOwnership` | global autosave / save-all (separate contained type), clean shutdown | countdown, delay and chance saved; Java tests and server validation **not run**; [details](wandering-traders.md) |
| Scheduled functions (`/schedule`) | `dimensions/<dim>/data/minecraft/scheduled_events.dat` | command thread | **shutdown only**, and the queue is **never ticked** (`ServerLevel.java:1027`, commented out by Folia with a TODO), so `/schedule` stores entries that never fire | Folia; **open** |
| World PDC (plugins) | `dimensions/<dim>/data/paper/persistent_data_container.dat` | any plugin thread | **still shutdown-only** (`ServerLevel.saveLevelData` copies the live PDC first) | 0067 review: `CraftPersistentDataContainer` has an unsynchronized `HashMap`, exposes `getRaw()` and mutable tags; `getTagsCloned()` has no lock. A global copy can throw or be torn; locking only the reader would not protect plugin writers. |
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
*(2026-10-22, MC 0069 fixes, run `opus-fix6869-data-1`, jar `6d096680…`, functional only:)* storage is
registered again. Console: `data modify storage sc:probe v set value 42` → "Modified storage",
`data get storage sc:probe v` → 42, `execute store result score probe scp run data get storage sc:probe v`
→ score 42; `data get block` / `data modify block … Items` on a force-loaded chest work (handed to the
owning region); `data modify storage … from block` from the console is refused with "Target not in same
region". Command blocks (`aurora.world.command-blocks = true`): `execute if data storage …` fails with
the "only available on the global tick" command error (SuccessCount 0); `data get storage …` runs deferred
(result 0; a command block's SuccessCount still counts the accepted command); `data modify storage … from
block` (block owned by that region) copied the chest items into storage. No exception in the log;
`stop` exited 0. Not covered on the server: `/function … with storage` from a region (unit/patch-shape only).

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

## 5. Feature patch 0067: periodic persistent-ticket snapshots

Source change only: **nothing compiled; Java tests not run, no Gradle**. No new crash/restart or
performance evidence is claimed. The historical failed forceload probe in §4 predates this patch.

`WorldStateAutosave.save(storage, sync)` runs inside the interval-gated world whitelist supplier,
near 0056's world-data call. Ownership is checked through `RegionOwnership`, with no engine imports
in the SourbyCraft helper. It schedules ticket data independently before the outer whitelist save;
`save-all` joins it. No new executor or region I/O is added. This is governed by the existing global
autosave interval (LIVE on reload), with no new setting; installing the patch requires a restart.

The active path is not the vanilla unsynchronized ticket map: `TicketStorage` redirects to
`ChunkHolderManager`. Its existing `getTicketsCopy` locks one section at a time but shallow-copies
Ticket references. The new persistence variant copies only persisted tickets, constructing new
Tickets from the codec's type, level and remaining-delay fields **while holding those same section
locks** used for ticket mutation/expiry. Identifiers are not encoded by `Ticket.CODEC`. The inactive
startup map is copied under its own monitor; its sole post-construction mutator,
`activateAllDeactivatedTickets`, now uses that monitor too, with matching lock order. The existing
shutdown codec and normal ticket-copy path remain selected as before.

The result is a detached encoding input, not a transaction across every section: concurrent index
iteration may miss a just-added chunk, and different sections can represent different instants.
Mutations through `TicketStorage` publish dirty after their changes, and region ticket purges mark it
dirty too, so overlapping changes remain eligible for a later autosave. The helper clears dirty
**before** snapshot capture and never clears it after capture. Capture/encode failures restore only
that item's dirty flag and do not skip the other items. Detached NBT is chained through the existing
`SavedDataStorage.pendingWriteFuture` and `DIMENSION_DATA_IO_POOL`, preserving ordering with later
world saves and shutdown. Existing storage file-write errors are logged rather than propagated; this
patch does not add durable-write acknowledgement or change that behavior.

Raids remain shutdown-only: serializing a concurrent raid map still reads region-owned `Raid`
fields, a mutable hero `HashSet`, and raid PDC off-owner. No owner snapshot/aggregation protocol
exists here; this patch does not add one. World PDC remains shutdown-only: neither its map nor tag
copying shares synchronization with plugin writes, and raw mutable access prevents proving an
atomic copy. These crash-loss gaps remain open.

Tests added: `WorldStateAutosaveTest` (snapshot isolation with a later mutation retaining dirty,
clean skip, and snapshot/encode failure containment; 4 cases), `WorldStateAutosavePatchShapeTest`
(2 cases), plus `scripts/test_world_state_autosave_patch.py` (2 cases) for patch shape only. Neither source-shape checks nor the unrun Java tests establish runtime persistence.

### Command storage ownership

The new `data-command-threading` feature patch restores `/data get|merge|modify|remove storage`.
The code path runs console commands inline on the global tick; players and command blocks
hand storage commands to the global tick, where feedback is sent after the action. Suggestions
also run there. Source storage reads in `modify ... from storage` use the same route. Mixed
storage/block/entity operations still require ownership of every non-storage source/target;
no cross-region read is permitted. Console block-data commands now refuse before looking up
world data, with a message to run from a player or command block in the owning region.

Status: code and tests written; Java compilation and runtime verification pending PM validation.
This is command routing, not a LIVE configuration toggle; deployment requires a new jar/restart.
Autosave timing remains the existing LIVE `autosave.autosave-frequency` setting.
