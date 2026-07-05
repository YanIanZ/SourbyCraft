# MT1 Multithread Uplift — Implementation Plan

Spec: docs/superpowers/specs/2026-07-05-mt1-multithread-uplift-design.md
Repo: /Users/rheninxy/Sourby/SourbyCraft, branch release/26.1.2.
Task 1 (outer) MUST land before Task 2 (nested) — Task 2 consumes the
`asyncSpawning` config field Task 1 adds. Task 3 = driver.

Implementer latitude: HIGH on locating exact sites; semantics below are
fixed. Report every adaptation.

## Task 1 (outer): core module framework + thread bridges

**New files (package `dev.iyanz.sourbycraft.core`):**

- `SourbyModule.java`:

```java
package dev.iyanz.sourbycraft.core;

import org.bukkit.plugin.Plugin;

/** A SourbyCraft feature with an isolated lifecycle. Enable failures never propagate. */
public interface SourbyModule {
    String name();
    void enable(Plugin plugin) throws Exception;
    default void disable() {}
}
```

- `ModuleRegistry.java`: static `add(String name, EnableFn)` convenience
  (functional adapter so existing `X.register(plugin)` statics enroll without
  rewrites) + `add(SourbyModule)`; `enableAll(Plugin)` iterates in
  registration order, each in try/catch(Throwable) logging
  `Failed to enable module <name> — continuing`, collects results, emits ONE
  summary INFO `[SourbyCraft] modules: <name>✓/✗ ... (N enabled, M failed)`;
  `disableAll()` reverse order, try/catch each.

- `PerWorldHolder.java`:

```java
package dev.iyanz.sourbycraft.core;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Name-keyed per-world cache with centralized WorldUnloadEvent eviction —
 * SWM island resets reuse world names, so every per-world map MUST evict on
 * unload. One shared listener serves all holders.
 */
public final class PerWorldHolder<T> {

    private static final List<PerWorldHolder<?>> ALL = new CopyOnWriteArrayList<>();
    private static volatile boolean listenerRegistered;

    private final ConcurrentHashMap<String, T> map = new ConcurrentHashMap<>();

    public PerWorldHolder() {
        ALL.add(this);
    }

    /** Idempotent; call once from plugin enable. Holders created before registration still evict. */
    public static void registerCleanup(Plugin plugin) {
        if (listenerRegistered) return;
        listenerRegistered = true;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onWorldUnload(WorldUnloadEvent e) {
                String name = e.getWorld().getName();
                for (PerWorldHolder<?> holder : ALL) holder.map.remove(name);
            }
        }, plugin);
    }

    public T computeIfAbsent(String worldName, Function<String, T> factory) { return map.computeIfAbsent(worldName, factory); }
    public T get(String worldName) { return map.get(worldName); }
    public void put(String worldName, T value) { map.put(worldName, value); }
    public void remove(String worldName) { map.remove(worldName); }
    public boolean isEmpty() { return map.isEmpty(); }
}
```

**Migrations (minimal churn):**

- `SourbyCraftWorldConfig`: replace `BY_WORLD` ConcurrentHashMap with
  `PerWorldHolder<SourbyCraftWorldConfig>`; keep `invalidate(String)` as a
  delegate (LagLimits stops calling it — see below).
- `ViewThrottle.originalViewDistance` → `PerWorldHolder<Integer>`; delete its
  inline WorldUnloadEvent listener.
- `LagLimits.ARROW_COUNT` → `PerWorldHolder<Integer>`; delete its
  onWorldUnload handler (including the SourbyCraftWorldConfig.invalidate call
  — PerWorldHolder now covers the world-config too).
- `SWPlugin.onEnable`: replace the `safeRegister(...)` pile with
  `PerWorldHolder.registerCleanup(this)` + `ModuleRegistry.add(...)` for:
  stacker, oreReveal, configBridge, lagLimits, ownerProtection, viewThrottle,
  signSanitizer (move its existing registration in), motd-suffix (conditional
  module), then `ModuleRegistry.enableAll(this)`. Delete `safeRegister`.
  `onDisable`: prepend `ModuleRegistry.disableAll()`.

**Thread bridges (SourbyCraftConfig):**

- Fields: `chunkWorkers = -1`, `ioWorkers = -1`, `maxChunkSendRate = -1.0`,
  `asyncSpawning = true`.
- Reads: `performance.threads.chunk-workers`, `performance.threads.io-workers`,
  `network.max-chunk-send-rate`, `performance.async-spawning`.
