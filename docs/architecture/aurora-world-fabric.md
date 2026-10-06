# Aurora World Fabric

Aurora World Fabric (AWF) is SourbyCraft's native region-aware virtual-world, template, instance, storage and persistence runtime.

## Identity and compatibility
AWF is not branded as ASP/SWM. AdvancedSlimePaper `dev/26.2` remains an upstream technical reference and API compatibility target. Directly reused upstream source must retain license/attribution required by its license.

## World roles
The storage library defines `VANILLA`, `VIRTUAL`, `TEMPLATE`, `INSTANCE`, `READ_ONLY`,
`TEMPORARY`. The engine uses `VANILLA` and `INSTANCE` for managed runtime worlds. These roles
are distinct from creation requests; `VIRTUAL`/`TEMPORARY` are not runtime creation modes
provided by `AuroraWorlds`.

## Ownership
Mutable chunk/world state is accessed and mutated by its owning Aurora region. Storage/serialization workers consume immutable snapshots only.

Ordinary region/entity owners must not wait on lifecycle/storage futures. The existing
global-thread creation/loading compatibility path still waits for I/O preparation, as described
below; prefer asynchronous calls. No CountDownLatch-style world lifecycle bridge is introduced.

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
Implemented backends: FILE and Redis. MongoDB/MySQL and a legacy SlimeLoader API adapter remain
planned. The runtime service exposes CompletableFuture-based lifecycle methods.

## Required metrics
Loaded worlds, resident/dirty chunks, save queue depth, oldest pending save, serialization/backend p50/p95/p99, retries/failures and bytes read/written.

## Implementation status (26.2 branch)

`dev.iyanz.sourbycraft.awf` integrates FILE and Redis storage under `RegionFileStorage`,
for worlds an operator lists in `aurora.awf.worlds` (default: none) and worlds created through
`AuroraWorlds`. Ordinary worlds retain region files unless selected. Functional and local
runtime evidence is described below; production qualification remains incomplete.

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

Status: worlds, named generators, templates, copy-on-write instances and the Slime importer are
implemented and verified by local end-to-end runs (below), on the FILE backend and on the Redis
backend (next section). MongoDB/MySQL backends are **planned, not implemented**.

- **API.** `dev.iyanz.sourbycraft.api.world.AuroraWorlds` in `sourbyapi`, obtained from the
  services manager: `list`, `exists`, `isLoaded`, `create(WorldCreator, autoload)`, `load`,
  `save`, `unload(name, save)` → `UnloadResult`, `delete`, `autoload`/`setAutoload`. Every call
  returns a future; completion callbacks have no guaranteed thread. World creation/loading
  runs on the global region, and callers on that thread retain the existing synchronous
  compatibility path (waiting for store preparation). Elsewhere, never join a lifecycle future
  on a region/entity thread. Names are `[a-z0-9_-]{1,48}`; storage folder names (`region`, `poi`, `entities`,
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
  committing and a template must be one consistent moment. The world's stores may be on any
  backend; the template itself is always written to disk under `awf-templates/`.
- **Instances.** `createFromTemplate(template, name)` registers a world with role `INSTANCE` whose
  stores take the template's stores as their read-only base: a chunk the instance never wrote is
  read from the template; a written chunk is the instance's own; a deleted chunk shadows the
  template and is generated again. All instances of a template share one opened template store
  (and its chunk index) in memory and one copy on disk. A template cannot be deleted while any
  world is an instance of it.
- **Slime import.** `importSlime(file, creator, generator, autoload)` converts a `.slime` file as
  SourbyCraft's former Slime world manager wrote it — format **v12** (zstd, light as two booleans
  per section, entities wrapped in a compound) and **v13** (zlib, a POI/tick flags byte) — into
  the new world's stores, then loads it. Each chunk becomes the engine's own chunk NBT with status
  `full`; entities and POI go to their own stores; block entities, ticks (v13) and
  `ChunkBukkitValues` are kept. Light is **not** imported (the old writer recorded it against the
  wrong section heights), so chunks are lit on first load. The chunks keep their data version, so
  the data fixers upgrade older ones. Sections start at -4 for `normal` and 0 for `nether`/`end`.
  The default generator for chunks the file does not hold is `void`. AdvancedSlimePaper files are
  not supported unless they use one of these two layouts. zstd comes from the engine's runtime
  libraries and is called reflectively. The source file is only read.
- **Command.** `/awf import <file> <name> [normal|nether|end] [vanilla|generator=Plugin[:id]]
  [autoload]` — the file path is relative to the server directory.
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

