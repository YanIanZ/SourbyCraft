# Aurora World Fabric

Aurora World Fabric (AWF) is SourbyCraft's native region-aware virtual-world, template, instance, storage and persistence runtime.

> **Related:** AWF MultiProfile builds durable player profiles on this API and keeps every AWF
> invariant (template immutability, copy-on-write, save-before-unload, per-world leases) unchanged.
> It is disabled by default; see [`docs/awf/multiprofile/ARCHITECTURE.md`](../awf/multiprofile/ARCHITECTURE.md).

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
  threads. A generation is either a full `index` blob (manifest format 1, no header) or, only when
  delta generations are on, a `delta` blob chained to a base generation by a hash-linked
  `@format 2` / `@base` manifest (see *Delta generations* below). A manifest header this build does
  not know is a corrupt generation, never ignored. A filesystem that refuses an atomic rename fails
  the commit; nothing is published by copying.
- `AwfWorldStore` (FILE backend): a chunk index per generation (a full index, or with
  `aurora.awf.delta-generations` on a delta over a base generation), and chunk bytes in a
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
  depth, oldest pending save, oldest dirty age, serialization and backend p50/p95/p99, retries,
  failures, bytes read and written, and materialised chunks. Loaded worlds are counted by
  `/perf awf` (`AWF loaded worlds`: dimension folders with an open AWF storage); `AwfRegistry` is
  not used by the runtime (only `AwfWorldTest` exercises it). An optional resident limit drops
  clean chunks LRU after each save; they are read back from the store, and dirty chunks are never
  dropped. Each chunk is one immutable entry (bytes, write version, first-dirty time) in one map,
  with a dirty-key index changed only inside that key's `compute`, so eviction removes only the
  exact clean entry it saw and a write racing it is never dropped (Build 50, PR #62; see
  *Build 50 changes* below).
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
  (see "What goes through AWF" below). Since Build 50 (PR #62) `overworld`, `the_nether`,
  `the_end` and the server's level-name are refused as world names too, and a registry entry with
  one is ignored with a WARN at load. Template names follow the folder rules only, and every template
  operation (create from, delete, read, save, and a template name read back from the registry)
  refuses a name that is not one, so no name resolves outside `awf-templates/` (AWF-4, 2026-10-14).
- **Registry.** `sourbycraft_config/aurora-worlds.json` lists the managed worlds (environment,
  seed, autoload, properties). It is written through a temporary file that is forced, an atomic
  move (a plain move where the filesystem refuses an atomic one) and a forced directory. A world in
  it is an AWF world without being listed in `aurora.awf.worlds`, so a world created at runtime
  stays in AWF across restarts. A malformed file stops the stage instead of being read as empty;
  since Build 50 an empty file or a literal `null` is refused the same way (the AuroraWorlds API
  then does not start until the file is restored or removed). Build 50 (PR #62) also: readers see
  a snapshot published only after a write reached the file, so a tick-thread read never waits for a
  write's fsync and sees an entry only once it is durable; each entry records `knownStores` (the
  storage folders whose store existed when the world was last loaded or saved), and a load is
  refused while a recorded store is missing instead of opening an empty store in its place (entries
  written before Build 50 are recorded at their first load or save, until then they load as
  before); a stored spawn that `WorldProperties.Spawn` now refuses is dropped with a WARN naming
  the world instead of failing the file (see *Build 50 changes*). `AuroraWorlds.setAutoload` is a
  synchronous API, so its forced write still runs on the caller's thread (the global tick for a
  console `/awf autoload`); an asynchronous variant is an API change left to the owner.
- **Where data lives.** `world/dimensions/minecraft/<name>/{region,entities,poi}.awf`. No region
  file is written while the world is attached.
