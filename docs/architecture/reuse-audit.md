# Object-reuse, `ThreadLocal`, pool and entity-scratch audit

Closes the inspection half of `docs/DEVELOPMENT-TASKS.md` section A items
"audit remaining reusable mutable state / scratch buffers", "audit all `ThreadLocal`
usage relevant to Sourby performance patches", "audit remaining object pools" and
"validate entity-owned scratch reentrancy assumptions", and feeds section I
("cache ownership/bounds inventory"). Section E items that touch shared mutable
solver state are covered only where the state itself is in scope; the async
pathfinding correctness audit remains open.

[threading.md](threading.md) already reviewed the six scratch-reuse patches
0009/0012–0016 and recorded the removal of 0013 and 0015 and the effect-particle
publication fix in 0014. This audit does not repeat that work. It covers what
that review explicitly deferred — pools, `ThreadLocal`s and "other reused state" —
plus two things that review did not reach: patch 0017's entity-owned
`MutableBlockPos`, and the lifetime (as opposed to the confinement) of the
buffers it cleared.

**Method.** Sourby-owned source under
`sourbycraft-server/src/main/java/dev/iyanz/sourbycraft` and the downstream patch
directories (`sourbycraft-server/minecraft-patches`, `canvas-patches`,
`paper-patches`) were read directly. Generated sources under
`sourbycraft-server/src/minecraft/java` were read **only to confirm call paths and
escape points**, never as the source of record. No build was run and no production
code was changed.

---

## 1. Headline

* **There is no mutable `ThreadLocal` storage anywhere in Sourby-owned code.** The
  only `ThreadLocal`-family use is `ThreadLocalRandom`, which is not reusable
  mutable storage at all. Section A's `ThreadLocal` item is satisfied by absence,
  not by review — see §3.
* **There is no object pool in Sourby-owned code.** The two things named "pool" are
  a thread pool and a histogram *accumulator*, neither of which recycles objects.
  See §4.
* **Four lifetime/cleanup findings were identified**, all of them lifetime rather than
  confinement problems, and none of them a cross-region race. Three are
  entity-scratch buffers that are cleared on *entry* to their next use and therefore
  retain their last contents indefinitely when that next use does not come; one is a
  latch in the async-pathfinding patch that is never reset on a retired entity
  scheduler. See §7.
* **Reentrancy is still not proven for any entity-owned scratch**, exactly as
  threading.md said. What this audit adds is the reachability set for each one
  (§6), which narrows the claim from "no path found in the codebase" to "the buffer
  has N call sites, here they are" — and notes possible reentrant invalidation. Exception detection is not guaranteed; ownership alone does not prove reentrancy safety.

---

## 2. Inventory — Sourby-owned reusable mutable state

Inventory of the reusable state inspected in `dev.iyanz.sourbycraft`; this is not a proof that every mutable field in the repository is covered. "Lifetime" is how long the state survives;
"Owner" is the thread or monitor that may mutate it.

