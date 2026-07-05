# MT1 Task 1 Report

## Commit
`58abc0a` — core: MT1 module registry + PerWorldHolder + chunk-worker/send-rate bridges + async-spawning config

## Files Changed

### New
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/core/SourbyModule.java`
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/core/ModuleRegistry.java`
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/core/PerWorldHolder.java`

### Modified
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftWorldConfig.java`
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/LagLimits.java`
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/ViewThrottle.java`
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/swm/plugin/SWPlugin.java`
- `sourbycraft-server/src/main/resources/sourbycraft.yml`

## Sites Chosen

- **Thread bridge insertion point**: `SourbyCraftConfig.init()` after the compression bridge block
  (lines ~477–495 of original), matching the plan's "same init region as the compression bridge (S5)".
- **Pufferfish config reference**: direct class reference to
  `gg.pufferfish.pufferfish.PufferfishConfig` — class is compiled in the same main source set
  via `srcDir("src/main/pufferfish")` so no reflection needed; still wrapped in try/catch per plan.
- **PerWorldHolder for SourbyCraftWorldConfig**: `BY_WORLD` field in the same class; `get()`
  unchanged signature; `invalidate(String)` kept as delegate calling `BY_WORLD.remove()`.
- **ViewThrottle**: `originalViewDistance` map swapped to `PerWorldHolder<Integer>`; the inline
  anonymous WorldUnloadEvent listener in `register()` deleted; tick() uses same `.computeIfAbsent`
  and `.remove` API which PerWorldHolder exposes.
- **LagLimits**: `ARROW_COUNT` map swapped from `HashMap<String, Integer>` to
  `PerWorldHolder<Integer>`; the `onWorldUnload` handler (which also called
  `SourbyCraftWorldConfig.invalidate`) fully deleted. PerWorldHolder covers both evictions.
- **SWPlugin**: `safeRegister` helper deleted; onEnable restructured to
  `PerWorldHolder.registerCleanup(this)` + 8 `ModuleRegistry.add(...)` calls (SignSanitizer moved
  from direct call into a module) + conditional motd-suffix add + `ModuleRegistry.enableAll(this)`;
  EmojiChatListener stays as direct registerEvents (not in ModuleRegistry); `onDisable` prepended
  with `ModuleRegistry.disableAll()`.

## adjustWorkerThreads Semantics

Examined `MoonriseCommon.java` (paper-server/src/main/java/ca/spottedleaf/moonrise/common/util/):
- Method signature: `adjustWorkerThreads(int configWorkerThreads, int configIoThreads)`
- Calls `WORKER_POOL.adjustThreadCount(workerThreads)` and `IO_POOL.adjustThreadCount(ioThreads)`
- `BalancedPrioritisedThreadPool.adjustThreadCount` is designed for runtime resize (no indication
  of unsafety for a second call).
- Paper calls it once from `GlobalConfiguration.ChunkSystem.postProcess` (line 222 of
  GlobalConfiguration.java).
- **Second-call safety**: safe to call again — wraps in try/catch per plan ("never crash boot").
- When `configIoThreads = -1`: `Math.max(1, -1) = 1` so io pool gets exactly 1 thread. Paper's
  default is also `ioThreads = -1` so Paper also sets io=1 on its call. Re-calling with -1
  keeps io at 1 (no net change when Paper already called with -1).
- Smart-auto logic: only calls when `GlobalConfiguration.get().chunkSystem.workerThreads == -1`
  (Paper also auto), computes `min(8, max(2, cpus/2))`.

## Pufferfish Config Findings

`gg.pufferfish.pufferfish.PufferfishConfig` is compiled into the same source set (srcDir pufferfish).
It has two relevant static fields: `enableAsyncMobSpawning` and `asyncMobSpawningInitialized`.
The class is loadable at SourbyCraftConfig.init() time but `asyncMobSpawningInitialized` may be
false (PufferfishConfig's init method hasn't run yet). Bridge: check `asyncMobSpawningInitialized`
first, then force `asyncSpawning = false` if `enableAsyncMobSpawning == false`. Wrapped in
try/catch — if class throws during static access, logged nothing and skipped (as specified).

## Deviations / Adaptations

1. **EmojiChatListener not moved into ModuleRegistry** — plan says "safeRegister pile" is migrated;
   EmojiChatListener was never in safeRegister (direct registerEvents). Kept as-is.
2. **io/ioWorkers passing to adjustWorkerThreads**: When `chunkWorkers > 0`, we pass `ioWorkers`
   directly (which may be -1, resulting in io=1). This matches the plan's "ioWorkersOrMinus1"
   phrasing. If the operator sets `io-workers: N > 0`, it will be passed and applied.
3. **PerWorldHolder registrations happen at `new PerWorldHolder<>()` time** (three static fields
   in SourbyCraftWorldConfig, ViewThrottle, LagLimits — created at class-load, before
   `registerCleanup` is called). This is by design per the plan's "Holders created before
   registration still evict" guarantee — the `ALL` list is populated at construction time.
4. **sourbycraft.yml baked resource**: added `performance:` top-level section with `threads.chunk-workers`,
   `threads.io-workers`, `async-spawning`; added `network.max-chunk-send-rate` inside existing
   `network:` block. These also get materialized to operator yml via `getInt/getDouble/getBoolean`
   on first boot.

## Build Result
`./gradlew :sourbycraft-server:compileJava -q` → BUILD SUCCESSFUL (11 pre-existing warnings, 0 errors).
