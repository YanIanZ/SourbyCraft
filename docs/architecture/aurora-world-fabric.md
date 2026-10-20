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
  runs on the global region in stages (see "Staged creation" below) and its future can complete
  on a later global tick even for a caller on that thread (contract changed 2026-10-13; it used to
  complete at once there). Blocking on an unfinished lifecycle future on the global tick throws
  `IllegalStateException`; never join one on a region/entity thread either. Names are `[a-z0-9_-]{1,48}`; storage folder names (`region`, `poi`, `entities`,
  `dimensions`, …) are refused because AWF matches worlds by the storage folder's own path
  (see "What goes through AWF" below); template names follow the same rules, and every template
  operation (create from, delete, read, save, and a template name read back from the registry)
  refuses a name that is not one, so no name resolves outside `awf-templates/` (AWF-4, 2026-10-14).
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
  [autoload]` — the file is read from `awf/imports/` under the server directory; an absolute
  path or a `..` segment is refused (`AuroraWorldFiles.confine`).
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
  again, which is idempotent for the chunk bytes — but not for the lease guard: see defect D2 under
  *Backend failure* below.
- **Durability.** With `wait-for-aof = true` (default) each commit waits for `WAITAOF 1 0`: it
  returns once Redis has written it to its append-only file. Redis without AOF, or older than
  7.2, is reported once in the log and commits are then acknowledged from Redis's memory —
  as durable as that Redis's own persistence settings. Redis keeps no older generations, so
  `retained-generations` does not apply.
- **One writer per world (lease).** Opening a store for writing takes a lease
  (`lease-seconds`, default 60) renewed every third of that. Another server opening the same world
  is refused until the lease is released (unload, delete, clean stop) or expires. Every commit
  checks the lease in its script. A lease that expired while this server stalled is taken back
  only if nobody holds it and no other server committed since: the generation is unchanged, or the
  last commit's token (`:writer`, written by every commit) is this store's own (a commit Redis
  applied whose reply was lost). Otherwise the commit is refused and the chunks stay dirty. A
  store stays on lease renewal for as long as it is open, also after losing the lease, so renewal
  or the next commit takes it back under the same rule (logged). The lease names the server by a hash of
  `HOSTNAME` (or `/etc/hostname`) and the server directory, so the same server restarting after a
  crash takes its own lease back at once; any other server waits for expiry. All of a server's
  renewals go out as one pipeline per pass (one round trip however many stores are open; until
  2026-10-14 they were serial, so N stores × `timeout-ms` could outlast the lease), and a pass
  slower than half the renewal period is logged as a WARN (AWF-6). A store the engine opens and
  then does not use — prepared for a world whose creation failed (`AwfEngine.unprepare`), a
  duplicate open of an already-open folder, an export's read-only store — is closed, releasing
  its lease (AWF-5).
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

#### Backend failure (Redis gone / lease lost on a running server)

Build 47 release gate *backend failure*. **State: local scenarios PASS after fixes (2026-10-14
rerun below); D1–D3, D5 and a write-hang found in the rerun (D7) fixed in the working tree,
uncommitted, not in CI; D4 only mitigated (log line), D6 open (report only).** Local laptop
evidence only, not a qualification.

**Designed behavior (from code, before testing).**
- *Redis gone.* Every call fails within `timeout-ms` (connect, read and pool wait each bounded;
  `RedisClient`). A periodic commit (`AwfRegionStorage.maintain`, off the region threads) fails after
  `commit-attempts` and its chunks stay dirty in memory; the next interval retries. Writes from the
  engine never touch Redis (`AwfRegionStorage.write`). A chunk AWF does not hold is answered from the
  in-memory index and the region file, so new terrain still generates. A chunk AWF holds but has
  evicted needs an `HGET` and fails. `save-all flush` on a region thread only starts an async commit.
  A shutdown/close commit (`saveNow`) fails after its attempts; the chunks are reported lost.
  Lease renewal failures are logged and the store stays registered.
- *Redis back.* The next commit or renewal succeeds. A lease that expired meanwhile is taken back
  only if nobody holds it and `:gen` still equals the generation this store last saw.
- *Lease lost/stolen.* Lease = `SET <prefix>{id}:lock <serverId/uuid> PX lease-ms`, renewed every
  `lease-seconds/3` by `RENEW_SCRIPT`. When another holder has it, or `:gen` moved, renewal logs
  "lost its Redis lease" and every commit is refused in its script (`AWFLEASE`); chunks stay dirty
  and are lost at stop.

**Setup.** Jar `rc-cbef3fb9.jar` (sha256 `975d3bda…`, head `cbef3fb9` tree), Apple M2, 16 GiB, macOS
27.0.1, JDK 25.0.2, `-Xms2G -Xmx2G -XX:+UseG1GC`; `redis-server` 8.10.2 started for the run on
127.0.0.1:6391 (`appendonly yes`, `appendfsync everysec`, `noeviction`); no players; view distance 4.
`sourbycraft_config/aurora.toml`: `[aurora.awf] worlds = ["world"]`, `backend = "redis"`,
`commit-interval-seconds = 5`, `resident-chunks = 1` (forces reads of committed chunks back from
Redis), `commit-attempts = 3`; `[aurora.awf.redis] uri = "redis://127.0.0.1:6391/0"`,
`lease-seconds = 15` (shortened so expiry happens in the run), `timeout-ms` default 5000. Global TOML
as `scripts/verify_bootstrap.py` (auto-provision and auto-update off). Load: console `setblock` at
chunk (0,0) every 2 s plus `save-all` every 4 s. Markers: diamond at 1600 100 1600 (chunk 100,100),
emerald + gold at 1700/1701 100 1700 (chunk 106,106), committed and evicted before any failure.
Faults: `kill -9` / `kill -STOP` / `kill -CONT` on the redis-server PID; `redis-cli DEL|SET … PX|INCR`
on the lock/gen keys. Block checks in game via `fill X Y Z X Y Z minecraft:iron_block replace <block>`
("Successfully filled 1" = present); Redis-side checks by tracing `HSET`/`INCR` in the AOF.

| Scenario | Observed | Verdict |
| --- | --- | --- |
| A1 Redis SIGKILL between commits, down 126 s, load running | Commits fail with `ConnectException`, retried (`retries/failures` 42/21), chunks stay dirty (56); renew warnings every 5 s; region MSPT avg 0.15 ms, no blocked thread; new terrain at -2000,-2000 generated; `save-all flush` printed "Saved the game" | Designed (the flush message is misleading, see D4) |
| A1 read of evicted AWF chunk 106,106 while down | `Failed to load chunk data … chunk data will be lost` for 25 chunks; gold marker absent in game | **D1** |
| B Redis restarted on same port/AOF | First commit ~1 s later; expired leases taken back ("had expired … taken back"); dirty chunk (0,0) committed (AOF gen 9→10) | Designed |
| B, chunk 106,106 | The same first commit wrote the *regenerated* 106,106 (no emerald/gold) over the stored one; emerald absent after reboot | **D1 (data loss)** |
| C1 lock key deleted | Taken back by the next commit within 1 s, renewals continue; no log line | Designed |
| C2 lock set to another holder | `AWFLEASE` on every commit, "lost its Redis lease" ERROR, gen unchanged, sponge not in Redis | Designed |
| C2b other holder deletes the lock without committing | Next commit silently retakes the lease (sponge committed, persisted after reboot); renewals never resume: PTTL decays to ~0 and the key lapses every 15 s, refreshed only by the next commit's take-back | **D3** |
| C3 other holder + `INCR gen`, then `stop` | All commits refused; `stop` in 1 s; "could not commit … uncommitted chunks are lost"; glass block absent after reboot; other leases released | Designed |
| A2 Redis SIGSTOP 25 s, SIGCONT, SIGKILL 0.3 s later, restart after lease expiry | During freeze: reads of evicted chunks time out serially (5 s each, chunk 100,100 regenerated = D1 again), MSPT unaffected. After SIGCONT Redis ran 3 queued commit attempts (AOF gen 45, server saw 42). After restart: no holder, yet every overworld/region commit refused `AWFLEASE` and "lost its Redis lease" until the world was reopened (reboot: commits fine) | **D2** |
| D1 `stop` with Redis SIGKILLed | < 1 s; each failed close reported | Designed |
| D2 `stop` with Redis SIGSTOPped, dirty chunks pending | 105 s, bounded (jstack: `RedisClient.read` in `AwfRegionStorage.close` and `renewAll`); "committed and closed 0 storage(s) at shutdown, 6 failed" | Designed (bounded; scales with stores × attempts × timeout, see D6) |
| D2 … after SIGCONT | Redis executed the dead server's queued commit (crying obsidian present after reboot, gen 47) although the server had logged it as lost | Designed (indeterminate commit); message inaccurate (D5) |
| Clean `stop` with Redis healthy (boot 4) | 3 s, 0 lock keys left, 0 ERROR lines | Designed |

**Defects found 2026-10-13 (root cause as found; fixes in *Fixes* below).**
- **D1 — data loss.** `AwfRegionStorage.read` (`AwfRegionStorage.java:117`) lets the backend's
  `IOException` escape from `RegionFileStorage.read` (feature patch, `RegionFileStorage.java:214` in the
  materialized tree). Moonrise treats a failed read as "no data"
  (`ChunkLoadTask.java:333-334`: logs "chunk data will be lost", returns `null`), so the chunk is
  generated from scratch, saved through `AwfRegionStorage.write`, and committed over the stored chunk
  once Redis answers. Same for entity data. Fix: never let a store read failure become "absent" for a
  chunk the store holds — retry with backoff on the I/O thread, and if it still fails, remember the key
  as unreadable and refuse (drop with an ERROR) any later write of that key until a successful read, so
  a regenerated chunk can never replace stored data; ideally fail the chunk load instead of generating.
- **D2 — permanent fence without a competitor.** `RedisStore.commit` updates `knownGeneration` only
  from a reply (`RedisStore.java:207-208`). A commit that Redis applied but whose reply was lost
  (timeout, crash after execution) moves `:gen` ahead; once the lease then expires, both the commit
  script (`RedisStore.java:52`) and `RENEW_SCRIPT` (`RedisBackend.java:62`) see "no holder, generation
  changed" and refuse forever. Fix: have the commit script also record the committing token
  (e.g. `:writer`), and allow the take-back when there is no holder and either the generation is
  unchanged or the last writer is this store's own token.
- **D3 — lease not renewed after a take-back.** On a lost lease `renewAll` removes the store from
  `leased` (`RedisBackend.java:249`), but the commit script still retakes a free lease
  (`RedisStore.java:52-55`), after which nothing renews it, so the single-writer lease lapses between
  commits, and the ERROR text ("refused until the world is opened again") is false. Fix: either make a
  lost lease terminal for that store (local flag checked before every commit), or return a
  "retaken" marker from the commit script and re-register the store for renewal, with a log line.
- D4 (reporting) — on a region thread `AwfRegionStorage.flush` only starts an async commit
  (`AwfRegionStorage.java:199-202`), so `save-all flush` prints "Saved the game" while AWF commits fail.
- D5 (reporting) — `AwfEngine.closeEvery` (`AwfEngine.java:515-519`) says "chunks written since the last
  commit are lost" also when only the lease release failed (no dirty chunks), and when the commit was
  in fact applied (D2 run).
- D6 (risk) — a frozen Redis makes `stop` take up to stores × (`commit-attempts` + 1) × `timeout-ms`
  (105 s here for 9 stores); there is no overall shutdown deadline for AWF.

**Fixes (2026-10-14, working tree, uncommitted; SourbyCraft-side only, no Minecraft/Paper patch).**
- **D1** (`AwfRegionStorage.read`/`readStored`/`fence`/`recovered`, `AwfRegionStorage.java:151-215`):
  a read of stored bytes is retried (3 attempts, 100/400 ms backoff, no new attempt after 5 s, none
  on a region thread or after close). If it still fails the position is *fenced* before the
  `IOException` is thrown: every later write of it is refused and counted (`admittedWrite`, :254), so
  the chunk the engine regenerates is never committed; the stored bytes stay. The fence is lifted
  only by a successful read on the chunk-load path (`RegionFileStorage.moonrise$readData` on the
  stack): that read means the regenerated chunk has left memory and the stored one is being loaded.
  A successful read elsewhere (structure scan) returns the stored bytes but keeps the fence, since
  the regenerated chunk may still be loaded. After a read gives up, every read in the server gets a
  single attempt for 5 s (`FAIL_FAST_NANOS`): the first rerun showed a chunk load pulls in hundreds
  of stored neighbours and the chunk system has one I/O thread here; retrying each kept the I/O queue
  busy for minutes, and `save-all flush` waits for that queue on the region threads (`MoonriseRegionFileIO.flush`).
  `/perf awf` shows `read retries / failed reads / unreadable / refused writes` once non-zero; close
  logs how many positions stayed fenced. Moonrise still logs "chunk data will be lost" and generates
  the chunk in memory; the store is what is protected. Not covered: a POI or entity store whose
  read succeeded while the chunk store's failed keeps committing (derived from its own stored data).
- **D2** (`RedisStore.COMMIT_SCRIPT` :54, `RedisBackend.RENEW_SCRIPT` :59): every commit writes its
  lease token to `<prefix>{id}:writer`; a free lease is taken back when the generation is unchanged
  *or* the last writer is this store's token. `delete` removes `:writer`, `retire` drops it.
- **D3** (`RedisBackend.renewAll` :259, `retaken` :240, `RedisStore.commit` :225): a lost lease no
  longer removes the store from renewal; the loss is logged once per episode with a true message; a
  take-back by renewal or by a commit (the script now returns `{generation, retaken}`) is logged and
  renewal simply continues.
- **D4** (mitigation, `AwfRegionStorage.flush` :323): a region-thread flush (`save-all flush`) logs
  a WARN naming the storage and its in-memory chunk count when its last commit failed. `save-all`
  still prints "Saved the game".
- **D5** (`AwfRegionStorage.close`, `CloseFailure`, `AwfEngine.closeEvery` :510): a close whose
  commit succeeded but whose release failed no longer throws (WARN "no chunks are lost"; counted as
  "not released on the backend" in the shutdown summary). A failed final commit throws
  `CloseFailure` with the uncommitted count and says the backend may still have applied it.
- **D7 (new, found in the rerun)** — `RedisClient` bounded reads (`SO_TIMEOUT`) but not writes. With
  Redis SIGSTOPped, a commit larger than the TCP buffers blocked in `SocketOutputStream.write` for
  good while holding `AwfWorld.commitLock`; `stop` then waited on that lock (jstack). Observed: `stop`
  still running after 10 min, finished 1 s after SIGCONT. Fix (`RedisClient.WriteWatch` :260): request
  bytes are written in 64 KiB slices that record progress; a watchdog closes the connection when no
  byte went out for `timeout-ms`, and the call fails with `SocketTimeoutException`.
- **D6** not changed: a frozen Redis still makes `stop` take stores × attempts × `timeout-ms` (115 s
  in the rerun). No overall AWF shutdown deadline.

Tests (all with `SOURBY_REQUIRE_REDIS=1`): `AwfReadFailureTest` (6: transient failure retried and
invisible; regenerated chunk never committed, stored chunk committed again only after a load-path
read; off-load-path read keeps the fence; fail-fast window; no retry after close; release-only
close is not a loss / failed final commit reports its count), `RedisBackendTest`
`aCommitWhoseReplyWasLostDoesNotFenceTheStoreForever` (D2, renewal and commit path, plus another
server's commit still fences), `aLeaseTakenBackByACommitIsRenewedAgain` (D3),
`aWriteThatMakesNoProgressFailsWithinTheTimeout` (D7; failed in 0.5 s). Full suites 2026-10-14:
server 10,230 tests / 0 failures / 23 skipped (10,221 before + 9), API 525/0/2, test-plugin 1/0;
Python 281 OK; `patch_surface.py --check` passed.

**Rerun 2026-10-14 (after the fixes).** Same host, JDK, heap, Redis 8.10.2 and `aurora.toml` as
above, on a fresh world (seed 440044) and a fresh Redis on 127.0.0.1:6392, Minecraft on 25622; same
load (`load.sh`) and fault methods. In-game checks with a non-destructive probe (`clone X Y Z X Y Z
X Y+30 Z filtered <block>`, "cloned 1" = present, one probe at a time; console `execute if block`
is refused cross-region). Jars: `d1d0034a…` (A1–C3, A2), `4d0cc1de…` (stop with Redis killed,
frozen-Redis stop that exposed D7), `d55469a0…` (final tree: retry under load, stolen lease, frozen
stop, reboot check, clean stop). Evidence: `scratchpad/gates/redis2/` (`events.txt`, `boot1-6.log`,
`a1.jstack`, `s3.jstack`); the first attempt without the fail-fast window is kept in `redis2-attempt1/`.

| Scenario | Before (2026-10-13) | After (2026-10-14) |
| --- | --- | --- |
| A1 read of evicted committed chunk 106,106 with Redis SIGKILLed | regenerated, markers gone | Still regenerated in memory (Moonrise); 729 positions fenced, 60 then 781 regenerated writes refused; `save-all flush` WARNs that commits fail |
| B Redis restarted on same AOF | regenerated 106,106 committed over the stored one (D1) | AOF: no `HSET 106,106` after the kill; Redis copy keeps emerald + gold; after unload/reload the stored chunk loads (probe: emerald and gold present), 25 positions unfenced by load-path reads; markers present after the reboots checked (boots 2, 3 and 6) |
| A1 retry under load (final jar, Redis down 30 s) | designed | commits fail and stay dirty, retried; recovered on restart (gen → 64); worst region MSPT avg 0.35 ms |
| C1 lock deleted | designed | taken back by renewal, now logged |
| C2 lock set to another holder | designed | AWFLEASE on commits, one "lost its Redis lease" ERROR, gen unchanged (64 → 64 during the steal, final jar) |
| C2b holder deletes lock without committing | retaken by commit, never renewed (D3) | taken back by renewal (WARN); PTTL sampled every 5 s for 40 s stayed ~10 s (renewed every 5 s), lease never lapsed; sponge committed and present after reboot |
| C3 other holder + `INCR gen`, then `stop` | designed | commits refused; `stop` 1 s; "could not confirm the final commit … lost"; glass absent after reboot |
| A2 Redis SIGSTOP 25 s, SIGCONT + SIGKILL 0.3 s later, restart after lease expiry | permanent AWFLEASE with no holder (D2) | Gen 37 at SIGSTOP, 39 after replay (commits applied, replies lost: `EOFException`); after restart 0 AWFLEASE, overworld/region lease "taken back by a commit", commits continue (gen 43). During the freeze each evicted-chunk read timed out once (5 s) and was fenced; diamond chunk regenerated in memory, kept in Redis, present after unload/reload and after reboot |
| `stop` with Redis SIGKILLed | < 1 s; release failures reported as lost chunks (D5) | 1 s; stores with no dirty chunk: WARN "committed … could not release … no chunks are lost", summary "(6 not released on the backend)"; stores with dirty chunks: loss reported with count |
| `stop` with Redis SIGSTOPped, dirty chunks | 105 s, bounded | jar `4d0cc1de`: **hung > 10 min** (D7, writer blocked in socket write), exited 1 s after SIGCONT. Final jar: 115 s, bounded (D6) |
| Clean `stop`, Redis healthy | 3 s, 0 lock keys | 3 s, 0 lock keys, 0 ERROR lines in that boot |

Left open: D6 (no overall shutdown deadline); D4 beyond the log line (`save-all` output);
Moonrise's misleading "chunk data will be lost" line on a fenced read; a regenerated chunk is
playable in memory while Redis is down (writes to it are dropped at the store, so player edits to
such a chunk are lost by design); the load-path check is the `moonrise$readData` frame, so a
Moonrise read that is not a chunk load (`ChunkMap.read`, e.g. a plugin reading chunk NBT) and
succeeds while the regenerated chunk is still loaded would lift the fence early (not observed, not
tested); a Redis-over-network run and CI have not exercised any of this.

Side observation, not root-caused and not tied to Redis: `save-all` threw `IllegalStateException:
Ticket already propagated` (`SaveAllCommand.java:108` → `TicketHolder.propagate`) 2 times in ~90
`save-all` commands sent every 4 s. Root-caused and fixed 2026-10-14 as AWF-2 (see "Audit fixes
2026-10-14").

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
- The command's files are confined (audit OBS-11): `/awf import` and `/awf convert` read from
  `awf/imports/`, `/awf export` and `/awf convert` write to `awf/exports/`, both under the server
  directory. A name may use sub-folders (`2026/hub.awf`) and may be typed back as the command
  printed it (`awf/exports/hub.awf`); an absolute path or any `..` segment is refused with the
  folder named, and a symbolic link inside the folder that leaves it is refused too
  (`AuroraWorldFiles.confine`, `AuroraWorldFilesTest`, `AwfCommandTest`). The `AuroraWorlds` API
  still takes any `Path`: a plugin has the file system anyway.
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

Writing all 3,364 chunks once: FILE 14.9 s (an fsync per chunk object, measured before 2026-10-20; FILE now forces all new objects in one pass before the index — local uncertified A/B on another disk, 34.1 s → 7.9 s median), Redis 1.6 s (one commit,
one `WAITAOF`). For one real island (6 chunks): reading every chunk from `.slime` (inflate +
parse + convert to chunk NBT) 0.95 ms against 0.07 ms from the same island as `.awf`, but the
`.awf` file was larger (12,118 vs 8,091 bytes): per-chunk compression loses what a whole-blob
compression shares between chunks, which matters only for tiny worlds.

What these numbers do not say: Redis was on the same machine; over a network each chunk read adds
a round trip, so the Redis column does not transfer to a remote Redis. They are storage-level
timings, not server world-load or tick times. No player load.

### Engine integration (RegionFileStorage)

Operator guide: [testing AWF](../guides/testing-awf.md).

Patches (verified 2026-10-14 against the materialized repo): the `RegionFileStorage` hook
(`sourby$attachAwf` and the read/write/flush/close paths) is in MC feature patch
`0021-SourbyCraft-count-chunk-system-storage-traffic-for-p.patch`, together with the `/perf storage`
counters it was introduced with — not in the engine-baseline
`minecraft-patches/sources/.../RegionFileStorage.java.patch`, which this section used to name. That
follows the AGENTS.md rule (SourbyCraft edits to Minecraft classes are feature patches); the patch's
name does not say it carries the AWF hook, which is a naming wart, not a policy breach. The shutdown
commit is MC feature patch 0019.
Sourby side: `AwfEngine`, `AwfRegionStorage`, `AwfSettings`.

- **What goes through AWF.** Every `RegionFileStorage` whose folder belongs to a listed or managed
  world: chunk data, entities and POI of that world, each dimension separately. The store lives
  beside the folder (`region` → `region.awf`). Which world a folder belongs to is read from the
  folder's own end of its path (`AwfRegionStorage.worldNames`): for the 26.2 layout
  `<level>/dimensions/<namespace>/<name>/<storage>` it is the dimension `<name>` and the level
  folder `<level>`; for any other layout inside the server directory, the first element of the
  path relative to it; outside it, the storage folder's parent and grandparent. Until 2026-10-14
  any path element matched, so a managed world named like a directory above the server (`home`
  for `/home/mc/server`) attached every world to AWF and could make the main world read that
  world's template chunks (AWF-1; `AwfRegionStorageTest.anAncestorDirectoryNamedLikeAManagedWorldDoesNotAttach`).
- **Hooked paths.** Moonrise's `readData`/`startWrite`/`finishWrite` (the chunk system's I/O),
  and vanilla `read`, `write` and `scanChunk` (structure checks, upgrade tools), `flush` and
  `close`.
- **Layering.** The world's region files are the read-only base. A chunk the engine writes or
  deletes afterwards belongs to AWF; untouched chunks are still read from the region file.
  Region files under an AWF store are never written or cleared.
- **Durability.** A write is held in memory. A commit starts on the governed `STORAGE` lane once
  written chunks have waited `aurora.awf.commit-interval-seconds` (RESTART_REQUIRED; read once when
  the AWF engine is first created). A daemon thread of its own, `SourbyCraft-AWF-Maintenance`
  (`AwfMaintenanceDriver`, started and stopped by `MetricsRuntime`), checks once a second, one second
  after each pass ends. Worst-case loss bound: commit interval + up to 1 s pass delay + commit time.
  Until 2026-10-14 this check ran on the metrics collector thread after the metrics were collected,
  and was skipped whenever a collection ran late, took over one period, or threw, i.e. under exactly
  the load where commits matter, so "the writes since the last interval" could be many intervals
  (AWF-3). Each driver step has its own `try`/`catch`, and a stalled collector no longer stops commits
  (`AwfMaintenanceIndependenceTest`, `AwfMaintenanceDriverTest`). Periodic commits still stop if the
  driver thread itself stalls. At shutdown, feature patch 0019 commits and
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
    found the lost-shutdown-commit bug fixed by the shutdown-commit feature patch (MC 0019 on the
    current branch).
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
  `aClosedFileStoreRefusesCommits`. Redis lease release after close is not re-tested here
  (a clean stop left 0 lock keys in the 2026-10-14 backend-failure run, *Backend failure* above).
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
- **Staged creation (B80/A5, B85), 2026-10-13, local, uncommitted, not in CI; LIVE for every
  AWF create/load/clone/import, no setting.** `createAfter` composes on store preparation
  instead of joining it, then hops to the global tick. There it calls
  `CraftServer#sourbycraft$createWorldStaged` (Paper feature 0014), which runs `createWorld` up to
  `WorldInitEvent` (`MinecraftServer#initWorldBeforeSpawn`, MC feature 0029), computes the chunk
  the spawn search loads first without loading it (`initialSpawnSearchChunk`, same checks as
  `setInitialSpawn`: spawn already set, forced, debug, generator-fixed → none), and starts that
  chunk's load to `SPAWN` with the same non-full ticket `syncLoadNonFull` uses
  (`ChunkTaskScheduler#beginNonFullLoad`). This is legal before `RegionizedServer.addWorld`
  because it is exactly what the old path did from the global tick: the chunk system generates
  non-full chunks on its workers without region or global tasks. A virtual thread polls the
  chunk (lock-free read) and queues the rest on the global tick: the unchanged spawn search
  (`initWorldSpawn`), the ticket's removal, `prepareLevel`, `addWorld`, `WorldLoadEvent`.
  `Bukkit.createWorld` is unchanged and synchronous. The operation lease stays held until the
  future completes. On failure after construction the half-built world is unloaded with
  `unloadWorldAsync`, then the service removes its stores, folder, registry entry and engine
  management off the region threads. If the server stops first, the future fails, the world is
  saved by the shutdown with its spawn unset and stays registered, and its next `load` searches
  the spawn (verified locally: `stop` during create exited 0 in 2.1 s; the later load found the
  same spawn as an uninterrupted create). Exposure: until the future completes
  `Bukkit.getWorld(name)` returns the world before `WorldLoadEvent` and without global ticking,
  as it did inside `createWorld`, now for a few ticks; AWF operations on the name are refused by
  the gate and `unloadWorldAsync` refuses the world (`FAIL_UNKNOWN`); other plugin access is not
  guarded. Residual: only the first spawn chunk is prefetched; a spawn search that moves to other
  spiral chunks loads them synchronously as before. A plugin generator's
  `getFixedSpawnLocation` is called twice. Evidence (Apple M2, 8 cores, one uncertified run per
  case, no players, `awf_create_stall.py`, max gap between console `say` echoes): normal seeds
  11/22/33 3.29/2.30/2.19 s → 0.10/0.14/0.06 s, nether 0.73 → 0.06 s, from template 1.98 →
  0.10 s, clone 1.98 → 0.06 s, void/flat ~0.1 s both; reply time unchanged. Spawn positions from
  `level_overrides.dat` identical for all 10 worlds compared. A post-construction failure and a
  plugin generator were not run.