- **Thread rules.** The engine creates and unloads worlds on the global tick, which runs on a
  region scheduler thread where AWF refuses blocking store I/O. `create` and `load` therefore
  open the world's three stores first on the I/O lane (`AwfEngine.prepare`); the storages that
  open on the global tick take those stores. `save` sends a save ticket to every region of the
  world, waits for all of them, then commits the stores off the region threads, so a completed
  `save` is durable. Since Build 50 (PR #62) the chunk I/O drain before that commit is bounded by
  `aurora.awf.shutdown-deadline-ms` and each store is committed through
  `AwfRegionStorage.flushConfirmed`, so a save fails, rather than reporting success, when its
  commit is cut by the deadline, fenced, closed or discarding. `unload` uses the engine's
  asynchronous unload; `Bukkit.unloadWorld` is not supported on this engine. `unload(name, true)`
  of a registry world first runs that durable save and, when it fails, refuses the unload and
  leaves the world loaded; a stopping server skips the save (the engine normally answers
  `SERVER_STOPPING`). `delete` requires the world to be unloaded.
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
  backend; the template itself is always written to disk under `awf-templates/`. Since Build 50
  (PR #62) `template.json` and the hidden folder are forced before the move and `awf-templates/`
  after it, and an empty or `null` `template.json` is an error instead of "no template".
- **Instances.** `createFromTemplate(template, name)` registers a world with role `INSTANCE` whose
  stores take the template's stores as their read-only base: a chunk the instance never wrote is
  read from the template; a written chunk is the instance's own; a deleted chunk shadows the
  template and is generated again. All instances of a template share one opened template store
  (and its chunk index) in memory and one copy on disk. A template cannot be deleted while any
  world is an instance of it. Since Build 50 (PR #62) every load path, a plugin's
  `Bukkit.createWorld` of a managed instance included, refuses an instance whose
  `awf-templates/<t>/template.json` is missing (`AwfEngine.requireTemplate`), instead of opening it
  as an empty or regenerated world whose next commits would replace its content.
- **Slime import.** `importSlime(file, creator, generator, autoload)` converts a `.slime` file
  into the new world's stores, then loads it. Two layouts are read: **v12** (zstd, light as two
  booleans per section, entities a list under `entities` in a compound) and **v13** (a POI/tick
  flags byte; the blobs are zlib as SourbyCraft's former Slime world manager wrote them or zstd as
  AdvancedSlimePaper writes them, told apart by each blob's magic bytes; entities a bare list or,
  as AdvancedSlimePaper writes them, a compound holding `entities`). Tests:
  `SlimeImporterTest.version12FilesAreReadToo` and `advancedSlimePapersOwnVersion13IsRead` (a
  fixture built in the test in AdvancedSlimePaper's v13 layout: zstd, compound entities), plus the
  three real island files of the run below. A v13 chunk flag bit this build does not know has its
  value skipped and, since Build 50 (PR #62), is named as `chunk-flags.0x<bit>` in the single
  "did not keep" WARN of the import. Each chunk becomes the engine's own chunk NBT with status
  `full`; entities and POI go to their own stores; block entities, ticks (v13) and
  `ChunkBukkitValues` are kept. Light is **not** imported (the old writer recorded it against the
  wrong section heights), so chunks are lit on first load. The chunks keep their data version, so
  the data fixers upgrade older ones. Sections start at -4 for `normal` and 0 for `nether`/`end`.
  The default generator for chunks the file does not hold is `void`. Other Slime versions are
  refused. zstd comes from the engine's runtime libraries and is called reflectively. The source
  file is only read.
- **Command.** `/awf import <file> <name> [normal|nether|end] [vanilla|generator=Plugin[:id]]
  [autoload]` — the file is read from `awf/imports/` under the server directory; an absolute
  path or a `..` segment is refused (`AuroraWorldFiles.confine`).
- **Command.** `/awf list | info <n> | create <n> [normal|nether|end] [void|flat|amplified|
  large_biomes] [generator=Plugin[:id]] [seed] [autoload] | create <n> from <template> [autoload] |
  load <n> | save <n> | unload <n> [nosave] | delete <n> confirm | autoload <n> on|off |
  template list | template save <world> <template> | template delete <template> confirm |
  clone <world> <new> [autoload] | set <world> <spawn|difficulty|pvp|monsters|animals|biome|bounds|prune>
  <value|none> | compact <world|all>` (plus `import`, above, `export`/`convert`, under *`.awf` world
  files*, and `multiprofile` and `redis`, which have their own permission nodes), permission
  `sourbycraft.command.awf`.
  `compact` forces every open store of the world into a full index on the storage lane and prints
  `full: yes/no` per store (see *Delta generations*). Since Build 50 (PR #62): delete, import,
  export, convert, clone, set, template delete and `unload … nosave` also check
  `sourbycraft.command.awf.admin`, which the command node implies unless that node is set on the
  sender, so operators and existing holders keep every subcommand, and setting it to `false` (in a
  permissions plugin, or as a child of a `permissions.yml` node) takes those eight away and keeps
  the rest (`list`, `info`, `create`, `load`, `save`, `unload` with saving, `autoload`,
  `template list`/`save`, `compact`; LIVE: checked on each execution). `/awf` starts no new work once shutdown
  has begun or after a failed startup (`AuroraRuntime.stopping()`). `set <world> spawn x y z [yaw]`
  refuses a spawn that is not finite with |x| and |z| ≤ 29,999,984, y in [-2032, 2032) and a finite
  yaw (`WorldProperties.Spawn`). `unload` whose result is `FAILED` after the world is gone prints
  "Unloaded <name>, but AWF could not confirm its last commit (see the server log)".

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

### Redis backend (`aurora.awf.backend: redis`)

Status: implemented and tested against a real Redis (unit/integration tests start their own
`redis-server`; CI installs one and requires them to run) and in a local server end-to-end run.
Not qualified under player load or over a real network. MongoDB/MySQL backends are **not
implemented**. The Build 50 changes to this backend (PR #62: commit confirmation, lease identity,
URI refusal, reply bounds) are code written and statically reviewed, not built and not tested by
the owner; the runs described here predate them.

- **What it is.** Every AWF store of every world in Redis instead of `<storage>.awf/`
  directories, so several servers can share world storage. Selected by `aurora.awf.backend:
  redis`; applies to every AWF world on the server (managed worlds and `aurora.awf.worlds`).
  Templates stay on disk under `awf-templates/` on each server; instances on Redis read through
  them. RESTART_REQUIRED, like every `aurora.awf` key.
- **Client.** SourbyCraft's own RESP2 client (`awf/redis/RedisClient`), no new dependency: a
  bounded connection pool, `AUTH` (password or ACL user), `SELECT`, TLS with host-name
  verification (`rediss://`), a timeout on every connect, read and pool wait. A connection that
  fails mid-reply is closed, never reused. Credentials are never logged. Since Build 50 (PR #62)
  a reply is bounded (a bulk string of at most 65 MiB, at most about 8.4M values and 256 MiB per
  reply, nested values included) and any RESP framing error, a malformed number included, is an
  `IOException`; URI parse errors never quote the URI, and log redaction masks everything up to the
  last `@`.
- **Layout.** Per store, four keys sharing one hash tag (one cluster slot):
  `<prefix>{<storage id>}:chunks` (hash `x,z` → chunk bytes), `:deleted` (set),
  `:gen` (counter), `:lock` (lease).
- **Atomic commits.** One `#!lua` script per commit checks the lease, applies every change and
  increments the generation; Redis runs it without interleaving and refuses it up front when out
  of memory. A dropped connection leaves the commit applied or not; AWF commits the same chunks
  again, which is idempotent for the chunk bytes — but not for the lease guard: see defect D2 under
  *Backend failure* below.
- **Durability.** With `wait-for-aof: true` (default) each commit waits for `WAITAOF 1 0`: it
  returns once Redis has written it to its append-only file. Since Build 50 (PR #62) the commit
  script and `WAITAOF` go out as one pipeline on the same pooled connection
  (`RedisClient.evalThen`; before, `WAITAOF` was a separate call that could take another connection,
  and `WAITAOF` waits only for the last write of the connection it runs on), and `WAITAOF` waits
  at most half of the time a call may still take (2500 ms with the default `timeout-ms` of 5000,
  less near the shutdown deadline). A commit Redis applied but did not confirm in that time fails,
  its chunks stay dirty and are committed again (idempotent for the chunk bytes; the commit and
  renew scripts accept this store's own last-writer token, so the lease is not lost over it).
  Redis without AOF, or older than 7.2, is reported once in the log and commits are then
  acknowledged from Redis's memory — as durable as that Redis's own persistence settings. Redis
  keeps no older generations, so `retained-generations` does not apply.
- **One writer per world (lease).** Opening a store for writing takes a lease
  (`lease-seconds`, default 60) renewed every third of that. Another server opening the same world
  is refused until the lease is released (unload, delete, clean stop) or expires. Every commit
  checks the lease in its script. A lease that expired while this server stalled is taken back
  only if nobody holds it and no other server committed since: the generation is unchanged, or the
  last commit's token (`:writer`, written by every commit) is this store's own (a commit Redis
  applied whose reply was lost). Otherwise the commit is refused and the chunks stay dirty. A
  store stays on lease renewal for as long as it is open, also after losing the lease, so renewal
  or the next commit takes it back under the same rule (logged). Lease tokens are
  `owner/incarnation/store` (Build 50, PR #62; `awf/redis/fabric/NodeIdentity`): the owner is a
  hash of the random id persisted in `awf/node-id`, the host name (`HOSTNAME` or `/etc/hostname`)
  and the server directory; the incarnation is new for every boot. While a lease is live only the
  incarnation holding it, or this server's previous incarnation, takes it over. The previous
  incarnation counts only when it is proven stopped through the `awf/node.lock` handoff (each
  process holds that lock for its whole life) and was recorded under the same lock-file fingerprint
  (device, inode and, on Linux, the kernel boot id), so a server restarting after a crash on the
  same host and boot takes its leases back at once. Everything else waits for expiry: a second
  process in the same directory, a copied directory with the same host name and path (also one
  copied while the server ran), a host reboot, or a platform without file keys. The upgrade to
  Build 50 changes the owner value once, so a server that crashed on an older build waits for its
  old leases to expire (a clean stop releases them first). All of a server's
  renewals go out as one pipeline per pass (one round trip however many stores are open; until
  2026-10-14 they were serial, so N stores × `timeout-ms` could outlast the lease), and a pass
  slower than half the renewal period is logged as a WARN (AWF-6). Since 2026-10-20 every script
  (acquire, renew, commit, release) is called by its SHA-1 (`EVALSHA`, `RedisClient.eval` /
  `evalPipeline`), so the script text no longer travels with each commit or renewal; a call Redis
  refuses with `NOSCRIPT` (it did not run: first use, Redis restart, `SCRIPT FLUSH`) is resent
  once as `EVAL`, a second round trip only for that pass (`RedisBackendTest.scriptsRunByTheirSha…`
  against a real Redis, `aPipelineOfScriptCallsResendsOnlyTheRefusedOnesByText`). Bytes or latency
  saved are not measured. A store the engine opens and
  then does not use — prepared for a world whose creation failed (`AwfEngine.unprepare`), a
  duplicate open of an already-open folder, an export's read-only store — is closed, releasing
  its lease (AWF-5). Since Build 50 (PR #62): `delete` and `retire` are each one script that is
  refused while any lease exists (so an export's retire after a crash waits until the crashed
  lease expires and is retried at the next load); a renewal that succeeds more than
  `lease-seconds` after the previous extension (a request Redis ran late retook a lapsed lease and
  its reply was lost) logs one WARN per pass; and `/perf awf` shows an `AWF Redis leases` row
  (held, lost, or not extended for more than two renewal periods) while the backend is registered
  and a storage is open.
- **Opening is lazy.** A store reads only chunk coordinates (`HSCAN … NOVALUES`, `HKEYS` before
  Redis 7.4); chunk bytes are fetched one `HGET` at a time when the engine reads that chunk. Over
  a real network every chunk read pays one round trip; there is no prefetch yet.
- **Requirements.** Redis 7.0+ (`#!lua`), an eviction policy that cannot evict these keys
  (`noeviction` or `volatile-*`; an `allkeys-*` policy is warned about at start), AOF for durable
  commits.
- **Config** (`aurora.awf.redis.*` in `sourbycraft_config/aurora.yml`, RESTART_REQUIRED, not
  seeded into a new file so that a seeded `uri` cannot shadow the environment variable): `uri` (or
  env `SOURBYCRAFT_AWF_REDIS_URI` when the key is absent; the file wins), `key-prefix`
  (`sourbycraft:awf:`), `pool-size` (8), `timeout-ms` (5000), `lease-seconds` (60), `wait-for-aof`
  (true). Since Build 50 (PR #62) an unusable URI (the key present but not a string that parses,
  or, with the key absent, an environment value that does not parse, or a config file that may
  hold the key but cannot be read, unless a readable `aurora.yml` sets the URI) is never replaced
  by `redis://127.0.0.1:6379/0`: the backend is not registered, one ERROR names the key (never the
  value), and every world stored on Redis fails to load with "not registered (the Redis backend is
  misconfigured: …)". The default server is used only when neither the key nor the variable is
  set. The shutdown budget key is `aurora.awf.shutdown-deadline-ms` (see D6), not a Redis key.
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

Build 47 release gate *backend failure*. **State: local scenarios pass after fixes (2026-10-14
rerun below); D1–D3, D5 and a write-hang found in the rerun (D7) fixed, D4 closed by MC 0072 and
D6 by one shutdown deadline (MC 0071, "Fixes 2026-10-22" below). All of these are committed on
26.2 and in the `v26.2-r50` tag (D4/D6: commit `7420098`; the recovery/commit log windows:
`9a6d0c7`); their unit tests are in the server suite.** Evidence limits unchanged: local laptop
and agent test server only, loopback Redis (plus a userspace-proxy approximation in
[awf-backend-failure-2026-10-22.md](../development/awf-backend-failure-2026-10-22.md)), no
players; Redis on a separate host over a real network has not been exercised. Not a
qualification. Build 50 (PR #62) changed the commit/`WAITAOF` path, the lease identity and the
shutdown reporting (*Build 50 changes* below), so these runs are NEEDS RETEST on the Build 50
candidate.

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
`sourbycraft_config/aurora.toml` (the pre-Build-49 TOML file this run used): `[aurora.awf] worlds = ["world"]`, `backend = "redis"`,
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
  `IOException` escape from `RegionFileStorage.read` (feature patch, `RegionFileStorage.java:387` in the
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

**Fixes (2026-10-14; since committed to 26.2, in `v26.2-r50`; SourbyCraft-side only, no Minecraft/Paper patch).**
- **D1** (`AwfRegionStorage.read`/`readStored`/`fence`/`recovered`, `AwfRegionStorage.java:151-215`):
  a read of stored bytes is retried (3 attempts, 100/400 ms backoff, no new attempt after 5 s, none
  on a region thread or after close). If it still fails the position is *fenced* before the
  `IOException` is thrown: every later write of it is refused and counted (`admittedWrite`, :254), so
  the chunk the engine regenerates is never committed; the stored bytes stay. The fence is lifted
  only by a successful read on the chunk-load path (`RegionFileStorage.moonrise$readData` on the
  stack): that read means the regenerated chunk has left memory and the stored one is being loaded.
  A successful read elsewhere (structure scan) returns the stored bytes but keeps the fence, since
  the regenerated chunk may still be loaded. (2026-10-20, MC 0053: a structure scan now reads through `AwfRegionStorage.scanRead`:
  one attempt, never fenced, and for 5 s after a scan read failed, or while reads fail fast, a scan of a
  chunk whose bytes must come from the store is refused with `ScanRefused` without a backend call.
  The callers (/locate, eyes of ender, dolphins, explorer maps via `StructureCheck`) need the answer
  in the same call, so no async path exists without changing vanilla; vanilla answers a throwing scan
  with CHUNK_LOAD_NEEDED and loads the chunk, so the answer is the same and the wait moves to that load,
  which still waits up to `timeout-ms` per attempt: that is the floor. Unit-tested, not run against a
  stalled Redis on a server.) After a read gives up, every read in the server gets a
  single attempt for 5 s (`FAIL_FAST_NANOS`): the first rerun showed a chunk load pulls in hundreds
  of stored neighbours and the chunk system has one I/O thread here; retrying each kept the I/O queue
  busy for minutes, and `save-all flush` waits for that queue on the region threads (`MoonriseRegionFileIO.flush`).
  `/perf awf` shows `read retries / failed reads / unreadable / refused writes` once non-zero; close
  logs how many positions stayed fenced. Moonrise still logs "chunk data will be lost" and generates
  the chunk in memory; the store is what is protected. (2026-10-20, log flood: one `forceload add`
  with Redis down reads the ticket's whole 27x27 square — level 31 propagates to 44, vanilla — and
  every stored chunk fails once: no retry loop or re-queue, each position is read once. The failure
  log (`ReadFailureLog`) is now per storage per 10 s window: the first failure is logged at once with
  its stack and thrown with its cause, the rest are counted, thrown as a stackless
  `RepeatedReadFailure` with the same message, and summarised once ("failed N time(s) in the last
  10 s ... first error: ..."), from `maintain` or close. Fencing and refusals are unchanged. Harness
  `opus-w6-redisflood-1`: SourbyCraft read ERRORs 731 → 3, stack frames 40 936 → 164, log of the
  flood 6.29 MB → 1.23 MB. Moonrise's own two ERROR lines per failed chunk (MoonriseRegionFileIO,
  ChunkLoadTask) are dropped for repeats, without a feature patch, by `RepeatedReadFailureLogFilter`: a
  configuration-wide log4j2 filter installed by the first repeat, re-added on every log4j2
  reconfiguration, that DENYs only records whose throwable (or a cause) is a `RepeatedReadFailure` and
  whose logger is not SourbyCraft's (the cause search goes at most 8 levels deep); the first failure of a window still logs in full, and the summary counts the repeats but does not list their positions. Harness
  `patch-w4-redisflood-1`, same scenario: Moonrise ERRORs 1458 → 4, all ERRORs 1461 → 7, lines
  3099 → 190, bytes 1.23 MB → 24.9 KB. Since 2026-10-22 (commit `9a6d0c7`, in `v26.2-r50`; found in the opus-49-backend run) the recovery INFO "read … again" and the WARN of a failed periodic commit use the same per-storage windowing (`ReadFailureLog.recoveryLog`, 10 s; `commitFailureLog`, 60 s, first with its stack, summary when the window ends or a commit succeeds): agent test server, 529 recovery lines in one second and 28 commit WARN records (603 lines) in a 60 s outage before; after the change one record with its stack and one summary per storage per 60 s window (S5: 129 lines in 75 s). Logging only; evidence [awf-backend-failure-2026-10-22.md](../development/awf-backend-failure-2026-10-22.md). The POI read path was not exercised live (unit/code-path only).) Not covered: a POI or entity store whose
  read succeeded while the chunk store's failed keeps committing (derived from its own stored data).
  *Build 50 (PR #62, code written and statically reviewed; not built; not tested by the owner):* a
  region storage's fence now also makes the POI storage of the same dimension refuse writes and
  deletes of that position (counted as refused writes) until the region storage reads the chunk
  back on the load path, which lifts both. Entities are deliberately not fenced: a refused entity
  save would lose entities that moved into the regenerated chunk, so its structure entities can sit
  beside the stored ones. Fences live in memory only; a POI storage opened after the fence was set
  does not inherit it.
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
  in the rerun). No overall AWF shutdown deadline. (Superseded 2026-10-22, below.)

**Fixes 2026-10-22 (D4, D6; MC feature patches 0071/0072; commit `7420098`, in `v26.2-r50`).**
- **D6 — one AWF shutdown deadline** (`aurora.awf.shutdown-deadline-ms`, default 30000, 1..2^31-1,
  RESTART_REQUIRED, read in `AwfSettings.readEarly`). Where it starts: `RegionShutdownThread` first
  waits up to 60 s for the region scheduler to halt, then calls `MinecraftServer.stopServer`, which
  disables plugins, waits for their async tasks and then, through MC feature patch 0081, calls
  `SourbyCraftBootstrap.close()`; there `AwfEngine.beginShutdown()` runs right after the SourbyMod
  shutdown (`ModLoader.shutdown()`). The budget therefore does **not** cover the scheduler halt,
  plugin disable or mod shutdown. It does start before the storage lane drains (the
  `ResourceGovernor` shutdown at the end of `close()`), before the region saves (whose last region
  per world runs `MoonriseRegionFileIO.flush`, then the AWF flush and close of that world) and
  before `AwfEngine.closeAll()` after the chunk I/O drain (MC 0019/0071). (Until PR #62 this
  paragraph said `beginShutdown()` was the first call of `close()`; the code comment that
  described it sat above the mod step and was moved above `beginShutdown()` in PR #62.) It
  creates one `AwfShutdownBudget` (monotonic `System.nanoTime` deadline) shared by everything after
  it, so the budget also covers the vanilla chunk save: a world whose save alone takes longer than
  the deadline gets its final commit refused (raise the key). What the deadline cuts is lock waits,
  the start of each commit attempt and Redis calls (lease release included). **A FILE commit that
  is already writing (its object, generation and pointer files and their fsyncs) is not
  interrupted** and can hold the stop past the deadline on a stalled disk; no thread is interrupted
  (`AwfShutdownBudget`, `AwfBackend.beginShutdown` javadoc). Running such commits on a daemon thread
  that the stop abandons at the deadline is not implemented. Since Build 50 (PR #62) the same key
  also bounds the chunk I/O drain of an `AuroraWorlds` save (see *Build 50 changes*). Mechanisms: the commit lock
  (`AwfWorld.commitLock`, now a `ReentrantLock`) and the storage's transition lock are taken with
  `tryLock(remaining)`; no commit attempt starts once the deadline has passed and an attempt cut
  by it is never retried; `RedisClient.deadline` cuts every call's pool wait, connect, write-progress
  and read timeouts to the time left, refuses calls after it without touching the network, and at
  the deadline closes every connection in use (and `close()` now also aborts checked-out
  connections, not only idle ones); at expiry the engine fences every world (queued lane commits
  commit nothing) and the Redis backend stops lease renewal; `RedisBackend.shutdown` releases all
  remaining leases in one pipeline; `WAITAOF`'s server-side timeout is cut too. Shutdown closes
  never throw for an unconfirmed commit any more: each reports to `AwfShutdownReport`, which logs
  one ERROR per storage and, from `closeAll`, one aggregate `AWF-SHUTDOWN-INCOMPLETE` line listing
  by storage the unconfirmed dirty positions (count + first 16), commits queued on the lane that the
  deadline fenced, refused writes (fenced reads + writes after close) and uncertain commits.
  Outcomes: CONFIRMED, FAILED (backend error before the deadline), REFUSED (no attempt could start:
  certainly not on the backend), UNCERTAIN (sent or running, unanswered: "may or may not have been
  applied", never "failed"). A clean shutdown logs `AWF-SHUTDOWN-OK`. MC 0071 turns a non-zero
  `closeAll()` into `abnormalExit` (exit status 70). Build 50 (PR #62; code written and statically
  reviewed, not built, not tested by the owner): stores prepared but never taken by a storage are
  closed at shutdown before the backend shuts down (releasing their Redis leases); a close that
  throws before it reported counts as FAILED (exit 70); a storage already reported (REFUSED) during
  the region saves is neither closed nor reported again; a Redis commit asked for after the deadline
  fails before anything is sent (REFUSED), while one cut inside its pipeline stays UNCERTAIN.
  Tests: `AwfShutdownDeadlineTest` (7: shared
  budget, a lock held by a hung commit cannot extend it, exact counts on expiry, sent vs never-sent,
  expired budget attempts nothing, confirmed shutdown keeps every commit, setting validation, real
  redis-server SIGSTOPped: 3 stores end at the 1500 ms deadline, ~1.7 s, where the old path would
  take 3 × 4 × 2 s), `RedisShutdownDeadlineTest` (4). Agent test server, jar from this tree
  (`opus-awf-d6-redis-1/-2`, loopback Redis 8.10.2 with AOF, default 30000 ms): markers placed and
  `save-all flush` confirmed; two more markers; Redis SIGSTOPped; `stop` exited after **31.1 s**
  with exit status 70 and the alert (overworld region UNCERTAIN, 763 dirty positions; overworld
  entities REFUSED, 3), against 91–115 s before. After SIGCONT and lease expiry the next boot
  read all four markers: Redis had applied the uncertain commit from the request already in its
  socket buffer, which is exactly why it is reported as uncertain and not as failed. A clean
  `stop` of that boot: `AWF-SHUTDOWN-OK ... 9 storage(s) ... 1468 ms`, exit 0, 0 lock keys.
  Functional evidence on a shared machine only; no player load, no networked Redis.
- **D4 — save-all durability.** `AwfRegionStorage.flushBarrier()` completes DURABLE once a commit
  whose snapshot was taken after the call succeeded (FAILED with the dirty count otherwise; never
  blocks). MC 0072 replaces `save-all`'s unconditional "Saved the game" with
  `command/SaveAllFeedback` → `AwfFlushReporting`: no AWF storage open → the vanilla success
  unchanged; plain `save-all` → vanilla success plus one line with the AWF chunks still in memory;
  `save-all flush` → a pending line first (it never contains "Saved the game"), then, on a virtual
  thread, wait (≤ 30 s) until the chunk I/O queue is idle (`RegionIoQueue`, so the save's writes
  reached AWF), one barrier per open storage, and the vanilla success plus "N storage(s) durable,
  M chunk(s) committed" only when every barrier succeeded; otherwise "Save NOT durable: ... could
  not commit K of N storage(s), C chunk(s) only in memory: <storage> (<dirty> dirty: <error>)".
  Later lines are delivered on the global tick (`RegionOwnership`). The WARN of the 2026-10-14
  mitigation stays. Tests: `AwfFlushReportingTest` (7). Agent test server: healthy Redis →
  pending, then "Saved the game" + "9 storage(s) durable, 72 chunk(s) committed" 2 s later; Redis
  SIGSTOPped → pending, then after 60 s (commit attempts × `timeout-ms`, behind the running
  periodic commit) "Save NOT durable: ... 2 of 9 storage(s), 17 chunk(s) only in memory ...", no
  success line.

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

Left open: ~~D6~~ and ~~D4~~ (fixed 2026-10-22, above; not yet over a networked Redis or under
player load); Moonrise's misleading "chunk data will be lost" line on a fenced read; a regenerated chunk is
playable in memory while Redis is down (writes to it are dropped at the store, so player edits to
such a chunk are lost by design); the load-path check is the `moonrise$readData` frame, so a
Moonrise read that is not a chunk load (`ChunkMap.read`, e.g. a plugin reading chunk NBT) and
succeeds while the regenerated chunk is still loaded would lift the fence early (not observed, not
tested); a Redis-over-network run has not exercised any of this, and CI runs only the unit tests
named above (against its own local `redis-server`), not these server scenarios.

Status 2026-10-25 (PR #62): the close-time line quoted in the C3 rows ("could not commit …
uncommitted chunks are lost", later "could not confirm the final commit … lost") now reads "could
not confirm the final commit of … on close; the chunks written since its last confirmed commit may
or may not have been applied" (`RegionFileStorageHooks.close`); the rows keep the text those runs
logged.

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
- Optional metadata keys: `properties` (WorldProperties as JSON, since 2026-10-07) and, since
  Build 50 (PR #62), `plugin-data`: the world's persistent data container as base64 of the
  gzip-compressed NBT compound. Only `/awf convert` writes `plugin-data` (from the Slime world's
  `BukkitValues`), and only when it fits one metadata value (65,535 base64 characters); a larger
  one is not written and is named in the conversion's WARN. `/awf import` of an `.awf` file
  (`AuroraWorlds.importWorld`) decodes it before creating anything (a malformed value fails the
  import) and merges it into the new world's container once the world is created. The format
  version stays 2 and older readers ignore the key. `/awf export` still does not write an existing
  world's PDC. Also since Build 50, the `.awf` and `AwfFile` index bounds are checked without
  overflow, so an entry with an offset near `Long.MAX_VALUE` is refused at open instead of
  allocating up to 2 GiB on read.

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
  Since Build 50 (PR #62) a world managed through the AuroraWorlds API matches by its dimension
  name only (`AwfRegionStorage.managedNames`), so in the 26.2 layout it claims only its own
  dimension folder, never every dimension of a level folder with the same name;
  `aurora.awf.worlds` and `aurora.awf.export` still match the dimension or the level folder.
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
  driver thread itself stalls; since Build 50 (PR #62) the metrics collector WARNs once when the
  driver has finished no pass for 30 s and logs an INFO when passes resume
  (`MetricsRuntime.watchAwfMaintenance`, checked once a second, stale collections included), but
  nothing watches the driver if the metrics collector itself failed to start. At shutdown, feature patch 0019 commits and
  closes every open storage in `MinecraftServer.stopServer`, right after
  `MoonriseRegionFileIO.flush(server)`, the point where every chunk save has reached its storage.
  This is needed because Folia's shutdown never calls `RegionFileStorage.flush()`/`close()`; CI
  run 441 found a stop that lost every chunk without it. `flush()` and `close()`, where the engine
  does call them, also commit synchronously on the calling thread. A flush arriving on a region thread only starts an async
  commit, because region threads must not block on disk. **A crash loses the writes since the
  last commit**; region files lose less. `AwfRegionStorageTest.writesSinceTheLastCommitAreLostOnACrash`
  pins this. `AwfAsyncSaveRestartTest` (DEV-G restart/crash test for the asynchronous save path)
  pins what a restart sees around a region-thread flush's lane commit: before the lane ran it,
  only the previous commit; after it, everything it carried; a crash at any single fsync inside
  it (13 crash points on the FILE backend, the `generations-root` directory force after the
  pointer rename being the commit point) a whole previous or whole new generation, no leftover
  `.tmp`, and the chunks still dirty for the next commit; and a `close` arriving while the lane
  commit is mid-fsync with a newer region-thread write racing it ends in the newest bytes with
  no generation rolled back (3 generations: flush, lane commit, close).
- **Memory.** `aurora.awf.resident-chunks` (default 1024) clean chunks per storage stay in memory
  after a commit. Dirty chunks are never dropped, so a storage can exceed the limit by what is
  unsaved.
- **Once attached, attached.** If a store exists for a folder, it is opened even when the world is
  no longer listed, with a warning. Writing to the region files underneath would be overridden by
  the older AWF data on a later run.
- **Export.** `aurora.awf.export: [world]` (and the world removed from `worlds`) writes every
  chunk and deletion in the store into the region files at the next load, through the storage's
  ordinary write path, flushes, then retires the store (FILE: renamed to
  `<folder>.awf.exported-<millis>`, kept, not deleted). A failure part-way fails the world load and
  leaves the store in place; the next load exports again from the start. A world in both lists
  stays in AWF.
- **Failure behavior.** A store that cannot be opened fails the world load rather than falling
  back to region files. A commit that fails after `aurora.awf.commit-attempts` leaves the chunks
  dirty and logs a warning; the next interval retries. A chunk over 64 MiB is refused as an I/O
  error. Since Build 50 (PR #62) cleanup that runs after a FILE commit point (pruning old
  generations, deleting unreferenced objects) no longer fails the commit: it logs a WARN at most
  once a minute per store and kind, and the next commit retries it, so a persistent cleanup failure
  shows as that WARN while disk use grows (no metric counts it). See *Build 50 changes* for the
  other store-integrity checks.
- **One writer per store.** Opening a folder that is already open throws. Two writers would each
  commit an index missing the other's chunks. Since Build 50 (PR #62) `open` and `prepareStores`
  reserve the folder before the backend opens anything, so a concurrent second open fails fast
  ("already being opened", or "being opened by another load" for a prepare) without touching the
  store (the `aurora.awf.export` branch of `open` runs before that reservation).
- **Commit cost.** Retained generations' object names are kept in memory. A commit only
  considers objects of the generation that fell out of retention, plus those a failed commit
  wrote, for deletion. The object directory is scanned once, when a writable store opens, for
  objects a crash left behind. By default (`aurora.awf.delta-generations: false`) a commit is still
  O(chunks in the storage): the whole chunk index is rewritten, hashed and forced. With the opt-in
  flag on, a commit writes a delta of the changed entries and compacts into a full index every 32
  hops or once replaying the chain would cost as much as the full index (AWF-7, see *Delta
  generations*). Either way the object forces are still O(objects) per commit. Unmeasured on large
  worlds. Every NBT write is serialized once more into a byte array.
- **Backends.** `AwfStore` is the backend contract: atomic commit, remembered deletions, caller
  owns arrays. `AwfBackend` selects where stores live: `aurora.awf.backend`, default `file`.
  SourbyCraft ships two backends: `file` (built in) and `redis` (`awf/redis/RedisBackend`, its own
  RESP client and no database driver, registered by the engine when `aurora.awf.backend: redis`;
  see *Redis backend* above). It ships no MongoDB or MySQL backend. Another backend is
  server-internal code registered with `AwfBackend.register` before listed worlds load. A listed
  or exporting world whose backend is not registered (including a Redis backend refused for
  invalid settings) fails to load; there is no fallback. Storage ids are region folders relative
  to the server directory (`world/region`). `READ_ONLY` opens must never create or delete
  anything; since Build 50 (PR #62) the FILE backend keeps that for `READ_ONLY` and `TEMPLATE`
  opens too (no recovery, temp cleanup or directory creation).
- **Observability.** `/perf awf` shows resident, dirty and evicted chunks, pending commits and the
  oldest one's age, commit p50/p95/p99, reads versus region-file fall-throughs and deletes,
  retries, failures and bytes written, per storage. Since Build 50 (PR #62) it also shows
  `AWF loaded worlds` (dimension folders with an open AWF storage), per storage `<storage> oldest
  dirty age` (milliseconds, truncated, since the oldest unsaved chunk became dirty, i.e. its first
  write since it was last clean; a chunk rewritten while a commit carried its previous bytes counts
  from that commit's snapshot; `no dirty chunk` when none is; `AwfMetrics.Snapshot.oldestDirtyMillis`),
  the read-failure row also when only refused writes are non-zero, and `AWF Redis leases` with the
  Redis backend. All LIVE (read at command time); the oldest-dirty walk is in memory over the dirty
  chunks only and has no unit test. While non-zero it also shows
  `Chunk-system read errors collapsed`, the number of repeat read failures whose chunk-system log lines
  `RepeatedReadFailureLogFilter` dropped since start (LIVE: read at command time, a count since JVM start).
- **Evidence.** Unit tests in `awf/`: `AwfRegionStorageTest`, `AwfSettingsTest`, `AwfBackendTest`,
  and the tombstone and garbage-collection tests in `AwfWorldStoreTest` and `AwfWorldTest`.
  `AwfQualificationTest` (below). CI step "Boot twice with Aurora World Fabric storing the world"
  boots a fresh world with `aurora.awf.worlds: ["world"]` (written to `aurora.yml` since Build 50,
  PR #62; `aurora.toml` before). It checks that a store and a committed generation
  exist and that no `.mca` file was written, then boots again from the store, then a third time
  with `export: ["world"]` and checks that region files appear and the store is retired. That
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
  `aClosedFileStoreRefusesCommits`. Redis lease release after close is not re-tested by these
  tests; it is covered by `AwfEngineStoreLifecycleTest.anUnpreparedStoreReleasesItsLease` (real
  `redis-server`: prepare takes the lease, unprepare releases it) and by the 2026-10-14
  backend-failure run (a clean stop left 0 lock keys, *Backend failure* above).
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
  is logged once per import or conversion as dropped, for example `environment`, `dragonBattle`
  and values that do not fit. **World PDC (2026-10-20, unit-tested only):** `importSlime` merges
  the file's `BukkitValues` compound into the new world's persistent data container
  (`SlimeImporter.restorePluginData`, existing keys kept) when `start` completes on the global
  tick, i.e. after `WorldLoadEvent` (a listener on that event does not see it yet). The container
  is the world's `paper/persistent_data_container.dat` (`dimensions/<dim>/data/`), which only that
  world's `saveLevelData` writes: at a clean stop and when the world is unloaded (the engine's
  world unload runs it after the chunks), never by autosave, `save-all` or `/awf save` (world PDC
  has no consistent concurrent snapshot; see the [save-path ledger](persistence-save-paths.md#2-per-kind-of-data)); this
  paragraph said "`level.dat` at the world's next save" until 2026-10-25. Since Build 50 (PR #62,
  code written and statically reviewed; not built; not tested by the owner) `convertSlime` writes
  it under the `.awf` metadata key `plugin-data` and `.awf` import restores it the same way (see
  *`.awf` world files*); `/awf export` of an existing world still does not carry the world's PDC. Tests:
  `SlimeImporterTest.theWorldsPluginDataIsRestoredIntoTheNewWorldsContainer`. Earlier tests: `SlimeImporterTest.worldPropertiesMapFromTypedValuesAndTheRestIsReportedDropped`,
  `theHeaderOnlyReaderSeesWhatAFullParseSees`, `slimeToAwfToImportKeepsTheProperties`. The service
  wiring (`AuroraWorldsService.importSlimeInternal`, and since PR #62 `importWorldInternal`) has no
  unit test because it needs a running server; the `plugin-data` round trip has no test either.
- **Staged creation (B80/A5, B85), 2026-10-13; committed as MC feature patch 0029 and Paper
  feature patch 0014 (both in `v26.2-r50`); LIVE for every AWF create/load/clone/import, no
  setting.** Evidence: the local runs at the end of this item; no CI server test covers it.
  `createAfter` composes on store preparation
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

### Region-file write concurrency review (DEV-G, 2026-10-20)

Source review of the write/commit paths in `AwfRegionStorage` and `AwfWorld` as of this date,
with the in-process tests that pin each finding. Unit-tested only; no server boot or soak is
attributed to it. No performance effect is claimed.

**The paths.** Region threads call `AwfRegionStorage.write` (read side of the transition lock,
`AwfWorld.write`: one `owned.compute` that installs the new dirty entry and adds its key to the
dirty index, since Build 50 (PR #62); at the time of this review it was `owned.put` then
`dirty.put(version)`, both `ConcurrentHashMap`). Commits
reach the store from three places: `maintain` (periodic, queued on the storage lane),
`flush` (synchronous `saveNow` off the region threads; on a region thread it only queues a
save through `maintain`) and `close` (synchronous). All three run `AwfWorld.commit` under
`commitLock`, which serialises them and orders them by snapshot.

**Findings.**

1. *Write vs commit.* A commit snapshots the dirty versions, then reads the bytes. A write
   racing it either lands its bytes before the read (the newer bytes are committed; the newer
   version keeps the chunk dirty, so it is committed again) or after (the older bytes are
   committed, the chunk stays dirty). Neither loses the write; the store never holds bytes older
   than a version it cleared. Pinned by `AwfConcurrentFlushTest` (8 region-named threads, 40
   rounds, a store that rejects any commit of older bytes over newer and any two commits inside
   it at once) and `AwfCommitOrderTest` (the 2026-10-07 ordering fix). *Build 50 (PR #62):* a
   commit now takes whole entries (bytes and version together) from the dirty index and, after
   the store accepted it, cleans only the exact entries it carried; a write that replaced an entry
   meanwhile stays dirty. The same structure closes a race this review did not cover: resident-limit
   eviction after a commit could drop a concurrent write that the next commit then cleared (PM-023);
   eviction now removes only the exact clean entry it saw. Code written and statically reviewed;
   not built; not tested by the owner; no new test pins the eviction case.
2. *Many regions flushing at once.* Each region-thread `flush` resets the commit clock and calls
   `maintain`, which starts at most one save unless the "no save pending" check races the
   `pendingSaves.offer` of another region's flush. Then a second save queues behind the first
   and finds nothing dirty. Before this review it still called the store with empty maps: on
   FILE that is a new (empty) generation plus the fsync of its commit, which also consumes one
   of the `retained-generations` slots; on Redis a round trip. `AwfWorld.commitInOrder` now
   returns without touching the store when an INCREMENTAL snapshot has nothing to commit
   (`CommitResult.generation() == -1`); FULL and CHECKPOINT still run, since they rewrite or
   verify what the store already holds. Pinned by
   `AwfConcurrentFlushTest.anIncrementalCommitWithNothingDirtySkipsTheStore` and the
   zero-empty-commit assertion of `flushesFromEveryRegionAtOnceLoseNoWriteAndNeverRegressTheStore`.
3. *Foreground flush durability.* A `flush` off the region threads returns only after
   `saveNow` committed a snapshot taken after every write that preceded the call (the write's
   single `owned.compute`, which installs the dirty entry and adds its key to the dirty index,
   happens-before the snapshot walk; at the time of this review it was the write's `dirty.put`
   before the snapshot copy). Pinned by
   `AwfConcurrentFlushTest.aForegroundFlushReturnsOnlyOnceEveryEarlierWriteIsInTheStore`,
   which flushes from the test thread while region threads write and flush.
4. *Bounded queueing.* `MAX_PENDING_SAVES` (16) refuses further saves; `maintain` logs that
   refusal as a failed commit (`lastCommitFailed`). With the pending check in `maintain` the
   duplicate from finding 2 is at most one extra save per racing region, so a world cannot reach
   the cap through region flushes alone. Not tested beyond the pending-count assertions above
   (still true after Build 50: PR #62 added no test, PM-293 is open).

**Open.** The region-thread `flush` leaves the commit clock at 0 after its `maintain`, so the
next periodic `maintain` commits as soon as anything is dirty instead of waiting a full
interval (one extra commit after a `save-all`, by design: the flush asked for durability).
A `close` racing a region-thread flush is covered by the lifecycle fence tests, not here.

### Delta generations (AWF-7, 2026-10-20) — OPT-IN

**Status.** Implemented and unit-tested; off by default; not booted with the flag on; no wall-time
gain measured locally.

- **Switch.** `aurora.awf.delta-generations` (YAML boolean, default `false`, RESTART_REQUIRED, read
  early like the other `aurora.awf.*` keys; anything that is not a boolean is reported as an invalid
  key and reads as `false`). Affects the `file` backend only.
- **Flag off (default).** Every commit writes a full `index` blob under a format-1 manifest,
  byte-identical to the writer of `12b1232c` (pinned by
  `AwfDeltaGenerationTest.withDeltasOffEveryCommitIsAFullIndexInAManifestThePreviousBuildParses`,
  which also runs the previous build's parser rules). Retention and object GC are exactly as before.
- **Flag on.** A commit writes a `delta` blob (`x z <object>` new object, `x z -` tombstone that
  shadows the region file, `x z drop` the chunk leaves the index) chained to the current generation
  by `@base <M> <sha256 of M's manifest>`. The first commit, FULL mode, a chain already 32 hops long,
  or a chain whose replay would reach the full index size writes a full index instead. Open cost is
  at most one full index plus one index worth of delta entries. Format-1 stores open unchanged; the
  migration is lazy.
- **Retention.** `retained-generations` still means the newest R committed generations, each fully
  replayable. A chain-only ancestor stays on disk while a retained generation is chained to it and
  is pruned after the commit that drops the last such child. Object liveness is the union of the
  replayed states of the retained generations.
- **Point-in-time rollback (2026-10-20, FILE backend, Java API only, unit-tested).**
  `AwfWorldStore.rollbackPoints()` lists the retained generations that replay (newest first);
  `rollbackTo(n)` reads back every object generation n references, then commits n's state as a new
  full-index generation through the normal commit point. Nothing is rewritten, so the generations
  after n stay retained (a rollback can be undone) until retention drops them; a missing or damaged
  object fails it before the commit point. Only for a store no loaded world uses (a loaded world
  would commit its dirty chunks over it). No `/awf` command, no Redis implementation, no world-level
  coordination of the region/entities/poi stores yet (`AwfRollbackTest` 6).
- **Crash consistency.** Unchanged commit order (objects forced, then generation, then `CURRENT`
  as the single commit point); a base is read and hash-linked before anything is written.
  Proved in-process with the recording `Durability` seam (`AwfDeltaGenerationTest` 15,
  `AwfDurabilityTest` 14), which proves order and recovery logic, not what a disk kept.
- **Measured (uncertified local A/B, `AwfBulkWriteBenchmarkTest`, Apple M2, APFS on a USB SSD,
  3,364 synthetic chunks, 336 changed per commit, 5 runs, wall ms min/median).** Full-index commit
  1,618 / 1,649; delta commit 1,665 / 1,688 (second sample 1,567 / 1,650); no wall-time win, because
  the 336 object forces dominate. What shrank is the generation step: 238 KB → 23 KB per commit
  here, and in an isolated 100,000-chunk probe 7.2 MB → 0.7 MB (50.3 → 31.5 ms for the step alone).
  The force count did not change. Not a release claim.
- **Why opt-in.** A format-2 manifest is unreadable by builds before AWF-7 (the previous parser
  rejects a manifest line that is not exactly three tab-separated fields), so deltas break the
  SEC-3 rollback to an older jar, and the measured benefit is index bytes, not wall time.
- **Downgrade (with deltas on).** A pre-AWF-7 build skips the retained delta generations as
  damaged, and its writable open deletes the objects only those generations reference,
  irreversibly. Sequence: (1) back up every `<folder>.awf` directory if the retained delta rollback
  points matter; (2) set `aurora.awf.delta-generations = false` and restart on the current build —
  every store the server opens is compacted into a full index at open — or, without a restart, run
  `/awf compact <world|all>` (worlds must be loaded); (3) check that `/awf compact all` prints
  `full: yes` for every store; (4) only then start the older jar. The delta-only history older
  than the compacted generation is lost at that point (the current state is intact). A store whose
  world the server never opens keeps its chain until it loads on this build.
- **Tests.** `AwfDeltaGenerationTest` 15 (including the loss model
  `aPreAwf7WritableOpenDeletesTheObjectsOnlyRetainedDeltasReferenceIrreversibly`, which copies the
  previous build's rules into `HeadBuildReader`), `AwfCompactionLifecycleTest` 2, `AwfSettingsTest`
  9, `AwfCommandTest` 7, `AwfDurabilityTest` 14 (incl. an atomic-rename refusal); 263 `awf` tests
  with only the 4 known suite initializationErrors. A Codex review found the default-off path clean
  and two P2 problems on the flag-on rollback path, both fixed by the compaction at open and
  `/awf compact`. Not done: a real pre-AWF-7 jar (its rules are modelled in the test), a rewrite of
  retained deltas as full indexes (not crash-safe to do in place), conversion of stores never
  opened.

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
  the server as before), `SUCCESS` otherwise (`WorldUnloadCompletionTest`). *Since then:* PR #61
  made the unload fail when an AWF storage of the world did not confirm its final commit
  (`WorldUnloadDrain.runWithAwfConfirmation`); PR #62 (code written and statically reviewed; not
  built; not tested by the owner) throws that case as `WorldUnloadDrain.AwfUnloadNotConfirmed`,
  which `WorldUnloadCompletion` answers with `FAIL_UNKNOWN` (API `UnloadResult.FAILED`) and one
  ERROR without rethrowing, so the server keeps running; the world is already unloaded at that
  point, and `isLoaded` tells this case from an unload that did not happen. Every other failure
  is still rethrown. A distinct "unloaded but not durable" result value is an API change left to
  the owner. Neither PR is in a published build. The world operation
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
- **AWF-7 (2026-10-20):** delta generations are implemented but OPT-IN, see *Delta generations*
  below. Not done: a boot with the flag on, `/awf compact` on a live server, a wall-time gain
  (none measured locally).
- **Local boot, 2026-10-14** (slim jar from this tree, fresh flat world, offline, no players): six
  `save-all`/`save-all flush` in a row, `awf create t1 normal 5`, `awf save t1` ("Saved t1
  (committed)"), `save-all flush`, `awf unload t1` ("Unloaded t1"), `stop` → exit 0, no exception.
  With no players no region held chunks, so the save tickets fanned out to zero regions ("No regions
  to save"): the boot shows nothing broke, not that the AWF-2 race is gone in a live server; that
  rests on `TicketHolderTest`.

### Build 50 changes (PR #62, 2026-10-25)

**Status: code written and statically reviewed; not built; not tested by the owner.** PR #62 added
no tests (owner rule for that session); a few existing assertions were edited where they encoded
behavior the PR changed on purpose. None of this is in a published build: the `v26.2-r50`
prerelease tag predates PR #61 and PR #62, and how it ships (a later tag or Build 51) is owner
decision PM-011. No AWF storage key (`aurora.awf.*` outside `aurora.awf.multiprofile.*` and
`aurora.awf.redis.fabric.*`, which have their own documents; PR #62 added
`aurora.awf.multiprofile.repository` and `aurora.awf.multiprofile.redis.*` there) was added, renamed
or re-defaulted, and every one stays RESTART_REQUIRED; two changed meaning:
`aurora.awf.shutdown-deadline-ms` now also bounds the chunk I/O drain of an `AuroraWorlds` save, and an unusable `aurora.awf.redis.uri` (or
`SOURBYCRAFT_AWF_REDIS_URI`) is refused instead of falling back to the default server. The
registry gains an optional `knownStores` array (older builds' Gson ignores it). Per-item status and owner scenarios:
[BUILD-50-PM-MATRIX.md](../releases/BUILD-50-PM-MATRIX.md), [Tester Owner](../tester%20owner.md).
The sections above link here where their statement changed.

**Store integrity.**
- *Eviction race (PM-023).* `AwfWorld` keeps each chunk as one immutable entry (bytes, write
  version, first-dirty time) in one map; the dirty-key index changes only inside that key's
  `compute`. A write installs its dirty entry in one step, eviction removes only the exact clean
  entry it saw, and a commit cleans only the entries it carried. Before, a write racing the
  post-commit resident-limit eviction could be dropped and then cleared by the next commit while a
  flush reported DURABLE. In memory only; no on-disk change.
- *Cleanup after the commit point (PM-070).* Pruning unretained generations (`GenerationStore`)
  and deleting unreferenced objects (`AwfWorldStore`) run past the commit point and no longer fail
  a commit that is already durable (which made callers retry and commit yet another generation).
  A failure logs "AWF store … committed generation N, but …" with its stack at most once a minute
  per store and kind, counting the failures in between; the next commit retries. A persistent
  failure grows disk use; no metric counts it.
- *Object names (PM-071).* Index and delta entries must name lower-case 64-hex objects
  (`ObjectStore.NAME`); anything else, such as a path, makes the generation a
  `CorruptGenerationException` ("malformed index line") at open, and `ObjectStore` refuses to
  resolve any other name, so a copied or hand-edited store cannot name a file outside `objects/`.
  No SourbyCraft writer produced other names.
- *Vanished object temp (PM-207).* A pending object whose temporary file was removed outside the
  server fails one commit attempt ("… disappeared …") and is rewritten by the next, instead of
  failing every later commit until restart. A temp that vanished from an earlier failed batch can
  fail the next, unrelated commit once.
- *Read-only opens (PM-208).* `READ_ONLY` and `TEMPLATE` FILE opens (exports, template reads,
  the template stores of instances) no longer run recovery, temp cleanup or directory creation, so
  they cannot delete a live writer's pending files; the next writable open cleans leftovers.
- *Bounds (PM-206).* `AwfWorldFile` and `AwfFile` index bounds are overflow-safe (see *`.awf`
  world files*).

**Unload and save durability (PM-001, PM-089, PM-211).**
- An unload whose final AWF commit is not confirmed completes with `FAIL_UNKNOWN` and an ERROR and
  no longer stops the server (see AWF-8 above). `/awf unload` then prints "Unloaded <name>, but AWF
  could not confirm its last commit (see the server log)".
- `AuroraWorlds.unload(name, true)` and `/awf unload` of a registry world first run a durable save;
  when it fails the future fails ("was not unloaded … stays loaded") and the world stays loaded.
  Unloads are one save slower. A stopping server skips that save. An unload can still report
  `FAILED` for a world it did unload, when a write between that save and the engine's final close
  is not confirmed.
- `AuroraWorlds.save` / `/awf save` fail when a commit is cut by the deadline, fenced, closed or
  discarding (`AwfRegionStorage.flushConfirmed`), or when the world's chunk I/O does not drain
  within `aurora.awf.shutdown-deadline-ms` ("did not drain within N ms … not durable"). A drain
  that timed out cannot be interrupted: it keeps one `VirtualExecutor` thread until the world's I/O
  queue empties, a later save of that world waits behind it, and it can add up to 30 s to
  `VirtualExecutor.shutdown` at stop. The region-ticket barrier before the drain is not bounded.

**Registry, templates and engine lifecycle.**
- Registry durability, published snapshot, `knownStores`, refused vanilla/level-name world names
  and dimension-name matching: see *Registry* and *What goes through AWF* above (PM-024, PM-073,
  PM-074). Templates: forced writes and refused missing templates (PM-072), see *Templates* and
  *Instances* above.
- Opens reserve their folder before the backend opens anything (PM-225), see *One writer per
  store*.
- A failed creation and `delete` discard and close the storages still open under the world's
  folder before deleting its stores; if a close fails, or another thread (an abandoning unload) is
  still closing one, nothing is deleted and the registry entry stays, and a later `delete` retries
  (PM-209).
- A region storage's read fence also fences the POI storage of its dimension (PM-210), see D1.
- Shutdown reporting: prepared stores closed, a throwing close counts as FAILED, each storage
  reported once (PM-226), see D6.
- The metrics collector WARNs when the AWF maintenance thread has finished no pass for 30 s
  (PM-223), see *Durability*.

**Spawn tolerance (PM-290).** `WorldProperties.Spawn` refuses a spawn that is not finite with
|x| and |z| ≤ 29,999,984, y in [-2032, 2032) and a finite yaw. A registry entry or `template.json`
written before that holding such a spawn is not refused as a whole: the spawn is dropped with a
WARN naming the world or template on every read (twice at boot for the registry: the engine and
the world service each read it) and is no longer applied at load. The registry's next write leaves
it out; `template.json` is never rewritten, so a template's WARN repeats on every read and worlds
made from it get no spawn property. Earlier loads may already have written that spawn into the world's own level data, so set a new
one (`/awf set <world> spawn …`). Other refused properties (save bounds with min above max) fail
the file with an `IOException` naming it. A Slime import with such a spawn imports without it and
lists it among the dropped keys. An `.awf` file exported before Build 50 with such a spawn imports
without it, with one WARN naming the file and the `/awf set` hint (`AuroraWorldFiles.properties`;
until 2026-10-25 it failed the whole import as "the file's properties are not valid"); a spawn
that is not numbers, and other refused properties, still fail the import.

**Redis backend.** `WAITAOF` pipelined behind the commit on the same connection, the node identity
and lease takeover rule, one-script `delete`/`retire`, the late-renewal WARN and the `AWF Redis
leases` row, RESP reply bounds, and the refusal of an unusable URI: see *Redis backend* above
(PM-025, PM-026, PM-027, PM-212, PM-213, PM-262). Not done: a pipelined neighbour prefetch for chunk
reads (PM-454; each read is still one round trip).

**`.awf` and Slime.** `plugin-data` on `/awf convert` and `.awf` import (PM-252), see *`.awf` world
files*; unknown v13 chunk flags named in the import WARN (PM-258), see *Slime import*.

**Observability.** `AWF loaded worlds`, per-storage `oldest dirty age`, refused writes and `AWF
Redis leases` in `/perf awf` (PM-260, PM-453), see *Observability*. Whether the oldest dirty age
moves with the opt-in byte trigger (`aurora.awf.commit-dirty-bytes`) is not measured.

**Not done in PR #62.** New tests for all of the above (eviction stress, cleanup failures,
vanished temp, hostile object names, offset overflow, read-only leftovers, plugin-data round trip,
unknown Slime flag, `MAX_PENDING_SAVES` refusal (PM-293), oldest dirty age, node identity,
RESP caps); an `UnloadResult` value for "unloaded, not durable"; an asynchronous
`AuroraWorlds.setAutoload`; FILE commits that the shutdown deadline can abandon; the Redis read
prefetch; `/awf export` of a world's PDC.

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
finding in docs/TODO.md); it fails the same way on region files.

Still open: backend timeout/disconnect is covered only by the local Redis backend-failure runs
(*Backend failure* above and [awf-backend-failure-2026-10-22.md](../development/awf-backend-failure-2026-10-22.md):
loopback Redis and a userspace-proxy approximation, no players); Redis on a separate host over a
real network has not been run. Any measurement under sustained real chunk load is open too.

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