- Alias input: if pufferfish config is present in this tree at runtime
  (gg.pufferfish.pufferfish.PufferfishConfig.enableAsyncMobSpawning readable
  statically), honor `false` there as forcing ours false; wrap in try/catch
  (class may not be initialized) and report what you found.
- Bridge (same init region as the compression bridge):
  - chunk-workers set > 0 → `MoonriseCommon.adjustWorkerThreads(chunkWorkers, ioWorkersOrMinus1)`.
  - chunk-workers == -1 (smart auto) → only when Paper
    `GlobalConfiguration.get().chunkSystem.workerThreads == -1`: compute
    `smart = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2))`
    and call `MoonriseCommon.adjustWorkerThreads(smart, -1)`.
    VERIFY `adjustWorkerThreads` semantics first (paper-server
    ca/spottedleaf/moonrise/common/util/MoonriseCommon.java:42) — if a second
    call cannot shrink/grow the pool safely, report and fall back to only
    setting when we can (never crash boot).
  - maxChunkSendRate > 0 → `GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkSendRate = maxChunkSendRate;`
  - Boot INFO: `[SourbyCraft] threads: chunk-workers=<final> io=<final> send-rate=<x> async-spawning=<bool>`
    (read the final values back from MoonriseCommon if getters exist, else log requested values).

**Steps:** implement → `./gradlew :sourbycraft-server:compileJava -q`
BUILD SUCCESSFUL → commit EXACTLY:
`core: MT1 module registry + PerWorldHolder + chunk-worker/send-rate bridges + async-spawning config`
Report: .superpowers/sdd/mt1-task-1-report.md

## Task 2 (nested): async spawn pipeline

**File:** `sourcycraft-server/src/minecraft/java/net/minecraft/server/level/ServerChunkCache.java`
(around lines 550-625: the tickChunks region computing
`NaturalSpawner.createState(...)` then looping `tickSpawningChunk` →
`NaturalSpawner.spawnForChunk`). Possibly `NaturalSpawner.java` for
visibility/signature only.

Semantics (pufferfish enableAsyncMobSpawning, engine built here):

1. Add fields to ServerChunkCache:

```java
    // SourbyCraft MT1 - async mob spawning (pufferfish semantics): the density/mob-cap scan
    // (NaturalSpawner.createState) runs off-main; spawns + events stay on main.
    private volatile NaturalSpawner.SpawnState sourbyAsyncSpawnState;
    private java.util.concurrent.CompletableFuture<Void> sourbyAsyncSpawnTask;
    private long sourbyAsyncSpawnFailLog;
```

2. In tickChunks where vanilla computes the SpawnState synchronously:
   - `if (!dev.iyanz.sourbycraft.SourbyCraftConfig.asyncSpawning)` → vanilla
     sync path unchanged.
   - Else: use `sourbyAsyncSpawnState` if non-null (one tick stale — by
     design); when null (first tick / after failure) compute synchronously as
     fallback so spawning NEVER skips.
3. At the end of tickChunks (after the spawn loop), when asyncSpawning:
   submit the next createState to
   `dev.iyanz.sourbycraft.util.VirtualExecutor.run(...)`, capturing the
   SAME inputs vanilla uses (naturalSpawnChunkCount, entity iterable, etc. —
   read the exact vanilla call args and mirror them). Guard body with
   try/catch(Throwable): on failure set state null + rate-limited WARN (≥60 s
   between logs via sourbyAsyncSpawnFailLog nanos).
   Do not overlap tasks: if previous future not done, skip submitting (keep
   previous result; log nothing — natural backpressure).
4. THREAD-SAFETY NOTES to respect (report how each was handled):
   - The entity iterable passed to createState must tolerate concurrent
     modification — mirror pufferfish: iterate a snapshot. If vanilla passes
     a live iterable, snapshot it cheaply ON MAIN before submit
     (e.g. copy to ArrayList) and pass the snapshot — measure: this copy is
     far cheaper than the full createState scan (it is the accepted cost).
   - `LocalMobCapCalculator` inside SpawnState touches ChunkMap — verify
     what vanilla passes; if it lazily queries chunk data during
     spawnForChunk (main thread) that is fine; only the createState portion
     moves off-thread.
5. Compile → BUILD SUCCESSFUL. Nested commit EXACTLY:
`SourbyCraft MT1: async mob-spawn density state (pufferfish semantics) behind performance.async-spawning`
Report: .superpowers/sdd/mt1-task-2-report.md

## Task 3 (driver)

- rebuild patches → outer commit
  `perf: MT1 async spawn pipeline (feature patch)`
- Combined review (sonnet; opus if findings heavy) → fixes → artifact →
  ledger + memory + user summary.
