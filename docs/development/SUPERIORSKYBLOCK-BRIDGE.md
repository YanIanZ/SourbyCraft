# SuperiorSkyblock2 spawn callback ownership

Status: source fix written, testing assigned to Claude; no Codex build, tests or boot.
The user's Build 47 log demonstrates a region-ownership violation. It does not
demonstrate the behavior of this new source patch or qualify the whole plugin.

## Status update 2026-10-22: island chunk preparation on the async lane (paper 0028)

Sourby Demo (r48.2, SSB2 2026.3 + SSB-AuroraWorlds, bridge `safe`), `/is create`: many
`Thread failed main thread check: Async chunk retrieval ... thread=SourbyCraft-BridgeIO-1 ... chunk_pos=[-1, 2]`
followed by `[SuperiorSkyblock2] An unexpected error occurred while interacting with unloaded chunk`. Path (source of
the jar's commit `868bebf`): on create, the callers that fit the stack are `IslandUtils.resetChunksExcludedFromList` and `setBiome`, which run
`NMSUtils.runActionOnChunks` 10 ticks after the post-create teleport (inferred from source, not traced on the panel); chunks not loaded at that moment go to an async task (`BukkitExecutor.runAsync`, the bridge
I/O lane) that reads each chunk from disk; a chunk never saved (new island world) calls `onChunkNotExist` ->
`ChunksProvider.loadChunk` -> `ChunkPosition.getLoadedChunk`, which calls `World#getChunkAt` **only when `isChunkLoaded`
is already true** (the player's chunk loader loaded it meanwhile). The base refused that call on any non-owning thread,
SSB2 caught it, and the chunk was skipped for that reset/biome operation. Nothing was counted toward quarantine (SSB2
catches the exception). After the lookup SSB2 hands the chunk back with `BukkitExecutor.ensureMain` (a bridged sync task,
global region or caller region per `sync-route`), unchanged by this fix.

Fix: engine-wide owned rule for threads that tick nothing, not an SSB2 adapter (an adapter would cover one plugin
callback, while Paper semantics are what every legacy plugin's async `getChunkAt` expects); see
[patches.md](../architecture/patches.md) (paper 0028). Headless evidence: the legacy-test-plugin probe reproduces the
exact refused call (a loaded, ticketed chunk from `SourbyCraft-BridgeIO-2`) and is served after the fix; a headless
client `/is create isle normal` with SSB2 + module creates the island with no refusal both before and after (the race
needs a chunk loaded between SSB2's two checks, which the local client did not hit). Owner check on the panel remains.

## Status update 2026-10-07

Sources: [AGENT-COORDINATION.md](../../AGENT-COORDINATION.md) (entries "SSB2 SpawnIsland violation, 2026-10-06 ~22:00",
"COWORK MODE on the bridge", "bridge-fix agent done"), `sourbycraft-server/build/test-results`, and
`~/Sourby/ssb/SSB2/FOLIA.md`. The paragraph above is the 2026-10-06 state.

- **SuperiorSkyblock2 2026.3 was ported to native Folia instead** (owner choice, log ~22:00): repository
  `~/Sourby/ssb/SSB2`, branch `folia`, commit `5818885` "Run as a native Folia plugin", documented in its `FOLIA.md`.
  Its `plugin.yml` declares `folia-supported: true`, so the bridge no longer admits or adapts that jar, and the spawn biome
  is read on the spawn's region. `FOLIA.md` records it verified on Sourby Demo 2026-10-06 with the SSB-AuroraWorlds module:
  boot without bridge admission or region violations, island create/paste/teleport, AWF save, restart and rejoin, admin
  disband. It lists as not verified: upstream Folia itself, spawner/stacker hooks, entity recalculation, island worth with
  spawner providers, multi-player concurrency. **This adapter therefore applies only to the unported upstream 2026.3
  jar.** It is hash-pinned, so the ported jar (different `SpawnIsland` bytes) is expected not to match; no test or boot
  here has exercised that.
- **The adapter stays in the tree and is now unit-tested.** `BridgeReviewTest.superiorCallbackWithoutMetaIsRefusedBeforeAdmissionWithoutViolation`
  covers B87: `SuperiorSpawnTaskOwner.resolve` threw a `NullPointerException` when the plugin meta was null; it now refuses
  with `IllegalArgumentException` before admission, with no task index, no executor job, no fatal violation and no
  quarantine (log, bridge-fix agent). The test uses a stand-in class
  (`sourbycraft-server/src/test/java/com/bgsoftware/superiorskyblock/island/SpawnIsland.java`) whose bytes are not the audited
  class, so the **accept path for the real 2026.3 jar's hashes has not been executed by any test or boot**. Result set of
  2026-10-06T18:26Z: `BridgeReviewTest` 7/7 and `BridgeTargetRoutingTest` 9/9 (the 3 V36 methods plus 6 V39 entity-routing
  methods), 0 failures.
- **Patch 0010 is committed** in `paper-server` as `a31e1184` (log, bridge-fix agent); 0011 (entity routing) is `441f8e56e` on
  top. The sentence in "Changes" that calls 0010 "an additional uncommitted working-tree change" describes 2026-10-06. The
  patch file is still untracked in the root repository (`git status` 2026-10-07).
- Acceptance cases, item by item (2026-10-07): **1** not run against the exact upstream 2026.3 jar (the demo now runs the
  ported jar). **2** unit level only: `v36TargetBeatsBothCallerAndGlobalFallback` and `regionTaskSnapshotsFlooredChunkCoordinates`
  (negative coordinates, later mutation of the Location); no server run. **3** unit level only:
  `v36GlobalAndAsyncNeverResolveTargetMetadata`. **4** partly: null meta and a wrong-bytes stand-in at the right version are
  refused without violation (`BridgeReviewTest`); missing resource, inaccessible capture, null world, island subclass, and the
  static/`Consumer` callbacks have no recorded test. **5** and **6** not run.

## Status update 2026-10-20: accept path run against the real jar classes

`SuperiorSpawnRealJarTest` (sourbycraft-server tests, `dev.iyanz.sourbycraft.bridge`) loads `SpawnIsland` and
`SWorldPosition` from a real jar in a child-first loader (so the test stand-in cannot win), allocates the island
without its constructor, sets the two final fields `getCenter(null)` reads (`center`, `spawnWorld`), and links the
callback with the jar's own bootstrap method #2 arguments (`LambdaMetafactory.metafactory`, `REF_invokeSpecial
lambda$new$0()V`, captured `SpawnIsland`) through a full-privilege lookup in the plugin loader's module — the same
link the constructor's `invokedynamic` performs. The plugin is a proxy defined in that loader. It runs only when
`SOURBY_SSB2_JAR` (and `SOURBY_SSB2_FOLIA_JAR` for the last case) name the jars; CI has neither, so CI skips it.

Local run 2026-10-20 (opus-w2), `SOURBY_SSB2_JAR=/Volumes/Ext/Sourby/ssb/jars/SuperiorSkyblock2-2026.3.jar` (SHA-256
`007bee17…f64ff597`; class hashes equal the table below) and `SOURBY_SSB2_FOLIA_JAR=…/jars/folia/SuperiorSkyblock2-2026.3.jar`
(`19e559cb…dc68fb4f35a`; `SpawnIsland.class` `a2ce7ff3…07cb247`, `SWorldPosition.class` unchanged): 4/4 passed, 0 skipped,
with `BridgeReviewTest` 11/11 in the same run.

- **Acceptance case 1, unit level:** the audited bytes are accepted; a spawn at (-17.5, 100, 40.25) resolves to chunk
  (-2, 2) in the spawn world; resolving the owner leaves `biome` null (the callback is not run). The same bytes with
  version `2026.4`, or with a plugin from another classloader, are refused.
- **Case 4, two more items:** a null spawn world is refused ("no world location"); the static `onSettingsUpdate`
  Runnable (bootstrap #0, no capture) returns null and keeps normal routing.
- **Folia-ported jar:** its constructor callback captures the island *and* a `Location` and is scheduled with
  `BukkitExecutor.sync(Location, Runnable)`; the adapter returns null for it (not the audited single-capture shape),
  so it neither reflects into it nor refuses it. The earlier expectation "different `SpawnIsland` bytes are expected
  not to match" is therefore not what happens: the shape check stops it before the fingerprint check.

Still not run: the plugin's real constructor and `BukkitExecutor`, the bridge scheduling the task onto the owning
region on a server, the actual biome read, another world, an initially unloaded chunk, an island subclass,
inaccessible capture, missing class resource. Cases 5 and 6 unchanged.

## Root cause and scope

Task 35 executes on `{god-tick}` through `BridgeRuntime` and calls
`SpawnIsland.lambda$new$0` → `CraftBlock.getBiome()` at overworld `(0,100,0)`.
The global tick does not own that chunk. Default caller-region routing also lacks
a target when the task originates during startup or from the global tick. Routing
to a region chosen from the caller cannot establish ownership of an unrelated
location inside a plugin callback.

Read-only `javap -p -c` inspection of the existing local artifact
`/Users/rheninxy/Sourby/ssb/jars/SuperiorSkyblock2-2026.3.jar` shows:

- Metadata: plugin `SuperiorSkyblock2`, version `2026.3`.
- Constructor: one instance-capturing Runnable invokes `lambda$new$0()` through
  `BukkitExecutor.sync`. Its body assigns `biome = getCenter(null).getBlock().getBiome()`.
- `getCenter(Dimension)` copies the final center through `SWorldPosition.toLocation(World)`.
  That method creates a Location from final x/y/z/yaw/pitch fields; it performs no world reads.
- Another Runnable in SpawnIsland is a static settings callback with no capture;
  it must not be mistaken for the constructor callback. The instance callback for
  `updateBorder` is a Consumer, not a Runnable.

References: SuperiorSkyblock2 by BG-Software / Ome_R,
[upstream repository](https://github.com/BG-Software-LLC/SuperiorSkyblock2).
This adapter contains original owner-resolution code; no plugin source or JAR is
copied into SourbyCraft. No assertion is made that upstream dev equals the local artifact.

## Changes

`RegionTask` in sourbyapi lets a plugin provide a world/chunk explicitly while using
the synchronous Bukkit scheduler. `RegionTask.at(location, action)` snapshots the
coordinates, including correct floor/shift behavior for negative coordinates.
The callback must restrict accesses to that region; entities use their native scheduler.

```java
final Location target = spawn.clone();
Bukkit.getScheduler().runTask(plugin,
    RegionTask.at(target, () -> {
        Biome biome = target.getBlock().getBiome();
        // Use the result on this owner, or publish immutable data to another callback.
    }));
```

The callback's own location must also remain stable; mutating `spawn` after
submission does not move the captured scheduling anchor. Prefer a cloned Location
for both the wrapper and callback when a caller retains the original Location.

Feature patch `0010-SourbyCraft-route-explicitly-owned-bridge-callbacks.patch` carries
the original callback into `AuroraBridge.targetRegion` before dispatch. It is the
source of truth for the targeted materialized CraftScheduler edit. Paper HEAD at
the time of writing contains 0007/0008/0009; this patch is an additional uncommitted
working-tree change. Codex did not run Gradle or rebuild the existing patches.

`BridgeRuntime.Task.targetRegion()` takes precedence over the caller-region/global
fallback setting (and, since 2026-10-22, over the inferred player anchor of the scheduling
context; full order in [the bridge doc](../architecture/aurora-plugin-bridge.md#routing)). Explicit global requests never query owner metadata. Async
requests remain on the existing governed I/O lane. Resolver failures cancel/reject
before queue admission and diagnose the failure; they do not increment fatal
ownership/quarantine counts. Existing task indexes, cancellation, capacity limits,
execution guarding, and native region merge/split handling are retained.

The SuperiorSkyblock adapter accepts only a Runnable lambda capturing one exact
SpawnIsland instance, matching its plugin classloader and version, and these hashes:

| Class resource | SHA-256 |
| --- | --- |
| SpawnIsland.class | `fdfbff8e58fe4db81640c0e03edc49eee94dd8726cbc7661da88a70f604f69ed` |
| SWorldPosition.class | `d2c86dee7049f0b1e937ee08dceb93531a7e8bfdd7a46cd6f3736e31908db126` |

Hidden/synthetic/nest/capture checks guard the reflective access. Hash and accessor
resolution are cached with ClassValue so plugin classloaders can be collected.
Class resources are read once during adapter resolution with a 1 MiB per-class cap.
The adapter resolves only immutable spawn coordinates before dispatch; the actual
biome read happens inside the original callback on the native region scheduler.
No TickThread check is bypassed, no failed callback is replayed, and no tick thread
waits for another region to answer a world read. No executor is added.

A modified/dev artifact with the same version string is not automatically trusted.
A recognized instance callback with unsupported fingerprint/version/capture is
rejected with an owner-resolution message. Static/unrelated callbacks keep normal
routing. Other SuperiorSkyblock operations remain subject to ownership checks and
may need separate adapters or a native plugin port. Runtime bytecode transformation
or other plugin forks require a fresh audit; resource hashes alone do not certify
transformed loaded classes.

## Claude acceptance cases — partly covered (status per item in the 2026-10-07 section above)

`BridgeTargetRoutingTest.java` contains three V36 regressions (written 2026-10-06 and unexecuted then; executed within the 9/9 result of 2026-10-06T18:26Z):
target precedence, async/global exclusion, and failure rejection/isolation. They
cover dispatch with a fake executor; they do not execute the actual plugin adapter.

1. Exact 2026.3 artifact: startup spawn callback runs on the region owning its configured
   location; reports the real biome with no violation/quarantine increment. Check a
   non-origin spawn, another world, negative coordinates, and an initially unloaded chunk.
2. Explicit RegionTask: target beats a different caller-region anchor and the `global`
   fallback setting; caller Location mutation leaves scheduling coordinates unchanged.
3. Explicit GlobalCallback and async submission: target resolver is never queried and
   native global/async ownership stays unchanged; unknown callbacks keep existing routing.
4. Wrong fingerprint/version, missing resource, inaccessible capture, null world, or an
   island subclass: reject without callback execution, orphaned task index or quarantine
   increment. Static no-capture settings Runnable and Consumer callbacks remain unaffected.
5. Cancel/disable before execution, late handles, repeating delay/period, admission limits,
   and region merge/split: preserve current bridge lifecycle semantics. Other plugins
   retain their admission/worker/cancellation behavior, including patch 0009 native disable.
6. Native chunk scheduling during startup and shutdown: verify owner admission works
   for the initially unloaded spawn. Keep retirement/refusal explicit if the world goes away.

Build/test/boot results must be recorded against the final tested snapshot. Prior
bridge or AWF passing suites do not validate this change. Runtime status: unit-level only, as of 2026-10-07 (see above).