### Audit fixes 2026-10-14 (AWF-1 … AWF-10)

From a code audit of AWF; each finding was checked against the code before it was fixed, and each
fix has a regression test. Correctness fixes only; no performance effect is claimed. Evidence:
unit tests plus one local boot (see the end of this list); no soak, no multi-server Redis run.

- **AWF-1 world matching by ancestor directory** — fixed; see "What goes through AWF" above.
- **AWF-2 lost save ticket.** `TicketHolder.pop()` read the ticket outside its lock and then
  cleared it, so a ticket propagated in between was cleared unseen: its `save-all` never finished
  and an `AuroraWorlds.save` held its world's operation lease until restart. A second propagation
  before the region ticked threw "Ticket already propagated". Now `pop` reads and clears under one
  lock, and a second save (or unload) ticket coalesces with the present one: one save, flushed if
  either asked, every caller's callback run (`TicketHolderTest`, including a 4-thread
  propagate/consume loop asserting callbacks == propagations).
- **AWF-3 commits stopped under load** — fixed; see "Durability" above.
- **AWF-4 template path traversal** — fixed; see the API bullet above (`AuroraTemplatesTest.aNameWithParentReferencesIsRefused`).
- **AWF-5 leaked stores/leases** — `AwfEngine.unprepare` closes the stores it drops; an open of an
  already-open folder is refused before a second store (and lease) is taken; a store opened for a
  storage that then fails to register is closed; the export's read-only store is closed
  (`AwfEngineStoreLifecycleTest`, incl. `anUnpreparedStoreReleasesItsLease` against a real Redis).
