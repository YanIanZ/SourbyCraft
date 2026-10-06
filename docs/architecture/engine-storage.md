# Aurora Storage Engine — Ownership

> **Role:** T5 ownership document for Storage. **Status:** Active; AWF-wiring sentences corrected on 2026-10-07. AWF itself is documented in [aurora-world-fabric.md](aurora-world-fabric.md).
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

T5 deliverable for §11.6 of `docs/AURORA-FULL-TRANSITION.md`.

## Summary

**Aurora owns no world-persistence policy for the running server today.** The AWF library
(`dev.iyanz.sourbycraft.awf`, see [aurora-world-fabric.md](aurora-world-fabric.md)) implements
atomic, verified persistence and its metrics. Since feature patches 0019 and 0021 the engine's
chunk, entity and POI storage goes through AWF for worlds an operator lists in `aurora.awf.worlds`
or manages with `/awf` (EXPERIMENTAL, default off); every other world keeps the region-file path.
The Resource Governor's `STORAGE` lane (`SourbyCraft-Storage-`, attributed to `WORLD_IO`) runs
those AWF commits and is idle when no world uses AWF. (Corrected 2026-10-07; this summary
previously said the save path did not use AWF.) It measures the world I/O lane, and it owns
one executor that is explicitly *not* world persistence. The distinction matters enough to be
the first thing this document says, because the thread-name prefix invites the opposite
conclusion.

## 1. What Aurora owns

| Service | Owns | Not |
|---|---|---|
| `execution/ExecutionLane.WORLD_IO` | Attribution of reading and writing world data, by thread name: `Dimension-Data-IO-Worker`, `Paper I/O Worker`, `SourbyCraft-IO` | — |
| `util/BoundedIoExecutor` | **Administrative** blocking I/O: virtual threads named `SourbyCraft-IO-`, fixed admission bound, no queue, never blocks the caller | **Not** world saving, region-file writes, or chunk serialization |

`BoundedIoExecutor` exists so administrative work (updater downloads, provisioning, config
reads) cannot block a region thread. It carries no world data. The `WORLD_IO` lane attributes
its threads alongside upstream's I/O workers only because they share the lane's purpose —
*reading and writing data off the tick* — not because Aurora handles persistence.

**Three feature patches touch storage:** 0019 commits AWF stores at shutdown
(`MinecraftServer`), 0021 counts chunk-system storage traffic and routes AWF worlds in
`RegionFileStorage`, and 0028 adds the global-owned world metadata to the global-tick autosave
selection (§6). No patch changes chunk serialization or the save queue. (Corrected
2026-10-07 from "none of the 16 feature patches"; 0028 added the same day.)

## 2. Metrics

| Metric | Source | Surfaced by |
|---|---|---|
| World I/O lane CPU share | `LaneCpuSampler` / `ExecutionLane.WORLD_IO` | `/perf lanes` |
| RSS over the measurement window, with drift | `run_baseline.py` (`metrics.rss`, `metrics.drift`) | `baseline.json` |

## 3. What Aurora does not own

Everything §11.6 lists:

- save queue behavior
- serialization handoff
- region-file write policy
- shutdown flush
- persistence validation

## 4. The rule that governs this domain

§11.6 states it directly, and it outranks any performance argument:

> No performance gain may come from silently weakening durability.

This is the one T5 domain where a "win" can be indistinguishable from data loss until much
later. Any future work here needs persistence validation *before* a throughput number, not
after — `docs/BASELINE.md` already treats persistence as a separate qualification from
performance, and T10 keeps them separate.

A related standing caution: the baseline harness records `metrics.drift` for RSS, and a rising
RSS over a short window is **not** by itself a leak. A five-minute `idle` run on 2026-09-20
showed RSS moving 1.97 GB → 2.48 GB (+26%) with only two GCs in the window — too few
heap-after-GC samples for the harness to even compute a heap trend (`"too few heap-after-GC
samples for a trend (2)"`). A leak claim from this domain needs a soak, not a short run; a
previous "+310 MB/hour" claim in this project was made from four GC points and withdrawn when a
fifth dropped 217 MB.

