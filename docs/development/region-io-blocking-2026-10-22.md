# Region threads and external I/O under plugins and a disk stall, 2026-10-22

Build 49 gate "Region threads never block on external I/O" (runbook G06). Earlier evidence: 2026-10-20 test
server, 0 I/O frames in region stacks over 484 dumps, light load, no plugins, plus the Redis-freeze runs. This
run adds the two missing parts: legacy plugins through the Aurora Bridge, and a disk stall of the world
directory. It is functional evidence from an agent test server with **no player connected**; it is not the
real-server G06 run and it qualifies nothing.

**Result: the expectation "no region thread touches file I/O" does not hold.**

**Status 2026-10-25 (PR #62).** The F2 fix is on 26.2 as MC feature patch 0077 (commit `7db5209`, in
the `v26.2-r50` tag), so the note below that its number is local is resolved. F1/F4 (structure lookups
on a region thread) and F5 (player saves and the join-time read on the owning region) are still open;
PR #62 did not change them (PM-060: needs an owner review of an asynchronous structure search and a
feature patch made from materialized sources; PM-204: needs such a patch and a disk-stall run with
players, [BUILD-50-PM-MATRIX.md](../releases/BUILD-50-PM-MATRIX.md)). The setup's
`sourbycraft_config/aurora.toml` and `config/canvas-server.yml` are the pre-Build-49 names these runs
used; the current files are `sourbycraft_config/aurora.yml` and `config/aurora-server.yml`.

| # | Thread | Path | Stall effect | Status |
| --- | --- | --- | --- | --- |
| F1 | region (command block `/locate`) | `LocateCommand` -> `ChunkGenerator.findNearestMapStructure` -> `StructureManager.checkStructurePresence` -> `StructureCheck.tryLoadFromStorage` -> `SimpleRegionStorage.chunkScanner` (inline) -> `RegionFileStorage.scanChunk` -> `RegionFile.getChunkDataInputStream` -> `FileChannelImpl.read` / `pread0` | region held for the stall: `Locating ... took 52977 ms` / `59859 ms` (run 3), `51347 ms` / `58211 ms` (run 4); stall recorder: region #1 and #2 completed no tick for up to 30+ s | **defect, not fixed** (proposal below). Vanilla/Folia shape, the R2 path of [region-io-audit.md](../architecture/region-io-audit.md) |
| F2 | global tick (runs on a `Folia Region Scheduler Thread`) | `RegionizedServer.globalTick` -> `autosaveSafeGlobalData` -> `WorldMetadataAutosave.saveServerOwnedData` -> `saveLevelDat` -> `LevelStorageAccess.saveDataTag` -> `NbtIo.writeCompressed` -> `write0` (periodic autosave, feature patch 0056) | global tick held 57-60 s; console, global scheduler (where the bridge runs legacy plugin timers), time/weather stopped meanwhile | **fixed** (feature patch 0077 + `WorldMetadataAutosave.scheduleLevelDat`), verified in run 4 |
| F3 | global tick | same write reached from console `save-all` (`SaveAllCommand` -> `autosaveSafeGlobalData(true, true)`) | 57.4 s (run 2) | by design: `save-all` stays synchronous, like vanilla |
| F4 | region (command block `/locate`) | `ChunkGenerator.getStructureGeneratingAt` -> `ServerLevel.moonrise$syncLoadNonFull` -> `ChunkTaskScheduler.syncLoadNonFull` -> `BlockableEventLoop.managedBlock` | waits for the chunk system (I/O pool or generation); 9 stacks in the unstalled run, none caught during a stall | R1 shape; same proposal as F1 |
| F5 | region (players) | `PlayerList.saveAll` -> `PlayerDataStorage.save` (+ stats, advancements) on the owning region; `PrepareSpawnTask.spawn` player-data read | not exercised: no players | static only (source reading of this tree); open |