Slime import (2026-10-06, same setup): three real SuperiorSkyblock island files — one v13
overworld (4 chunks, data version 4790), one v12 overworld and one v12 nether (6 chunks each, data
version 4189) — imported with autoload; a known block of each island (grass, andesite,
netherrack, located beforehand by decoding the files) found in the expected section; missing file
and existing name refused; clean stop, 0 `.mca`; after restart the same blocks found again; no
exception or chunk-load error in the logs. 17/17 checks. Entities were written to the entity store
(4 entity chunks in total) but their presence in game was not checked.

A defect this found: `AwfRegionStorage.read` returned "not ours" for any chunk the world had not
written, so instances never read their template and the engine fell through to (absent) region
files. It now reads through the world's base first.

### Redis backend (`aurora.awf.backend = "redis"`)

Status: implemented and tested against a real Redis (unit/integration tests start their own
`redis-server`; CI installs one and requires them to run) and in a local server end-to-end run.
Not qualified under player load or over a real network. MongoDB/MySQL backends are **not
implemented**.

- **What it is.** Every AWF store of every world in Redis instead of `<storage>.awf/`
  directories, so several servers can share world storage. Selected by `aurora.awf.backend =
  "redis"`; applies to every AWF world on the server (managed worlds and `aurora.awf.worlds`).
  Templates stay on disk under `awf-templates/` on each server; instances on Redis read through
  them. RESTART_REQUIRED, like every `aurora.awf` key.
- **Client.** SourbyCraft's own RESP2 client (`awf/redis/RedisClient`), no new dependency: a
  bounded connection pool, `AUTH` (password or ACL user), `SELECT`, TLS with host-name
  verification (`rediss://`), a timeout on every connect, read and pool wait. A connection that
  fails mid-reply is closed, never reused. Credentials are never logged.
- **Layout.** Per store, four keys sharing one hash tag (one cluster slot):
  `<prefix>{<storage id>}:chunks` (hash `x,z` → chunk bytes), `:deleted` (set),
  `:gen` (counter), `:lock` (lease).
- **Atomic commits.** One `#!lua` script per commit checks the lease, applies every change and
  increments the generation; Redis runs it without interleaving and refuses it up front when out
  of memory. A dropped connection leaves the commit applied or not; AWF commits the same chunks
  again, which is idempotent.
- **Durability.** With `wait-for-aof = true` (default) each commit waits for `WAITAOF 1 0`: it
  returns once Redis has written it to its append-only file. Redis without AOF, or older than
  7.2, is reported once in the log and commits are then acknowledged from Redis's memory —
  as durable as that Redis's own persistence settings. Redis keeps no older generations, so
  `retained-generations` does not apply.
- **One writer per world (lease).** Opening a store for writing takes a lease
  (`lease-seconds`, default 60) renewed every third of that. Another server opening the same world
  is refused until the lease is released (unload, delete, clean stop) or expires. Every commit
  checks the lease in its script. A lease that expired while this server stalled is taken back
  only if nobody holds it and the generation is unchanged (nobody committed since); otherwise the
  commit is refused and the chunks stay dirty. The lease names the server by a hash of
  `HOSTNAME` (or `/etc/hostname`) and the server directory, so the same server restarting after a
  crash takes its own lease back at once; any other server waits for expiry.
- **Opening is lazy.** A store reads only chunk coordinates (`HSCAN … NOVALUES`, `HKEYS` before
  Redis 7.4); chunk bytes are fetched one `HGET` at a time when the engine reads that chunk. Over
  a real network every chunk read pays one round trip; there is no prefetch yet.
- **Requirements.** Redis 7.0+ (`#!lua`), an eviction policy that cannot evict these keys
  (`noeviction` or `volatile-*`; an `allkeys-*` policy is warned about at start), AOF for durable
  commits.
- **Config** (`[aurora.awf.redis]`): `uri` (or env `SOURBYCRAFT_AWF_REDIS_URI`; the file wins),
  `key-prefix` (`sourbycraft:awf:`), `pool-size` (8), `timeout-ms` (5000), `lease-seconds` (60),
  `wait-for-aof` (true).
- **Not covered by the backend:** `aurora.awf.export` back to region files works through
  `retire` (keys renamed `…:exported-<millis>`), but region files are written locally.

Local end-to-end run (2026-10-06, macOS aarch64, Redis 8.10 on loopback with AOF, 2 GiB heap,
no players): a Slime island imported into Redis (6 chunks in the hash), lease held while loaded and
released on unload and at a clean stop, block placed and saved, no `.awf` directory and no `.mca`
in the world folder, export to `.awf`, that file imported as a second world with the block present,
Slime → `.awf` conversion imported as a nether world, restart with autoload, `kill -9` leaves the
lease and the restarted server takes it back immediately with its last save intact, delete removes
the Redis keys, no exception in the logs. 27/27 checks. An earlier run of the same script found a
real defect: the process stalled ~43 s (cause not identified; not reproduced), the lease expired
unclaimed, every later commit of that world was refused and its shutdown flush failed. The
generation-guarded take-back above is the fix, with tests for both the commit and the renewal path.