## 5. Gate status

| T5 requirement | Storage |
|---|---|
| Ownership document | this file |
| Metrics | world I/O lane CPU, RSS + drift |
| Implementation boundary | §1 — measurement plus administrative I/O only, no persistence policy |

Honest status: unowned, instrumented, and gated behind durability validation rather than a
performance target.

## 6. World metadata on autosave and `save-all` (2026-10-07, feature patch 0028)

**Problem.** Game time, spawn, game type, difficulty, the initialized flag and the per-world
distance override live in `PaperLevelOverrides` (`paper:level_overrides`, a `SavedData` in each
dimension's data storage). `PaperWorldLoader` reads that file at world load and only falls back
to `level.dat` when it is missing. Before 0028 the only writer during a run was
`ServerLevel.saveLevelData` (`saveAndJoin`/`scheduleSave` of *all* the world's `SavedData`),
which Folia reaches only at a clean stop (`RegionShutdownThread.saveLevelData`). The region
autosave calls `saveIncrementally(false)`, which skips it ("don't auto save level data"), and
both the global-tick autosave and `save-all` go through
`RegionizedServer.autosaveSafeWorldData`, whose whitelist (maps, per-world clocks, weather,
gamerules) did not list `PaperLevelOverrides`. A crash after `save-all flush` therefore rewound
game time to the last clean stop (Sourby Demo, `verify_crash_panel.py`, 14/15: 1,772,397
restored vs 1,776,107 at the flush).

**Save path now.** `autosaveSafeWorldData` adds
`dev.iyanz.sourbycraft.world.WorldMetadataAutosave.GLOBAL_OWNED_TYPES` (only
`PaperLevelOverrides`) to its whitelist. `SavedDataStorage.saveSpecific` encodes the dirty
instances on the calling thread (the global tick) and writes the files on
`Util.DIMENSION_DATA_IO_POOL`, the lane Folia's map/weather/gamerule autosave already uses.
Periodic autosave does not wait (`sync=false`); `save-all` passes `sync=true` and joins, as it
already did for the other whitelisted types. `level.dat` itself is still written only at a clean
stop (`saveGlobalData`); it is not on the load path for these fields once `level_overrides.dat`
exists.

**Ownership argument.** The global tick advances game time (`RegionizedServer.tickTime`) and
marks the data dirty every tick; the encode runs on the global tick, so the field it changes
most is read on its writer's thread. Spawn can also be set from other threads
(`CraftWorld.setSpawnLocation`, no thread check), but it is a single immutable-record reference,
so the encode sees either the old or the new value, and because game time re-marks the data
dirty on every tick the next autosave writes the newer value. Region-owned `SavedData`
(wandering trader data, raids, structure indexes, the dragon fight) is not listed and is not
bulk-saved. Not covered: world border (Folia's TODO), world PDC (`PaperWorldPDC`, plugin-written
from any thread), and `level.dat`.

**Evidence.** Unit: `WorldMetadataAutosaveTest` (2) drives the real `saveSpecific` filter:
`level_overrides` is written with its game time; a dirty `WanderingTraderData` in the same
storage is not. Local slim jar, flat world, no players, 2026-10-07: `save-all flush` at game time
651 (732 right after), SIGKILL at 942, restart restored **681** (the old path would fall back to
the clean-stop value of boot 1, about 119; a pre-0028 jar was not re-run locally); autosave only (span set to 20 s, no `save-all`):
start 699, SIGKILL at 1709, restored **1479**. Clean stops exited 0; no corruption or error lines
on the restarts. Not yet re-run on Sourby Demo (`verify_crash_panel.py`), with players, or with
AWF worlds.
