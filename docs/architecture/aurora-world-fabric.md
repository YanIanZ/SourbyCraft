# Aurora World Fabric

Aurora World Fabric (AWF) is SourbyCraft's native region-aware virtual-world, template, instance, storage and persistence runtime.

## Identity and compatibility
AWF is not branded as ASP/SWM. AdvancedSlimePaper `dev/26.2` remains an upstream technical reference and API compatibility target. Directly reused upstream source must retain license/attribution required by its license.

## World roles
`VANILLA`, `VIRTUAL`, `TEMPLATE`, `INSTANCE`, `READ_ONLY`, `TEMPORARY`.

## Ownership
Mutable chunk/world state is accessed and mutated by its owning Aurora region. Storage/serialization workers consume immutable snapshots only.

Never block region owners on file/database I/O or use CountDownLatch-style sync bridges for world lifecycle work.

## Chunk pipeline
Chunk request -> Aurora scheduler -> AWF index/store -> decode -> owner-region materialization.

AWF replaces the storage source, not the scheduler correctness model.

## Lazy materialization
Register a virtual world from header + immutable metadata + chunk index. Decompress/materialize chunks only when requested.

## Copy-on-write templates
Instances share immutable template chunks until first mutation. On first mutation the chunk becomes instance-owned. Isolation between template and instances is mandatory.

## Persistence
Modes: FULL, INCREMENTAL, CHECKPOINT, READ_ONLY.

Dirty region-owned state -> immutable snapshot -> Aurora STORAGE lane -> serialize -> backend.

Atomic commit sequence: snapshot -> temporary generation -> verify -> commit manifest. A crash before commit leaves the prior generation authoritative.

## Storage
Initial backends: FILE, MongoDB, MySQL, Redis. Native async contracts return CompletionStage; legacy SlimeLoader APIs are adapters.

## Required metrics
Loaded worlds, resident/dirty chunks, save queue depth, oldest pending save, serialization/backend p50/p95/p99, retries/failures and bytes read/written.

## Implementation status (26.2 branch)

`dev.iyanz.sourbycraft.awf` is a tested library with one engine integration: the FILE backend
under `RegionFileStorage`, for worlds an operator lists in `aurora.awf.worlds` (default: none).
It is off unless configured and is not qualified; see "Engine integration" below.

- `AwfFile` (`.awf`): header, immutable metadata, chunk index, and deflated chunks, each with a
  SHA-256. Opening reads the index only; a chunk materialises when read. Corrupt or truncated
  chunks are reported, never returned.
- `GenerationStore`: the atomic commit primitive. Snapshot → `N.tmp` → read-back verify → publish →
  replace `CURRENT`. Recovery on open; retention; `readRetained`; blocking calls throw on region
  threads.
- `AwfWorldStore` (FILE backend): a chunk index per generation, and chunk bytes in a
  content-addressed `ObjectStore`.
  - FULL rewrites every chunk.
  - INCREMENTAL writes only new objects (identical chunks share one).
  - CHECKPOINT also re-verifies every referenced object before committing.
  - READ_ONLY refuses.
  - Objects are verified before the generation swap. Objects no retained generation references
    are collected after it.