- **AWF-6 serial lease renewal** — pipelined, WARN on a slow pass (see the Redis lease bullet;
  `RedisBackendTest.leaseRenewalIsOneRoundTripPerPassWhateverTheNumberOfStores`, stub client).
  Not measured against a slow real Redis.
- **AWF-8 unload callback skipped on failure.** `WorldShutdownThread` rethrew a chunk-system halt
  or chunk-save failure without invoking the unload ticket's callback, so the unload future (and
  the world operation lease) never completed. MC feature patch 0030 runs the unload through
  `WorldUnloadCompletion`: `FAIL_UNKNOWN` on a throw (still rethrown, so the thread's handler stops
  the server as before), `SUCCESS` otherwise (`WorldUnloadCompletionTest`). The world operation
  gate now also logs, once, any operation holding its world over 5 minutes, checked once a second
  on the AWF maintenance thread (`WorldOperationGateTest.aLeaseHeldPastTheThresholdIsReportedOnceAndAReleasedOneNever`).
- **AWF-9 Slime import memory.** Blobs up to 1 GiB, unlimited NBT accounting and a whole-file
  `readAllBytes` let a crafted file exhaust the heap. Now one limit, JVM property
  `-Dsourbycraft.awf.slime.max-bytes` (default 256 MiB, read at each import, so LIVE for the next
  one), bounds the file size before it is read, every compressed and decompressed blob length and
  NBT value length before allocation (and a compressed length longer than the remaining file), and
  NBT heap accounting (`SlimeImporterTest.aBlobOverTheLimitIsRefusedBeforeAllocation`).
- **AWF-10 docs** — this section, the patch numbers and hook location above, and `WorldRole`'s
  javadoc (it said no world used a role; VANILLA, INSTANCE, TEMPLATE and READ_ONLY are used).
- **Not done:** AWF-7 (delta generations) is roadmap only.
- **Local boot, 2026-10-14** (slim jar from this tree, fresh flat world, offline, no players): six
  `save-all`/`save-all flush` in a row, `awf create t1 normal 5`, `awf save t1` ("Saved t1
  (committed)"), `save-all flush`, `awf unload t1` ("Unloaded t1"), `stop` → exit 0, no exception.
  With no players no region held chunks, so the save tickets fanned out to zero regions ("No regions
  to save"): the boot shows nothing broke, not that the AWF-2 race is gone in a live server; that
  rests on `TicketHolderTest`.

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