| # | State | Location | Owner / lifetime | Verdict |
|---|---|---|---|---|
| S1 | `long[] pooledHistograms` (5 windows × 64 bins) | `perf/PerformanceCollector.java:50` | Collector daemon `SourbyCraft-PerformanceCollector`; zeroed every cycle at `:184`; process lifetime | **KEEP** |
| S2 | `float[] medianBuffer` + `int medianCapacity` | `perf/PerformanceCollector.java:55-56` | Same daemon; grown in `ensureMedianCapacity` `:216-233`; never shrinks | **KEEP** (see F2, F5) |
| S3 | `WindowAccumulator[] accumulators` and their scalar fields | `perf/PerformanceCollector.java:49`, `:317-451` | Same daemon; `reset()` per cycle `:341-359` | **KEEP** |
| S4 | `long[] histogramScratch` (64 bins) | `perf/RegionTickMetrics.java:60` | The `RegionTickMetrics` instance monitor; refilled at `:478-479` and `:513-514` | **KEEP** |
| S5 | Raw sample ring: `rawStarts/rawEnds/rawIntervals/rawExecutions/rawMissingCpu/rawTargets/rawOverflow`, capacity 384 | `perf/RegionTickMetrics.java:40-48` | Instance monitor; fixed size, wraps with `lastDroppedEnd` truncation marker | **KEEP** |
| S6 | Unresolved-interval ring, capacity 8 | `perf/RegionTickMetrics.java:50-53` | Instance monitor; fixed size | **KEEP** |
| S7 | `BucketStore seconds` (61 slots) / `fiveSeconds` (169 slots) and their 12 parallel arrays | `perf/RegionTickMetrics.java:58-59`, `:736-923` | Instance monitor; slot-recycling ring, `prepare()` `:904-922` resets a slot on epoch change | **KEEP** |
| S8 | `acquired` / `acquiredState` snapshot memo | `perf/RegionTickMetrics.java:69-70`, `:213-226` | Instance monitor; holds an immutable `Snapshot` record | **KEEP** |
| S9 | `ConcurrentHashMap<Long, Generation> generations` | `perf/RegionMetricsRegistry.java:21` | Process-wide; entries expire 15 min + 1 s after retirement (`:124-132`), swept during `forEachUnexpired` `:111-122` | **KEEP** |
| S10 | `RegionTickMetrics current` cell | `perf/RegionTickMetricsHolder.java:23`, replaced at `:60` | One per region tick handle; replaced only after retirement | **KEEP** |
| S11 | GC ring buffers `ringCount` / `ringTimeMs` / `ringNanos` (21 slots, `static`) | `perf/GcTracker.java:21-23` | `GcTracker.class` monitor (`sampleOnce` `:72`, `stop` `:57`); reset by `stop()` | **KEEP** |
| S12 | Three shared `BossBar` instances | `hud/HudBars.java:47-52` | Mutated by the global-region updater task `:170-184`; shown/hidden from player region threads `:106-109`, `:122-123` | **KEEP** (see U3) |
| S13 | `TPS_VIEWERS` / `RAM_VIEWERS` / `PERF_VIEWERS` / `ANY` | `hud/HudBars.java:42-45` | Process-wide; hold `UUID`s only, evicted in `onQuit` `:143-150` and cleared in `close()` `:160-168` | **KEEP** — satisfies the "no player reference leak after disconnect" rule in `DEVELOPMENT.md` §14 |
| S14 | `Map<String,String> cache` (GeoIP) | `util/GeoUtil.java:53` | Process-wide; hard cap 1024 with half-eviction `:115-123` | **KEEP** (see F4) |
| S15 | `List<Entry> ENTRIES` (plugin-load failures) | `brand/PluginLoadDiagnostics.java:30` | JUL handler thread; capped at 16 `:63-66` | **KEEP** (see F3) |
| S16 | `Set<String> WARNED_KEYS` | `SourbyCraftConfig.java:281` | Process-wide; bounded by the config key space | **KEEP** |
| S17 | `Map<String,Command> OURS` (plain `LinkedHashMap`) | `command/SourbyCraftCommands.java:29` | Only ever touched inside `synchronized registerAll()` `:36-81`; no other reader | **KEEP** |
| S18 | `volatile long cached` (cgroup limit memo) | `util/ContainerMemory.java:19` | Process-wide; idempotent detect, benign race | **KEEP** |
| S19 | `MetricWindow[] WINDOWS = MetricWindow.values()` | `perf/PerformanceCollector.java:29` | Static array from `values()`; private, never handed out, never written | **KEEP** |
| S20 | `volatile PerformanceSnapshot snapshot` | `perf/SourbyMetricsProvider.java:10` | Single immutable reference swapped by the collector | **KEEP** — this is the publication model, not reuse |

No production change is proposed for this table in this batch. Fixed rings have explicit bounds; registry entries depend on expiry, median storage on peak region count, and shared cache bounds need separate concurrency validation. The listed ownership claims must be read alongside the unresolved HUD thread-safety question U3. S1–S8 are the telemetry hot path and are the only ones where reuse is
load-bearing; S9–S19 are bookkeeping where reuse is incidental.

### Deliberate non-reuse worth recording

`PerformanceCollector` allocates a fresh `ImmutableWindowMetrics[]` pair and a fresh
`ImmutablePerformanceSnapshot` every second (`:122-148`). That is ten small records
per second for the whole process and it is what makes the "one immutable snapshot
per refresh" contract in `DEVELOPMENT.md` §12 hold. It should stay allocated, not
pooled.