Chunk loads and saves ran on the `Paper I/O Worker` pool (one thread): during the stall it sat in
`UnixNativeDispatcher.access0` / reads for `RegionFile`, and no region thread waited on it except through F1/F4.

## Setup

- Worktree `sc-worktrees/opus-49-io`, base `001b86f1` (26.2, Build 48 merge). Runs 1-3: slim jar
  SHA-256 `14248d80e54ead332dc6a015980e340154ed8f661bf2e7704772eb054fb6e9a7` (clean tree, `run.json`
  `git.dirty` false). Run 4: jar `a8eb1a63d40c096aa91b1ab7c4444f6d600f48c2954645d6613934562b280c65`, same base
  plus the F2 fix (tree dirty with exactly that change). Test plugin `29d9799e...`.
- Host: Apple M2 (4P+4E), 16 GiB, macOS 27.0.1, OpenJDK 25.0.2, 2 GiB heap, shared laptop (load average at
  start 6.8-32; another agent's server ran beside runs 1-3). No timing here is a measurement.
- Agent test server (`scripts/agent_testserver.py`), `minecraft:normal`, seed 440044, view distance 4, offline.
  `sourbycraft_config/aurora.toml`: `[aurora.bridge] mode = "safe"`, `[aurora.world] command-blocks = true`.
  Runs 3-4 also `config/canvas-server.yml` `autosave.autosave-frequency: "30s"` (default `5m`) so the periodic
  global autosave falls inside the stall.
- Plugins (bridge safe mode, hashes as in [bridge-plugin-compatibility-2026-10-22.md](bridge-plugin-compatibility-2026-10-22.md)):
  Vault 1.7.3-b131, EssentialsX 2.22.1 (config `disabled-commands: [time, help]`), LuckPerms 5.5.10,
  PlaceholderAPI 2.12.2 (native), DeluxeHubReloaded 3.7.2, ntdLuckyBlock 2.8.34, ChatGames 1.2.1, ReMinions
  2.0.7, plus the SourbyCraft test plugin with `sourby.testplugin.probes=true`. `/perf plugins`: native 2 /
  bridged 7 / failed 0; rejected 0 and violations 0 for every bridged plugin at the end of runs 2-4.
- Console workload (driver `tools/drive.py`): forceload two areas (spawn, and 3000,3000) so two regions tick;
  in each a pair of repeating command blocks that fill/clear a 49x49 layer every tick (chunk churn and saves);
  150 cows per area plus 20 more per cycle (`massspawn`); impulse command blocks running
  `locate structure <s>` on the region thread, re-placed every cycle with a new structure; plugin commands
  each cycle (`chatgames math`, `lp sync`, `lp creategroup`/`permission set`, `papi ecloud status`, `lb list`);
  run 1 pre-generated about 2 300 chunks around spawn and 256 at -3000,-3000 and unloaded them, so later runs
  read existing region files; each stall run force-loads a pre-generated area during the stall.

## Disk-stall method (macOS, no root)

The world container (`bukkit.yml` `settings.world-container`) is on an APFS sparse-bundle disk image attached
by the user (`hdiutil attach -nobrowse`). Its block I/O is served by a user-owned `diskimages-helper` process
(`hdiutil info` `process ID`). `kill -STOP` on that process stalls the volume; a watchdog sends `SIGCONT` after
60 s. Before each stall run the image is detached and re-attached, which drops its page cache, so reads of
region files are real device reads.

Checked before use: with the helper stopped, an `F_NOCACHE` 1 MiB read blocked 11.99 s (until `SIGCONT`) and a
64 MiB write blocked 15.1 s; during the server runs the I/O worker and the `level.dat` writes sat in native
file calls for the whole window.

Limits: page-cache hits are not stalled (a file read since the last re-attach can still be served); small
writes can be absorbed by the buffer cache until a flush; only the world container is stalled (plugin data
folders, logs and the server jar are on another volume, so plugin file I/O was not stalled); one host, macOS,
not Linux block devices or network storage.

## Sampling and classification

`tools/sample.py`: `jcmd <pid> Thread.print -l` every 0.5 s (each dump takes about 0.3-0.5 s). `tools/classify.py`
keeps threads named `Folia Region Scheduler Thread #n` and sorts each stack: region tick
(`TickRegions$ConcreteRegionTickHandle`), global tick (`RegionizedServer$GlobalTickTickHandle`), idle (parked
in `EDFSchedulerThreadPool$TickThreadRunner.run`), other. **I/O frame** = any frame in `java.io.File*`,
`RandomAccessFile`, `FileDescriptor.sync`, `sun.nio.ch.FileChannelImpl`/`FileDispatcherImpl`/`UnixFileDispatcherImpl`/`IOUtil`,
`sun.nio.fs.*`, `java.nio.file.Files`, socket classes (`sun.nio.ch.SocketDispatcher`/`NioSocketImpl`/`SocketChannelImpl`/`Net`,
`java.net.*`, `jdk.internal.net.http`), `RegionFile`, `NbtIo.read/write`, `PlayerDataStorage`, `RedisClient`.
**Wait on chunk I/O** = `ServerChunkCache.syncLoad`, `ChunkTaskScheduler.syncLoadNonFull`, `StructureCheck.tryLoadFromStorage`,
`scanChunk`, `MoonriseRegionFileIO` joins. Every matching stack was read by hand; the unique stacks are in
`classify-*.txt`.

| Run / phase | Jar | Dumps | Region-scheduler stacks | Region tick | Global tick | Idle | I/O frame, region tick | I/O frame, global tick | Wait only |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 unstalled (pregen + 16 locates) | 14248d80 | 352 | 2816 | 362 | 3 | 2446 | 0 | 0 | 9 (F4) |
| 2 pre | 14248d80 | 60 | 480 | 30 | 0 | 450 | 0 | 0 | 0 |
| 2 stall (console `save-all`) | 14248d80 | 119 | 952 | 5 | 113 | 834 | 0 | 113 (F3) | 0 |
| 2 post | 14248d80 | 119 | 952 | 64 | 1 | 887 | 0 | 0 | 0 |
| 3 pre | 14248d80 | 76 | 608 | 80 | 1 | 527 | 0 | 0 | 0 |
| 3 stall (locate, autosave 30 s) | 14248d80 | 116 | 928 | 213 | 93 | 621 | 203 (F1) | 92 (F2) | 0 |
| 3 post | 14248d80 | 95 | 760 | 81 | 3 | 676 | 1 (F1, unstalled read) | 0 | 0 |
| 4 pre | a8eb1a63 | 80 | 640 | 46 | 1 | 590 | 0 | 0 | 0 |
| 4 stall (same as 3, fixed jar) | a8eb1a63 | 117 | 936 | 218 | 0 | 718 | 203 (F1) | **0** | 0 |
| 4 post | a8eb1a63 | 84 | 672 | 97 | 1 | 574 | 0 | 0 | 0 |
| **Total** | | **1218** | **9744** | | | | **407** | **205** | **9** |

No socket frame appeared on any region-scheduler thread. The stall recorder agrees: run 3 recorded the global
tick and regions #1/#3 stalled; run 4 only regions #1/#2 (F1), never the global tick. In run 4 the
`SourbyCraft level.dat writer` thread was in a native write in 72 of 117 stall dumps, and the server logged once
`The previous level.dat write has not finished (slow or stalled disk?); skipping the periodic level.dat write
until it does.` Run 3 also recorded regions #4-#6 of the test plugin's temporary `unloadfixture` world without
a tick for 10 s before the stall began (no I/O frame in those stacks; the fixture unloads that world).

## Fix for F2 (feature patch 0077 + owned code)

- `WorldMetadataAutosave.scheduleLevelDat` (periodic autosave): builds the `level.dat` tag on the global tick
  (it reads live level data, as before), writes the file on one daemon thread (`ThreadPoolExecutor` 1/1 with an
  `ArrayBlockingQueue(1)`, so `UnboundedQueueAuditTest` holds), same file operation as `saveDataTag` (temp file
  + `safeReplaceFile` through `LevelStorageAccess.saveLevelData`). While the previous write is outstanding the
  new one is skipped, not queued; one WARN until a write completes.
- `saveLevelDat` (`save-all`): waits for an outstanding write, then writes on the caller (unchanged semantics).
- MC feature patch 0077 (`hook`, +1 line): `MinecraftServer#saveGlobalData` calls
  `WorldMetadataAutosave.awaitLevelDatWrites()` before the shutdown `level.dat` write, so an older scheduled tag
  can never replace the shutdown file.
- Tests: `WorldMetadataAutosaveTest` two new cases (holding the write lock stands in for a stalled disk: the
  caller returns at once, a second periodic write is skipped, `save-all` and the shutdown wait run after the
  outstanding write and the newer tag wins); `WorldMetadataAutosavePatchShapeTest` pins the 0077 hook before
  `saveDataTag`. No mutation run was done.
- Docs: `persistence-save-paths.md` (level.dat row and notes), `patches.md` (row, count 77),
  `inherited-guards.md` generated summary (feature patches 104), `region-io-audit.md` (runtime section).
- Patch number 0077 is local; if another 0077 lands first, renumber on top.

## Proposal for F1/F4 (not fixed here)

Structure lookups from a region tick read region files (or wait for the chunk system) inline. A fix that keeps
`/locate`, `EnderEyeItem`, `ExplorationMapFunction` and dolphin treasure search correct needs the search to run
off the region thread: start it on a worker with a snapshot of the origin, let `StructureCheck` reads go through
the I/O pool (`MoonriseRegionFileIO.loadDataAsync`) and complete back on the owning region through its task
queue (the command reply, eye-of-ender target or map item filled in on completion). This changes when the
result appears (a later tick), so it needs its own design and plugin-semantics review (`World#locateNearestStructure`
callers expect a synchronous answer). A smaller interim option: a per-region cap on blocking structure reads per
tick with a "not found yet" result, which changes vanilla semantics and is not recommended without review. F5
(player saves and the join read on the owning region) needs the same treatment: encode on the region thread,
write on an ordered per-player I/O lane, and a player-data read before the region spawn task.

## What this does not cover

- No real players: joins, player saves (F5), plugin actions in player flows, chunk loading by movement.
- Linux host, a real slow disk, NFS/FUSE storage, fsync-heavy load; only a SIGSTOPped disk-image helper on macOS.
- Plugin data folders were not stalled; plugins that do file or database I/O on a region thread in player flows
  were not exercised (no plugin action ran on a region thread here: with no players, bridged tasks ran on the
  global region).
- AWF worlds (only vanilla region files), the Redis backend (the earlier Redis-freeze sample stands on its own).
- Short runs, one boot per configuration, shared machine. Counts are sample counts, not durations.

## Raw material

`/Volumes/Ext/DevCache/agent-tmp/sourbycraft/opus-49-io/`: `samples/<run>-<phase>/` (all dumps, `index.tsv`
with epoch and dump duration), `classify-*.txt`, `events-*.txt` (stall timestamps; the `SIGCONT` line of run 2
was logged when the driver returned, the actual `SIGCONT` was 60 s after `SIGSTOP`), `transcript-*.txt`
(console), `tools/` (`sample.py`, `classify.py`, `drive.py`, `stall_run*.sh`, `start1.sh`), configs. Run
directories `/Volumes/Ext/Sourby/agent-testservers/opus-49-io-{1,2,3,4}/` (`server.log`, `run.json`,
`logs/stalls/`). All four runs stopped with exit 0 and released their slot.
