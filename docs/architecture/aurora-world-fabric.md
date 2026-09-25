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

## Qualification
Load/unload loops, COW isolation, crash during each persistence stage, backend timeout/disconnect, shutdown with pending saves, corrupt cache/blob recovery, and 1/50/250/1000-world workloads.