---

## 3. `ThreadLocal` — the distinction the task matrix asks for

`ThreadLocalRandom` and mutable `ThreadLocal` storage share a prefix and nothing
else, and only the second is in scope for an object-reuse audit.

**`ThreadLocalRandom` — present, correct, not reusable mutable storage.**

* `lang/SourbyMessages.java:11`, `:27`, `:178-179` — `ThreadLocalRandom.current().nextInt(list.size())`
* `lang/SourbyJoinLeaveListener.java:25` — documents the above

`ThreadLocalRandom.current()` returns a per-thread PRNG whose state is an
implementation detail of the JDK, never handed to the caller, never cleared, never
reset, and never a lifetime or escape hazard. It is the *correct* replacement for a
shared `Random`, which would be a contended mutable object. No action.

**Mutable `ThreadLocal` storage — none.** There is no `new ThreadLocal<>(...)`,
`ThreadLocal.withInitial(...)`, or `extends ThreadLocal` anywhere under
`dev.iyanz.sourbycraft`, and no `.patch` file in `minecraft-patches`,
`canvas-patches` or `paper-patches` introduces one. The section A item
"audit all `ThreadLocal` usage relevant to Sourby performance patches" is therefore
closed with an empty result set.

**The one thread-confined lookup that is *not* a `ThreadLocal` but behaves like
one, and that Sourby code depends on.** `ServerLevel#getCurrentWorldData()` resolves
region-local state through `TickRegionScheduler`, and returns `null` off a region
tick thread. Patch 0007 exists precisely because
`PathfindingContext.<init>` dereferenced it on an `AsyncPath` worker and NPE'd
(`minecraft-patches/features/0007-...patch:10-20`). Any future Sourby code that
reads region-local state must assume it can be absent, not that it is a
`ThreadLocal` with an initial value. This is the single most likely place for the
next defect of this class.

---

## 4. Object pools

**There is no object pool in Sourby-owned code.** Three things could be mistaken
for one:

| Candidate | Location | What it actually is | Verdict |
|---|---|---|---|
| `AsyncPathProcessor.pool` | `perf/AsyncPathProcessor.java:34`, `:49-73` | A bounded platform-thread `ThreadPoolExecutor` (cores/4, min 1), queue capacity 1024, caller-runs rejection `:66-69`, `allowCoreThreadTimeOut(true)` `:70`. A *thread* pool. No object recycling. | **KEEP** |
| `BoundedIoExecutor` | `util/BoundedIoExecutor.java:12-20` | Thread-per-task virtual threads with a 64-permit `Semaphore` admission bound and no queue. Explicitly *not* a pool — a new virtual thread per task is the point. | **KEEP** |
| `PerformanceCollector.pooledHistograms` | `perf/PerformanceCollector.java:50` | A pooled *histogram* in the statistical sense — rank mass from many regions merged into one array. Not an object pool. | **KEEP** |

The section A item "audit remaining object pools" is closed with an empty result
set for Sourby-owned code. The nearest thing to a pool that Sourby code *touches*
is upstream's per-`PathNavigation` solver state — see §5.

---

## 5. Reusable solver state reached by the async-pathfinding patches

Not Sourby-owned, but Sourby patches changed who may reach it, so it is in scope.

`PathFinder` holds two reusable mutable A* buffers — `Node[32] neighbors` and a
`BinaryHeap openSet` (`src/minecraft/java/net/minecraft/world/level/pathfinder/PathFinder.java:21`,
`:24`) — plus a `NodeEvaluator` with its own node map. One `PathFinder` is built
per `PathNavigation` at construction (`PathNavigation.java:60`) and belongs to the
mob's region thread.

Patch 0006 does **not** share it. `sourbyKickAsyncRecompute` calls
`this.createPathFinder(...)` to build a *fresh* solver for each async solve
(`PathNavigation.java:153`, patch `0006-...patch:85-88`), with a comment saying
exactly why. That is the correct call, and it is the single most important thing
the patch gets right.

* **Verified:** there is no `static` mutable state in
  `net/minecraft/world/level/pathfinder` — the only statics are
  `WalkNodeEvaluator.PATH_TYPES` (a `values()` array, read-only) and two static
  methods. A per-solve solver is therefore genuinely isolated.
