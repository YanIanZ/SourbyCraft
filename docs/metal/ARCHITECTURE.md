# Aurora Metal Runtime — Technical Architecture Proposal

**Status:** planned evolution of Metal/LeafPile integration, not new runtime code. "Aurora Metal Runtime" (AMR) is a working name for SourbyCraft's internal concurrency adoption program, **not** a replacement for JVM, Paper, Folia, Moonrise or AWF.

## Layering

~~~mermaid
flowchart TD
  MC["Minecraft + Paper / Folia runtime"]
  API["SourbyAPI and plugin contracts"]
  AUR["Aurora Engine services and owner schedulers"]
  AMR["Aurora Metal Runtime adapters"]
  UTIL["LeafPile / vendored Metal utilities"]
  GOV["Aurora Resource Governor"]
  AWF["Aurora World Fabric"]
  ARF["Territory Control Plane (future)"]
  MC --> AUR
  API --> AUR
  AUR --> AMR
  AMR --> UTIL
  AUR --> GOV
  AUR --> AWF
  ARF -.-> AWF
~~~

Metal classes are allowed **under** owner-safe services, not a new global plugin access escape hatch. Public plugin API should not expose Metal implementation classes unless a compatibility and ABI policy is approved.

## Regions and memory ownership

- **Folia region thread** exclusively owns mutable region/world/entity state. Metal concurrent maps permit safe *map operations* across threads; they do **not** make referenced Minecraft objects, iterators, entity lists or paired world structures safe to mutate off-owner.
- **Global region** owns global state per Folia rules. Region I/O worker threads process immutable copies or strictly guarded data; they never call Bukkit world mutation directly.
- **Cross-JVM boundary** never uses Metal locks/maps as durable authority. AWF/ARF must still fence writes against authoritative storage epoch/revision.
- Never introduce blocking futures on the owning region/entity/global tick thread or Netty. Use async handoff and immutable snapshot delivery with stale-result validation.

## Integration zones and priorities

| Priority | Zone | Candidate Metal utility impact | Risk |
|---|---|---|---|
| P0 | Chunk indexes/holder lookup | specialized concurrent maps, lower allocation and contention | Very high; wrong maps corrupt chunk lifecycle |
| P0 | Region task queues/dispatch | bounded MPSC/MPMC queues and cancellation semantics | Very high; lost tasks freeze or duplicate ticks |
| P1 | Entity tracking/view caches | read-heavy map layout and snapshot publication | High; stale entities or client desync |
| P1 | AWF snapshot staging | bounded work queues, immutable buffers, backpressure | High; lost durable writes/data corruption |
| P1 | MultiProfile IO | per-account ordered fair dispatch | High; incorrect reordering creates dupes |
| P2 | Proxy/packet buffer housekeeping | allocator metrics, avoid ownership confusion | Medium/high; plugin/network compatibility |
| P2 | Telemetry and sampling | low-overhead counters, batched extraction | Medium; measurement overhead |
| R&D | Territory mirrors | immutable cross-server delta caches, version checking | Very high; Metal does not solve distributed consistency |

## Data-structure selection policy

Before replacing `ConcurrentHashMap`, `ConcurrentChained...HashTable` or a Folia-specific collection, record: number of read/write threads, mutation/iteration semantics, visibility guarantees, memory layout, contention percentiles, GC effects, key distribution, expected cardinality, and whether reads can race with remapping/resizing. A faster microbenchmark with unsafe semantics is a rejection, not a win.

Choose specialized `SWMR` only if **exactly one serialized writer** is enforced by the owning engine context. Multi-writer maps/queues require explicit happens-before and cancellation behavior. Reentrant compute callbacks and lock-order cycles must be reviewed. Avoid introducing a global lock that destroys Folia parallelism.

## Adaptive work scheduling policy

"Adaptive" refers to resource-aware admission/budgets inside existing Aurora Resource Governor or later evidence-backed changes. It does not mean silently replacing Folia's scheduler. The [current dependency ledger](../architecture/dependency-ledger.md) records an experimental EDF scheduler as parked/unselected. Reactivating it requires source acceptance, patch review, cancellation/visibility correctness, fairness and owner stress evidence.

Control decisions must have limits and hysteresis: queue capacity, backpressure, thermal/memory and CPU saturation budget, bounded retry, fail-closed for persistence and world authority. Do not reconfigure the JVM tick thread count every tick. JFR/Spark per-region metrics should be sampled with explicit overhead budget.

## Packaging/provenance

Only one provider of `ca.spottedleaf.*` overlapping classes is allowed in candidate runtime where duplicate class definitions can conflict. Verify shaded dependencies, Paper's transitive dependencies, serialized class descriptors, class loader locations and service resources. GPL-3.0 attribution/notice must remain. Source-owned forks require a per-class diff ledger vs pinned upstream and documented reasons, not a blind copy of new code.

No automatic overwrite of vendored source from network at build or startup. Pinned versions/commits, deterministic generation and source/JAR checksums are release inputs.

## Non-goals

- No promise of 2× TPS/zero MSPT before paired benchmarks.
- No global synchronization around all regions or "thread safety" via unchecked locking.
- No GPU processing; no cross-server consistency from in-JVM data structures.
- No automatic change to authenticated UUID, Virtual GameProfile, island/world durability or plugin permission semantics.
- No unreviewed autoupdate of upstream dependencies.

## Failure conditions

Reject or disable experimental provider when: missing module, duplicate class detected, unexpected ABI, generated source mismatch, failed concurrency regression, poor p99 region time, excessive alloc/GC, chunk corruption, unexplained world-state lag, deadlocks, lost tasks, or any owner P0 failure.