- `AwfWorld`: world-owned chunks copy-on-write over a base (`AwfFile`, a store, or
  `LayeredSource`); the base is never written. Writes copy the caller's buffer. Saves run on a
  caller-supplied executor (the governor's `STORAGE` lane is meant for this), with bounded
  retries; a chunk rewritten during a save stays dirty. Metrics: resident/dirty chunks, save queue
  depth, oldest pending save, serialization and backend p50/p95/p99, retries, failures, bytes read
  and written, and materialised chunks. `AwfRegistry` counts loaded worlds. An optional resident
  limit drops clean chunks LRU after each save; they are read back from the store, and dirty
  chunks are never dropped.
- `WorldRole`: the six roles and their write rules.

- Deletions are recorded as tombstones in the chunk index. A deleted chunk reads as absent and
  shadows the base across restarts, instead of reappearing from it.

### Runtime worlds (`AuroraWorlds` API and `/awf`)

Status: worlds, named generators, templates and copy-on-write instances are implemented on the
FILE backend and verified by local end-to-end runs (below). The Slime (SRF v13) converter and
database backends are **planned, not implemented**.

- **API.** `dev.iyanz.sourbycraft.api.world.AuroraWorlds` in `sourbyapi`, obtained from the
  services manager: `list`, `exists`, `isLoaded`, `create(WorldCreator, autoload)`, `load`,
  `save`, `unload(name, save)` → `UnloadResult`, `delete`, `autoload`/`setAutoload`. Every call
  returns a future that completes on the global region thread or later; never block a region
  thread on it. Names are `[a-z0-9_-]{1,48}`; storage folder names (`region`, `poi`, `entities`,
  `dimensions`, …) are refused because AWF matches worlds by path element.
- **Registry.** `sourbycraft_config/aurora-worlds.json` lists the managed worlds (environment,
  seed, autoload). It is written through a temporary file and an atomic move. A world in it is an
  AWF world without being listed in `aurora.awf.worlds`, so a world created at runtime stays in
  AWF across restarts. A malformed file stops the stage instead of being read as empty.
- **Where data lives.** `world/dimensions/minecraft/<name>/{region,entities,poi}.awf`. No region
  file is written while the world is attached.
- **Thread rules.** The engine creates and unloads worlds on the global tick, which runs on a
  region scheduler thread where AWF refuses blocking store I/O. `create` and `load` therefore
  open the world's three stores first on the I/O lane (`AwfEngine.prepare`); the storages that
  open on the global tick take those stores. `save` sends a save ticket to every region of the
  world, waits for all of them, then commits the stores off the region threads, so a completed
  `save` is durable. `unload` uses the engine's asynchronous unload; `Bukkit.unloadWorld` is not
  supported on this engine. `delete` requires the world to be unloaded.
- **Autoload.** Worlds with `autoload` are loaded on the global tick after `ServerLoadEvent`
  (STARTUP), i.e. after the default worlds exist. Setting changes are persisted immediately and
  take effect at the next start (RESTART_REQUIRED for loading; LIVE for the file).
- **Generators.** A generator is recorded by name so the world loads again with it: `void`
  (built in: no terrain, structures or natural mobs; spawn 0.5, 64, 0.5), a plugin generator as
  `Plugin` or `Plugin:id`, or none for the environment's own terrain. World types `flat`,
  `amplified` and `large_biomes` are recorded the same way. A `WorldCreator` that carries a
  generator object is refused, because that object cannot be found again at the next load.
- **Templates.** `saveTemplate(world, template)` flattens an **unloaded** world's committed chunks
  — for an instance, its own chunks over its template's — into `awf-templates/<t>/{region,
  entities,poi}.awf` plus `template.json` (environment, seed, generator, world type). It is built
  in a hidden folder in batches of 256 chunks and moved into place at the end, so a failure never
  leaves a template instances could be made from. Nothing writes a template afterwards; it opens
  as role `TEMPLATE` (no commits, no garbage collection). A loaded world is refused because it keeps
  committing and a template must be one consistent moment. Templates need the FILE backend.
- **Instances.** `createFromTemplate(template, name)` registers a world with role `INSTANCE` whose
  stores take the template's stores as their read-only base: a chunk the instance never wrote is
  read from the template; a written chunk is the instance's own; a deleted chunk shadows the
  template and is generated again. All instances of a template share one opened template store
  (and its chunk index) in memory and one copy on disk. A template cannot be deleted while any
  world is an instance of it.
- **Command.** `/awf list | info <n> | create <n> [normal|nether|end] [void|flat|amplified|
  large_biomes] [generator=Plugin[:id]] [seed] [autoload] | create <n> from <template> [autoload] |
  load <n> | save <n> | unload <n> [nosave] | delete <n> confirm | autoload <n> on|off |
  template list | template save <world> <template> | template delete <template> confirm`,
  permission `sourbycraft.command.awf`.

Verification (2026-10-06, local, macOS, JDK 25, 2 GiB heap, one server, no players): create →
setblock → `/awf save` → unload → stores present and 0 `.mca` → load → block present → clean
stop → restart autoloads the world → block present → delete removes folder and entry →
reserved name refused. 12/12 checks. This is a functional check, not a performance or
concurrency qualification; unloading with players present and `unload … nosave` with dirty chunks
were not exercised.

Templates (2026-10-06, same setup): void world built → template save refused while loaded →
unload → template saved → two instances created with autoload → instance 1 changes a template
block, instance 2 still reads the template's → template delete refused while instances exist →
clean stop → stores on disk, 0 `.mca` in instances → restart autoloads both → instance 1's change
and instance 2's template content (both template blocks) present → instances deleted → template
deleted. 17/17 checks. No disk-size or memory measurement was taken; the sharing claim above is
by construction (one store opened per template), not a measured saving.

A defect this found: `AwfRegionStorage.read` returned "not ours" for any chunk the world had not
written, so instances never read their template and the engine fell through to (absent) region
files. It now reads through the world's base first.

### Engine integration (RegionFileStorage)

Operator guide: [testing AWF](../guides/testing-awf.md).

Patch: `sourbycraft-server/minecraft-patches/sources/net/minecraft/world/level/chunk/storage/RegionFileStorage.java.patch`.
Sourby side: `AwfEngine`, `AwfRegionStorage`, `AwfSettings`.

- **What goes through AWF.** Every `RegionFileStorage` whose folder has a path element equal to a
  listed world name: chunk data, entities and POI of that world, each dimension separately. The
  store lives beside the folder (`region` → `region.awf`).
- **Hooked paths.** Moonrise's `readData`/`startWrite`/`finishWrite` (the chunk system's I/O),
  and vanilla `read`, `write` and `scanChunk` (structure checks, upgrade tools), `flush` and
  `close`.
- **Layering.** The world's region files are the read-only base. A chunk the engine writes or
  deletes afterwards belongs to AWF; untouched chunks are still read from the region file.
  Region files under an AWF store are never written or cleared.
- **Durability.** A write is held in memory. A commit starts on the governed `STORAGE` lane once
  written chunks have waited `aurora.awf.commit-interval-seconds` (checked once a second by the
  metrics collector, which stops early in shutdown). At shutdown, feature patch 0020 commits and
  closes every open storage in `MinecraftServer.stopServer`, right after
  `MoonriseRegionFileIO.flush(server)`, the point where every chunk save has reached its storage.
  This is needed because Folia's shutdown never calls `RegionFileStorage.flush()`/`close()`; CI
  run 441 found a stop that lost every chunk without it. `flush()` and `close()`, where the engine
  does call them, also commit synchronously on the calling thread. A flush arriving on a region thread only starts an async
  commit, because region threads must not block on disk. **A crash loses the writes since the
  last commit**; region files lose less. `AwfRegionStorageTest.writesSinceTheLastCommitAreLostOnACrash`
  pins this.
- **Memory.** `aurora.awf.resident-chunks` (default 1024) clean chunks per storage stay in memory
  after a commit. Dirty chunks are never dropped, so a storage can exceed the limit by what is
  unsaved.
- **Once attached, attached.** If a store exists for a folder, it is opened even when the world is
  no longer listed, with a warning. Writing to the region files underneath would be overridden by
  the older AWF data on a later run.
- **Export.** `aurora.awf.export = ["world"]` (and the world removed from `worlds`) writes every
  chunk and deletion in the store into the region files at the next load, through the storage's
  ordinary write path, flushes, then retires the store (FILE: renamed to
  `<folder>.awf.exported-<millis>`, kept, not deleted). A failure part-way fails the world load and
  leaves the store in place; the next load exports again from the start. A world in both lists
  stays in AWF.
- **Failure behavior.** A store that cannot be opened fails the world load rather than falling
  back to region files. A commit that fails after `aurora.awf.commit-attempts` leaves the chunks
  dirty and logs a warning; the next interval retries. A chunk over 64 MiB is refused as an I/O
  error.
- **One writer per store.** Opening a folder that is already open throws. Two writers would each
  commit an index missing the other's chunks.
- **Commit cost.** Retained generations' object names are kept in memory. A commit only
  considers objects of the generation that fell out of retention, plus those a failed commit
  wrote, for deletion. The object directory is scanned once, when a writable store opens, for
  objects a crash left behind. Still O(chunks in the storage) per commit: the whole chunk index is
  rewritten. Unmeasured on large worlds. Every NBT write is serialized once more into a byte
  array.
- **Backends.** `AwfStore` is the backend contract: atomic commit, remembered deletions, caller
  owns arrays. `AwfBackend` selects where stores live: `aurora.awf.backend`, default `file`, the
  only one shipped. Another backend is server-internal code registered with
  `AwfBackend.register` before listed worlds load; SourbyCraft ships no database backend or driver.
  A listed or exporting world whose backend is not registered fails to load; there is no
  fallback. Storage ids are region folders relative to the server directory (`world/region`).
- **Observability.** `/perf awf` shows resident, dirty and evicted chunks, pending commits and the
  oldest one's age, commit p50/p95/p99, reads versus region-file fall-throughs and deletes,
  retries, failures and bytes written, per storage.
- **Evidence.** Unit tests in `awf/`: `AwfRegionStorageTest`, `AwfSettingsTest`, `AwfBackendTest`,
  and the tombstone and garbage-collection tests in `AwfWorldStoreTest` and `AwfWorldTest`.
  `AwfQualificationTest` (below). CI step "Boot twice with Aurora World Fabric storing the world"
  boots a fresh world with `worlds = ["world"]`. It checks that a store and a committed generation
  exist and that no `.mca` file was written, then boots again from the store, then a third time
  with `export = ["world"]` and checks that region files appear and the store is retired. That
  is one small world with no players.
  - **CI run 442 (`1e9a5c1a`, 2026-09-27), green.** First boot: 529 chunks per dimension went
    into AWF, and `committed and closed 9 storage(s) at shutdown` was logged. The second boot
    reopened the stores with no region files written; it loaded no chunks, so it proved reopening
    but not reading. The third boot exported 529 chunks per dimension (and 1 entity-storage
    deletion each) back to region files and kept the stores as `*.awf.exported-*`. Run 441 had
    found the lost-shutdown-commit bug fixed by feature patch 0020.
  - **CI run 445 (`d919ea66`), read-back.** The second boot force-loads the spawn area. The
    overworld region storage saw 1089 reads, 560 of which fell through to (absent) region
    files, so 529 chunks, exactly the ones the first boot generated, were served from AWF.
    `/perf storage` showed 3267 chunk-system reads and no pending I/O at that point. The CI
    gate fails unless AWF-served reads exceed fall-throughs.
  - **Demo-panel staging (`9a15f51`, 2026-09-29), fresh `world_awf_test`.** AWF FILE/INCREMENTAL
    used a 5-second commit interval and created 9 stores with no `.mca` while attached.
    After all dirty chunks drained, `/perf awf` showed 0 retries/failures and 0 pending commits.
    A restart logged 6 stores committed/closed at shutdown. On the next boot, overworld
    region read counters were 25 total, 0 region-file fall-throughs; pending I/O was 0.
    Export on a subsequent restart wrote 14 `.mca` files, retired all 9 stores as
    `*.awf.exported-*`, and left 0 active AWF stores. The server's original `world` and config
    were restored after the test. The first 529-chunk overworld commit took 105.5 seconds on
    this panel, a single unqualified observation requiring a separate latency investigation.

Not implemented: MongoDB/MySQL/Redis backends (the SPI exists; no driver is on the classpath and
none is added without a qualification plan); a SlimeLoader compatibility adapter (AWF stores
chunk NBT, not the Slime world format, so an adapter would need a format converter); world-role use
by the engine (every engine storage is `VANILLA`; templates and instances exist only in the
library).

## Qualification
Load/unload loops, COW isolation, crash during each persistence stage, backend timeout/disconnect, shutdown with pending saves, corrupt cache/blob recovery, and 1/50/250/1000-world workloads.

### Storage-layer qualification (`AwfQualificationTest`, in-process)

These run the engine's own objects (`AwfEngine`, `AwfRegionStorage`, FILE backend) with byte
stand-ins for NBT. They do not start a server, simulate power loss below the JVM, or measure
anything under load.

| Workload | Result |
| --- | --- |
| Crash at each commit stage (snapshot written, verified, generation published, committed), then restart | Before the pointer moves, the previous generation is intact; after it, the new one is. The store keeps committing after restart. Pass |
| 200 load/unload cycles, 16 writes each | Every write read back on the next load; store file count stays flat after warm-up; no storage left registered. Pass |
| Shutdown with a commit queued on a stalled lane, late commit runs after | Shutdown commit wins; the late commit does not roll it back. Pass |
| 4 concurrent writers (own chunks) while the lane commits, then shutdown | Every chunk holds its last write after restart. Pass |
| 1 / 50 / 250 / 1000 storages open, written, committed, reopened | All chunks read back; one commit per dirty storage. On the development container: 7 / 177 / 902 / 3871 ms total (one run, not a benchmark) |
| Corrupted object | Read throws; other chunks unaffected. Pass |
| COW isolation | `AwfWorldTest` (library). Pass |

Server-level crash test (`scripts/verify_crash.py --awf`, 2026-10-05, post-migration build): a
fresh world stored through AWF (5 s commit interval) is killed with SIGKILL three times, 3, 8 and
15 s into save traffic from 4 moving clients and a block site rewritten every second. Before each
kill the checkpoint is flushed and every storage reports 0 dirty chunks and 0 pending commits.
After every crash the server boots, the checkpoint is exact (64/64 blocks, 24/24 entities), every
store holding data points at a committed generation, and no region file is written: 53/53 checks.
The world's game time rewinds to the last boot, which is not AWF's doing (see the level.dat
finding in TODO.md); it fails the same way on region files.

Still open: backend timeout/disconnect (no network backend exists) and any measurement under
sustained real chunk load.