* **Verified:** `SnapshotPathRegion` really does copy, rather than alias, the block
  data — `section.getStates().copy()` at `SnapshotPathRegion.java:66`, with
  `hasOnlyAir` sections left null and read as air `:64-67`, `:91-97`. The class
  doc's "immutable copy" claim holds for every read path it overrides.
* **Not covered by the snapshot:** `SnapshotPathRegion` inherits
  `PathNavigationRegion.getWorldBorder()` (`PathNavigationRegion.java:106-108`),
  which returns the live `ServerLevel` world border. On this build that is a
  process-lifetime cached object (`ServerLevel.java:2464-2472`), so it does not
  repeat the 0007 `getCurrentWorldData()` failure, but its fields are mutable and
  read unsynchronised from the worker.
* **Retention:** the snapshot also inherits `PathNavigationRegion.chunks`
  (`:29`, `:41-49`), a live `ChunkAccess[][]`, held for the duration of the solve.
  The private `getChunk` paths that would dereference it are all overridden away in
  `SnapshotPathRegion`, so it is unreachable rather than unsafe — but it does pin
  those chunks against unload for the solve's lifetime.

**Verdict: BENCHMARK REQUIRED.** Async mode trades one A* solve's worth of
region-thread CPU for a full `PathFinder` + `NodeEvaluator` + `BinaryHeap`
allocation on every ~20-tick recompute, per mob, *plus* an uncached
`PathfindingContext` (0007 disables the shared `PathTypeCache` off-region, so every
off-region solve recomputes path types from scratch). Whether that is a net win is
exactly the kind of claim `DEVELOPMENT.md` §4.1 forbids asserting without
measurement. The feature is default-off, which is the right state until the section
E benchmark exists.

---

## 6. Entity-owned scratch: reachability, not proof

threading.md verified confinement (entity-owned, one region at a time) and escape
(contents not stored by a callee) for 0009, 0012 and 0016, and flagged reentrancy
as argued-by-inspection. Patch **0017** adds a fourth entity-owned scratch that
threading.md predates and does not cover.

What follows is the reachability set for each buffer, established from the generated
sources, and what a reentrant call would actually do. **None of this is a proof of
reentrancy safety.** It narrows where a proof would have to look.

| Buffer | Declared | Used in | Call sites of that method | A reentrant call would… |
|---|---|---|---|---|
| `collisionScratchVoxels`, `collisionScratchAabbs`, `collisionScratchEntityAabbs` | `Entity.java:421-423` | `Entity#collide` `:1739-1769` | **One.** `collide` is `private`; sole caller is `Entity#move` `:1292` | …invalidate the outer query; exception versus silent corruption is not proven. |
| `itemEntitiesScratch` | `Mob.java:119` | `Mob#aiStep` `:563-570` | `LivingEntity#tick` → `aiStep`, once per mob per tick, behind four gates `:553-557` | …invalidate the outer iteration; fail-fast exceptions are possible, not guaranteed. |
| `effectParticlesScratch` | `LivingEntity.java:1006` | `updateSynchronizedMobEffectParticles` `:1078-1092` | `updateInvisibilityStatus` path, once per tick per affected entity | …produce a wrong particle set for one tick. **Silent.** |
| `isInWallScratch` (patch 0017) | `Entity.java:429` | `Entity#isInWall` `:3226-3242` | **One.** `LivingEntity#isInWall` `:4810-4811` → `LivingEntity#baseTick` `:486`, behind `shouldCheckForSuffocation()` | …leave the position pointing at the inner call's block → wrong suffocation result. **Silent.** |

Review correction: iterator failure is not a safety guarantee. Collision utilities may
use indexed loops, and even fail-fast iterators do not promise detection of every
reentrant mutation. Both silent result changes and exceptions remain possible;
this audit does not establish either outcome for all paths. The isInWall scratch
was missing from the original ownership test and is covered by the follow-up.

### Escape points for 0012 and 0017 — now verified rather than asserted

Patch 0012's own comment claims every `BlockPos` consumer either reads coordinates
or chains a method that returns `.immutable()`, and that no consumer caches the
mutable (`0012-...patch:23-30`). The two capture points that would actually matter
were checked and both defend themselves:

