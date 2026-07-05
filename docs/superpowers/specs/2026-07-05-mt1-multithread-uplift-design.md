# MT1 — Multithread Uplift + OOP Module Cleanup

**Date:** 2026-07-05
**Status:** Approved (continuous execution authorized)
**Request:** "rapikan OOP, dan update world atau chunk generate, render, dan
entity spawn, menjadi multithread tanpa merusak plugin kompatible. efisiensi
ram dan CPU USAGE juga perlu diperhatikan tanpa mengorbankan power."

## Ground truth (from threading survey)

- **Chunk generation is already multithreaded** (moonrise `WORKER_POOL`,
  `parallelGenExecutor` + radius-aware scheduler). But the pool defaults are
  conservative — cores/4 (≤3 cores: 1–2) or cores/8 — and nothing in
  sourbycraft.yml exposes it. The uplift is exposure + a smarter default, not
  a rewrite.
- **Anti-xray obfuscation already runs off-main** (controller executor).
  Chunk-send pacing is governed by Paper `chunk-loading-basic.*` knobs.
- **Entity spawning**: `NaturalSpawner.createState()` (mob-cap/density scan
  over all entities+spawning chunks) runs on main every tick in
  `ServerChunkCache.tickChunks`. Pufferfish's proven answer — compute the
  SpawnState asynchronously, use the last completed result on main; actual
  spawns + `CreatureSpawnEvent` stay main-thread — exists in this tree ONLY
  as a config key (`PufferfishConfig.enableAsyncMobSpawning`, zero
  consumers). We build the engine.

## Plugin-compat invariants (hard lines, from AsyncCatcher audit)

Never leave main thread: `addFreshEntity`/entity add (ServerLevel:1619),
entity tracker register/unregister, all Bukkit event dispatch, chunk packet
send. Off-thread work is strictly *read/compute*: spawn density state,
raytrace, serialization.

## Work items

### 1. OOP cleanup — module registry (outer)

- New `dev.iyanz.sourbycraft.core.SourbyModule` (name + enable(Plugin) +
  default disable()) and `dev.iyanz.sourbycraft.core.ModuleRegistry`:
  ordered list, per-module try/catch isolation (replaces the S-final
  `safeRegister` pile), one boot summary line
  `[SourbyCraft] modules: stacker✓ oreReveal✓ ... (N enabled, M failed)`,
  `disableAll()` called from `SWPlugin.onDisable`.
- The 7 static `register(Plugin)` features (EntityStacker, OreReveal,
  ConfigBridge, LagLimits, OwnerProtection, ViewThrottle, SignSanitizer) plus
  the motd listener enroll as modules (thin adapters — their internals are
  not rewritten; churn stays minimal).
- New `dev.iyanz.sourbycraft.core.PerWorldHolder<T>` — generic
  name-keyed per-world cache with a SINGLE shared `WorldUnloadEvent`
  cleanup listener. Adopted by: `SourbyCraftWorldConfig.BY_WORLD`,
  `ViewThrottle.originalViewDistance`, `LagLimits.ARROW_COUNT` (removes three
  hand-rolled cleanup paths; RAM leak class eliminated once).

### 2. Chunk generation exposure (outer)

New sourbycraft.yml section, bridged in `SourbyCraftConfig.init` via
`MoonriseCommon.adjustWorkerThreads(gen, io)` (re-adjust is supported — Paper
itself calls it from global-config post-process):

```yaml
performance:
  threads:
    chunk-workers: -1   # -1 = smart auto: max(2, cores/2) capped at 8 — vs moonrise stock cores/4..cores/8
    io-workers: -1      # -1 = moonrise auto
```

`chunk-workers: -1` applies the smart-auto only when the operator has not set
Paper's `chunk-system.worker-threads` (respect explicit Paper config). Boot
INFO reports final counts: `[SourbyCraft] threads: chunk-workers=N io=M`.
CPU note: workers idle-park when no gen work (moonrise queue hold 20 ms), so
raising the ceiling costs RAM ~stack-size per thread only.

### 3. Chunk send pacing exposure (outer)

```yaml
network:
  max-chunk-send-rate: -1   # -1 = keep Paper chunk-loading-basic defaults (75/s)
```

Bridged to `GlobalConfiguration.chunkLoadingBasic.playerMaxChunkSendRate`
when set > 0 (read per-player at join). Obfuscation already async; nothing
else needed on the send path.

### 4. Async entity-spawn pipeline (nested — the real engine)

`performance.async-spawning` (default **true**; pufferfish key
`enableAsyncMobSpawning` is honored as an alias input — if operator set it
false in pufferfish.yml, we respect false):

- `ServerChunkCache.tickChunks`: instead of computing
  `NaturalSpawner.createState(...)` synchronously, use the result computed
  asynchronously at the END of the previous tick (one-tick-stale densities —
  the proven pufferfish tradeoff). Submit next computation to
  `VirtualExecutor` after the tick's entity data is stable.
- First tick / result-not-ready → fall back to synchronous compute (never
  skip spawning entirely).
- Disabled → exact vanilla path (sync compute), zero new cost.
- All spawn placement, `addFreshEntity`, and `CreatureSpawnEvent` remain
  untouched on main thread → plugin compatible by construction.
- Guard: async task catches Throwable → logs once per minute max, falls back
  to sync next tick (self-healing; a CME from concurrent entity mutation
  degrades gracefully, mirroring pufferfish behavior).

Main-thread savings: the createState scan is O(loaded entities +
spawn-chunks) every tick — typically 0.3–1.5 ms on busy servers; that goes
off-main.

## RAM/CPU efficiency posture

- No new platform threads: async spawn + raytrace ride VirtualExecutor
  (~1 KB stacks). Worker-pool ceiling raise is operator-visible and parked
  when idle.
- PerWorldHolder unifies eviction — no per-world map can leak again.
- No allocation added on per-tick happy paths (async submit reuses one
  runnable; state object replaces the one vanilla already allocates).

## Verification (manual TestServer)

1. Boot: module summary line; threads report line; `async-spawning=true` in
   effect (log line once).
2. Mob farm world: spawns continue normally; `/tps` mspt drops vs sync when
   entity count high; CreatureSpawnEvent plugins (e.g. SS2 hooks) still fire.
3. `performance.async-spawning: false` → identical vanilla behavior.
4. `performance.threads.chunk-workers: 6` → boot INFO shows 6; elytra fly
   gen speed visibly improves on multi-core host.
5. World unload/reload (SWM island reset) → no stale per-world state (holder
   eviction), no memory growth across 50 resets.

## Files

- Outer: `core/SourbyModule.java`, `core/ModuleRegistry.java`,
  `core/PerWorldHolder.java` (new); `SWPlugin.java`,
  `SourbyCraftWorldConfig.java`, `perf/ViewThrottle.java`,
  `perf/LagLimits.java`, `SourbyCraftConfig.java` (bridges + keys).
- Nested (1 feature patch): `ServerChunkCache.java` (+ `NaturalSpawner.java`
  visibility if needed).