### `.awf` world files (version 2)

`AwfWorldFile`: the portable world format, replacing `.slime` for moving worlds between servers.
One file holds a world's `region`, `entities` and `poi` chunk streams plus metadata
(`format=awf-world`, `environment`, `seed`, `generator`, `world-type`, `data-version`, `source`,
`created`). Layout: `"AWFW" int(2)`, compressed chunks, then the index (metadata and, per stream,
`x z offset stored raw codec crc32c`), then a trailer pointing at the index with its CRC32C.

- Each chunk is compressed on its own — zstd where the server has zstd-jni (it does; the engine
  ships it), deflate otherwise — so a reader decompresses only the chunks it reads and opening
  reads the index alone. A Slime file is one compressed blob that has to be inflated and parsed
  whole.
- Written streaming (only the index in memory), to a temporary file that is forced and moved into
  place; never a half-written file. Every chunk is checked against its CRC32C on read.
- `/awf export <world> <file.awf>` (unloaded world; an instance is exported with its template's
  chunks beneath it), `/awf import <file.awf> <name> [autoload]` (environment, seed and generator
  from the file), `/awf convert <file.slime> <file.awf> [normal|nether|end]`. `/awf import`
  recognises `.awf` and `.slime` by their first bytes.
- `AwfFile` (version 1: one stream, SHA-256) stays as the immutable image the qualification tests
  use.

Measurements (2026-10-06; `AwfLoadBenchmarkTest`, run by hand with `AWF_BENCH_FILE`; macOS
aarch64, 8 cores, JDK 25.0.2; median of 7 runs after a warm-up; one session, not certified, on a
host shared with other work). Data: a generated vanilla-terrain world exported to `.awf` — 3,364
region chunks, 73.2 MiB of chunk NBT, 11.1 MiB as a zstd `.awf` file. Opening the region store and
reading every chunk:

| Source | open | read all | per chunk |
| --- | ---: | ---: | ---: |
| `.awf` world file (zstd) | 1.4 ms | 70.0 ms | 20.8 µs |
| FILE store (`region.awf/` directory) | 11.9 ms | 254.4 ms | 75.6 µs |
| Redis store, loopback, AOF everysec | 2.5 ms | 96.2 ms | 28.6 µs |

Writing all 3,364 chunks once: FILE 14.9 s (an fsync per chunk object), Redis 1.6 s (one commit,
one `WAITAOF`). For one real island (6 chunks): reading every chunk from `.slime` (inflate +
parse + convert to chunk NBT) 0.95 ms against 0.07 ms from the same island as `.awf`, but the
`.awf` file was larger (12,118 vs 8,091 bytes): per-chunk compression loses what a whole-blob
compression shares between chunks, which matters only for tiny worlds.

What these numbers do not say: Redis was on the same machine; over a network each chunk read adds
a round trip, so the Redis column does not transfer to a remote Redis. They are storage-level
timings, not server world-load or tick times. No player load.

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

Not implemented: MongoDB/MySQL backends and a SlimeLoader API compatibility adapter. Redis,
Slime v12/v13 conversion, runtime templates and engine-backed copy-on-write instances are
implemented as described above. Direct compatibility with arbitrary SWM/ASP plugin APIs is not
implied by the converter or by Aurora Bridge admission.

### Robustness fixes 2026-10-07

Unit-tested only (AWF test classes: 151 run, 0 failures, 1 skipped benchmark). No server
boot, soak or Redis-lease run covers them yet. They change correctness, not throughput, and
no performance effect is claimed.

- **Write vs close (TODO P0).** `AwfRegionStorage.write` holds a read lock from its
  closed/discarding check until its bytes are in the world. `close`/`discard` take the write lock
  to change state. A write admitted before close is in the final commit. A write after close
  throws instead of being lost. Test: `AwfLifecycleFenceTest.aWriteAdmittedJustBeforeCloseIsCommittedByIt`.
- **Discard fences queued commits (B78/A3).** `discard()` fences the `AwfWorld`. A commit that
  `save()` queued but that had not started fails with `AwfWorld.FencedException` and commits
  nothing. A commit already running finishes as one atomic commit. `closeStore()` waits for it
  under the commit lock before it releases the store. After `close()`, the FILE store
  (`AwfWorldStore`) refuses commits. Tests: `AwfLifecycleFenceTest.aCommitQueuedBeforeADiscardCommitsNothingWhenItRunsAfterIt`,
  `aFencedWorldRefusesQueuedCommitsWithoutTouchingTheStore`, `aRunningCommitCompletesAndTheStoreClosesOnlyAfterIt`,
  `aClosedFileStoreRefusesCommits`. Redis lease release after close is not re-tested here.