* `CraftBlock` — `this.position = position.immutable();`
  (`paper-server/src/main/java/org/bukkit/craftbukkit/block/CraftBlock.java:75`),
  reached through `CraftBlock.at(...)` `:78-80`. Every Bukkit block event raised
  from a `randomTick` therefore stores a frozen position, not the scratch.
* `ScheduledTick` — the compact constructor does `pos = pos.immutable();`
  (`src/minecraft/java/net/minecraft/world/ticks/ScheduledTick.java:38-40`). Every
  `scheduleTick` from a random tick or a collision-shape lookup stores a copy.

`isInWallScratch` (0017) is passed only to `BlockState#isSuffocating` and
`BlockState#getCollisionShape` (`Entity.java:3237`, `:3242`); both are read-only
`BlockGetter`/`BlockPos` consumers, and the same two capture points cover anything
they reach. The 0017 scratch also never leaves the method.

**Verdicts.** 0009: **KEEP** + **BENCHMARK REQUIRED** (no benchmark exists; see D1).
0012: **KEEP** — method-local, shares nothing, escape points verified.
0016: **REDESIGN** — see D1. 0017: **KEEP** + **BENCHMARK REQUIRED**, and add it to
`ScratchBufferConfinementTest`.

---

## 7. Verified defects

Four findings: D1/D2 retain query results beyond their operation; D3 keeps an extra list of particle references but does not by itself prove longer particle-object lifetime than synchronized entity data; D4 misses retirement cleanup. No world-corruption conclusion or performance impact is established by this inspection.

### D1 — `Mob.itemEntitiesScratch` retains `ItemEntity` references between passes

`Mob.java:563-570`. The list is cleared on *entry* to the pickup block and left
populated when the loop ends. The block is gated four ways
(`level() instanceof ServerLevel && canPickUpLoot() && isAlive() && !this.dead &&
MOB_GRIEFING`, `:553-557`), so the clear only happens on the next pass that
satisfies all four. When any gate stops holding — the `mobGriefing` gamerule is
turned off, a plugin calls `setCanPickupItems(false)`, or the mob dies but is still
referenced — the mob keeps strong references to whatever `ItemEntity` instances were
in its pickup box at the last pass, indefinitely.

Each retained `ItemEntity` transitively pins an `ItemStack`, its `CraftEntity`
handle and its `Level`. Removed item entities are retained too. Blast radius is
`live mobs × items in the last pickup box` — small per mob, unbounded in aggregate
on a server with dense item clusters. `ArrayList.clear()` nulls its elements, so the
fix is one line after the loop.

**Classification: REDESIGN.** The reuse itself is sound; the clear is on the wrong
side of the loop.

### D2 — `Entity.collisionScratch*` retain `VoxelShape`/`AABB` between calls

`Entity.java:1733-1741`. The clear is deliberately placed *after* the
all-axes-zero early return — the patch comment says so explicitly
(`0009-...patch:30-32`) — with the stated intent of never leaking entries from a
previous call. It achieves the opposite for a stationary entity: `collide` returns
at `:1734` without clearing, so an entity that stops moving holds its last
collision resolution's `VoxelShape` list, `AABB` list and entity-`AABB` list for as
long as it exists. The `VoxelShape`s are mostly shared per-block-state singletons
and cheap; the `AABB`s are per-call allocations and are not.

Same shape as D1 and the same one-line fix. Lower severity, much higher entity
count.

**Classification: REDESIGN.**

### D3 — `LivingEntity.effectParticlesScratch` retains `ParticleOptions` between ticks

`LivingEntity.java:1078-1092`. Identical pattern: cleared on entry, left populated.
Retains one `ParticleOptions` per visible effect per affected living entity. These
are small and often shared, so this is the least severe of the three, but it is the
same defect and the same fix.

**Classification: REDESIGN** (low priority; fix alongside D1/D2).

### D4 — `sourbyAsyncPathPending` is never reset when the entity scheduler is retired

`PathNavigation.java:122`, `:156-171`. The latch is set to `true` before submitting
(`:156`) and reset only inside the scheduled callback (`:162`) or in the `catch`
(`:171`). It is driven by
`mob.getBukkitEntity().taskScheduler.scheduleOrExecute(...)` (`:161`), whose
contract is:

