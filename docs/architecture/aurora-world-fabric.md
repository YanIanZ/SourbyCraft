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

`dev.iyanz.sourbycraft.awf` is a tested library. **The engine's chunk load/save path does not use
it, so no world runs on AWF.** Wiring it in means patching chunk I/O in the engine, which has not
been done.

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
  and written, and materialised chunks. `AwfRegistry` counts loaded worlds.
- `WorldRole`: the six roles and their write rules.

Not implemented: MongoDB/MySQL/Redis backends; a SlimeLoader compatibility adapter; engine
integration; any of the load/unload or multi-world qualification workloads.

## Qualification
Load/unload loops, COW isolation, crash during each persistence stage, backend timeout/disconnect, shutdown with pending saves, corrupt cache/blob recovery, and 1/50/250/1000-world workloads.