- **Pruning keeps non-regenerable data (B77/A2).** `ChunkPruning` keeps a chunk in any of
  these cases:
  - a non-empty `ChunkBukkitValues`
  - block or fluid ticks
  - `PostProcessing` offsets
  - a structure start (other than `INVALID`) or a structure reference
  - a non-empty `UpgradeData`
  - a top-level key it does not know
  - a biome palette that is not exactly one `minecraft:the_void` or `minecraft:plains` (the
    world's biome source is not known at that layer)

  As a result, a void world without a single default biome of the_void or plains now keeps
  these chunks instead of pruning them. Tests: `ChunkPruningTest.nonRegenerableDataKeepsAnOtherwiseEmptyChunk`,
  `aVoidChunkWithOnlyPluginDataSurvivesUnloadAndReload`.
- **`.awf` files carry WorldProperties (B79/A4).** Export writes a `properties` metadata key
  (JSON), and import restores and validates it. A file without the key imports with no
  properties, as before. Tests: `AuroraWorldFilesTest` (round trip with every field
  non-default, legacy file, malformed refusal).
- **Slime properties applied (B76/A1).** `SlimeImporter.worldData` maps typed ASP properties
  where the mapping is unambiguous: spawn x/y/z(+yaw), difficulty, pvp, allowMonsters/Animals
  and defaultBiome. `importSlime` reads only the file's header and extra data to set the new
  world's `WorldProperties`, and `convertSlime` writes them into the `.awf` file. Anything else
  is logged once per import or conversion as dropped, for example the world's `BukkitValues`,
  `environment`, `dragonBattle` and values that do not fit. The world PDC itself is not restored
  yet. Tests: `SlimeImporterTest.worldPropertiesMapFromTypedValuesAndTheRestIsReportedDropped`,
  `theHeaderOnlyReaderSeesWhatAFullParseSees`, `slimeToAwfToImportKeepsTheProperties`. The service
  wiring (`AuroraWorldsService.importSlimeInternal`) has no unit test because it needs a running
  server.
- **Not changed: B80/A5, B85.** `createAfter` still joins preparation on the global tick. The
  `AuroraWorlds` contract promises a completed future to callers on the global region thread so
  they can join it there. An asynchronous handoff would break that promise, and such callers
  would deadlock. Fixing it needs a contract change first.

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

### Multi-plugin lifecycle admission and creation requests (2026-10-06)

`AuroraWorlds.create(WorldRequest)` adds two immutable request types without replacing the
existing API: `WorldRequest.persistent(name)` and `WorldRequest.fromTemplate(template, name)`.
Both create persistent managed worlds. A clone inherits its template's environment, seed,
generator and world type; it keeps its own changed chunks. `autoload` controls startup loading,
not durability. Neither request is an ephemeral/discard-on-unload mode.

The service reserves a world until its lifecycle operation actually finishes, including failed
creation cleanup. Concurrent create/import/load/save/unload/delete/export/template-save and
`setAutoload` calls on that world fail with `WorldOperationBusyException`, identifying the
resource and active operation. This is admission, not an unbounded queue: a caller can chain
operations after completion or retry deliberately. Different worlds can proceed concurrently
within the existing executor budgets; no new executors or CPU workers are introduced.

Template readers share admission, so clones of different worlds can start from one template
concurrently. Saving/deleting a template requires exclusive admission; deletion still refuses
registered instances. Export and conversion reserve their normalized output path against other
service operations. These reservations cover this service instance, not arbitrary filesystem
writers, direct Bukkit calls, other JVMs or alias paths through symlinks; the backend's existing
writer lease remains responsible for cross-server storage ownership.

Cancelling or manually completing a returned future cannot release its reservation while the
underlying operation is running. A save records region failures but waits for every admitted
region and the fan-out to finish before reporting failure, so a failing save cannot overlap a
new unload/delete while other regions are still saving. Completion releases admission before callbacks run, allowing
`save(name).thenCompose(ignored -> unload(name, true))`. Callback code must explicitly schedule
region/entity mutations on their owners.

Functional evidence is recorded with the delivery tasks in `SPEC.md`; throughput improvement,
real-plugin gameplay compatibility and large-world soak qualification remain separate gates.
Plugin development examples: [AWF plugin API](../guides/developing-awf.md).

Functional verification (2026-10-06, Temurin 25.0.4.1): WorldRequestTest 3/3,
WorldOperationGateTest 7/7 and WorldSaveBarrierTest 3/3 pass. The isolated full Gradle run
passes API 525 tests (2 skipped) and server 10,147 tests (23 skipped), with zero failures/errors.
The gate's simultaneous-plugin test and 100 shared-template readers are in-process simulations,
not 100 running Minecraft worlds. Exact command/log/report provenance is in SPEC §155.