> Returns `Boolean.FALSE` indicating neither the run nor retired function will be
> invoked since the scheduler has been retired.
> — `paper-server/src/main/java/io/papermc/paper/threadedregions/EntityScheduler.java:163-165`

`scheduleOrExecute` returns `FALSE` without throwing (`:176-180`), and its
off-thread branch delegates to `schedule(run, null, 1L)` (`:173`) — a **null retired
callback**, so retirement mid-flight is also silent. The patch ignores the return
value. On a retired scheduler neither the callback nor the `catch` runs, and
`sourbyAsyncPathPending` stays `true` for the remaining life of that
`PathNavigation`, which is guarded at `:107` — that navigation never takes the async
branch again and falls back to the synchronous recompute permanently.

Not corruption: the mob still paths, just synchronously. Blast radius is limited
because a retired scheduler usually means the entity is gone and a reload builds a
fresh `Mob` with a fresh `PathNavigation`. The feature is default-off. It is still a
latch that can only ever be set, and the fix is to reset it when
`scheduleOrExecute` returns `Boolean.FALSE`.

**Classification: REDESIGN** (of the completion handling, not of the feature).

---

## 8. Latent fragilities — correct today, unasserted

Not defects. Each is a place where a routine edit would silently produce wrong
numbers, and none has a test pinning it.

**F1 — `MetricWindow` ordinal is silently coupled to the histogram layout.**
`RegionTickMetrics.acquireSnapshot` merges into fixed offsets in the order
`FIVE_SECONDS, TEN_SECONDS, MINUTE, FIVE_MINUTES, FIFTEEN_MINUTES`
(`RegionTickMetrics.java:143-147`). `PerformanceCollector` reads window `i` at
`i * HISTOGRAM_BINS` (`:431`) while taking its counts from `WINDOWS[i]`
(`:29`, `:201-204`). That is only correct because
`sourbyapi/src/main/java/dev/iyanz/sourbycraft/api/metrics/MetricWindow.java:5-9`
declares the constants in exactly that order. Reordering the enum — an edit nothing
would flag — misattributes every percentile without changing any count, so the
`histogramCount == this.count` guard at `:433` would not catch it either.

**F2 — `medianFinite` mutates its input.**
It compacts and sorts finite values in place. The original audit suggested that
swapping extrema and median evaluation corrupts extrema; review did not support
that claim, because sorting/compaction does not introduce a new finite extremum.
Keep the mutation visible to maintainers; no defect is established here.

**F3 — check-then-act eviction in `PluginLoadDiagnostics`.**
`if (ENTRIES.size() >= MAX_ENTRIES) ENTRIES.remove(0);` on a `CopyOnWriteArrayList`
(`:63-66`) is not atomic. Concurrent publishes can overshoot the 16-entry cap
slightly. The handler is installed before `loadPlugins` and is effectively
single-threaded, so this is theoretical — worth a comment, not a change.

**F4 — same pattern in `GeoUtil`.** The size check and half-eviction at
`util/GeoUtil.java:115-123` are not atomic either, so the 1024 cap is approximate
under concurrent lookups. A strict bound under concurrent insertions was not proven by this audit.

**F5 — `medianCapacity` is monotonic.** `PerformanceCollector` grows
`medianBuffer` to the highest active-region count ever observed and never shrinks
(`:216-233`). At 5 windows × 2 series × 4 bytes that is 40 bytes per peak region —
negligible, but it is a retained-heap high-water mark that a soak test should be
told to expect rather than flag.

**F6 — `ScratchBufferConfinementTest` has two blind spots.** It omits
`isInWallScratch` (§6), and its `ServerLevel` guard is name-based — it rejects
fields whose name contains "scratch" (`:76-86`), so a reintroduced level-held buffer
under any other name passes.

---

## 9. Region-thread contention note (measure, do not assume)

`RegionTickMetrics.tickCompleted` is `synchronized` (`:108`) and so is the
collector's `acquireSnapshot` (`:136`). Once per second per region, the collector
holds a region's instance monitor for the duration of `refreshPublicationIfDue` →
`publishSnapshot` (three raw-window scans over up to 384 samples, three bucket-window
scans over 61 + 169 slots, `:452-466`) plus five histogram merges (`:143-147`). A
region thread finishing a tick in that window blocks on the monitor.

The work is bounded and the cadence is 1 Hz, so this is very likely far below the
0.5 % telemetry budget in `DEVELOPMENT.md` §12 — but "very likely" is not a
measurement, and this is the only place where Sourby telemetry can block a region
tick. It belongs in the section B overhead measurement, not in a correctness
finding.

---

## 10. Remaining unknowns

**U1 — Reentrancy is not proven for any of the four entity-owned scratches.**
§6 gives the reachability set for each, which is stronger than "none found", and
identifies possible outer-query invalidation. It is not a proof. The cheap
way to convert it into a check is a boolean in-use guard asserted on entry
(`assert !this.collideInProgress`), which costs nothing in production because
assertions are disabled — but that is a production-code change and out of scope
here.

**U2 — No benchmark exists for any reuse in this audit.** Every KEEP above is a
correctness verdict. `DEVELOPMENT.md` §4.3 warns that allocation reuse is not
automatically faster on Java 25, and section F's "benchmark existing reusable entity
buffers" is still open. The three entity-scratch patches trade one allocation per
call for one permanently retained collection per entity — a trade whose sign is
unknown at scale and which D1–D3 currently make worse than it needs to be.

**U3 — Adventure `BossBar` thread-safety was not verified.** S12 is mutated by the
global-region updater (`HudBars.java:170-184`) while player region threads add and
remove viewers (`:106-109`, `:122-123`). Whether Adventure's `BossBar`
implementation tolerates that is a property of the Adventure library, which this
audit did not read. Nothing observed suggests a problem and the failure mode would
be cosmetic, but the claim is unverified.

**U4 — `getWorldBorder()` reads from the async-path worker.** §5. Cross-thread safety of these live reads remains unverified; the async feature stays default-off.

**U5 — Scope.** Only `dev.iyanz.sourbycraft` and the Sourby patch directories were
read as sources of record. Reused state inside Canvas, Paper, Folia and Moonrise is
out of scope except where a Sourby patch changed who can reach it.

---

## 11. What this audit did not do

* Did not run Gradle, apply patches, or execute any test.
* Did not change production code, docs owned by other tracks, or branch state.
* Did not benchmark anything; no performance claim here is measured.
* Did not re-derive threading.md's confinement and escape findings for 0009/0012/0016
  — it builds on them and adds lifetime and reachability.
* Did not audit the Spark configuration provider or its secret filtering
  (`perf/../spark/SourbyServerConfigProvider.java`), which is concurrently owned
  elsewhere.

## 12. Integration follow-up (Codex, 2026-09-14)

The inventory above records the code Claude inspected before follow-up edits.
Line references identify that inspected revision and may move after integration.

- D1/D2: remove downstream patch 0009 and the Mob portion of 0016, restoring upstream per-call
  item/collision collections. This removes entity-held query-result retention and
  the corresponding reusable-list reentrancy hazard. The published Level query
  overload from 0016 remains for NMS binary compatibility.
- D3: remove patch 0014, restoring a fresh effect-particle list per publication.
  The published list is no longer reused or mutated on subsequent calls. The
  extra per-entity scratch container and its duplicate references disappear.
- These removals can increase short-lived allocations; no throughput improvement
  or performance-stable qualification is claimed. There was no benchmark proving
  the removed reuse beneficial. Region/gameplay logic stays upstream-defined.
- D4: route completion through Sourby-owned AsyncPathCompletion with an explicit
  retired callback, failed-admission cleanup, and owning-scheduler application.
  Completions are now always queued for a subsequent entity tick, including the
  caller-runs fallback. The full async snapshot/staleness/behavior audit remains open.
- Add the surviving isInWall scalar-position scratch to confinement checks.
- The report's original guaranteed-CME and median/extrema claims were corrected
  during integration review. Shared HUD safety, strict concurrent cache bounds,
  live world-border reads and remaining reentrancy proof are still open.

Collaboration: Claude CLI session 988a8822-80be-44bc-9c26-9bbc2aa1467b produced the
initial audit; Codex reviewed the findings and owns the follow-up changes/tests.
