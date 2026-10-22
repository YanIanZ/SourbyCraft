# FastAsyncWorldEdit and `MinecraftServer.currentTick` (2026-10-22)

## Finding

Sourby Demo panel, Epic Skyblock v9.7 on v26.2-r48.1 (boot log of the opus-panel-epicskyblock run):
FastAsyncWorldEdit 2.15.4 enabled, then WorldGuard 7.0.19 failed in `onEnable` with

```
java.lang.NoSuchFieldError: Class net.minecraft.server.MinecraftServer does not have member field 'int currentTick'
  at ...fawe.v26_2.PaperweightFaweWorldNativeAccess.<init>(PaperweightFaweWorldNativeAccess.java:64)
  at ...fawe.v26_2.PaperweightFaweAdapter.createWorldNativeAccess(PaperweightFaweAdapter.java:342)
  at com.sk89q.worldedit.bukkit.BukkitWorld.<init>(BukkitWorld.java:142)
  ...
  at com.sk89q.worldguard.bukkit.BukkitConfigurationManager.postLoad(BukkitConfigurationManager.java:80)
```

and Jobs was disabled because the WorldGuard classes it needs were missing.

## What FAWE reads (bytecode only, nothing executed)

`com.sk89q.worldedit.bukkit.adapter.impl.fawe.v26_2.PaperweightFaweWorldNativeAccess`, inspected with `javap -c -p`
in `FastAsyncWorldEdit-Paper-2.15.4.jar` (the panel's) and `FastAsyncWorldEdit-Paper-2.16.1.jar` (latest release,
2026-10-09, SHA-256 `a1706c41…1838`):

- a direct field access, `getstatic net/minecraft/server/MinecraftServer.currentTick:I` (no reflection, no getter);
- in the constructor (stored in `AtomicInteger lastTick`) and at the top of `synchronized setBlockState(LevelChunk, BlockPos, BlockState)`;
- threads: the constructor runs wherever a `BukkitWorld` is created (WorldGuard's enable on the server thread during
  startup, later any thread); `setBlockState` reads it before its `Fawe.isMainThread()` branch, so also from FAWE's
  async edit threads. Off the main thread FAWE only compares the value with `lastTick` to decide when to flush its
  cached changes (or after 1024 of them); it needs a counter that moves forward, not a precise tick.

2.16.1's `v26_2` adapter has the same two reads (every adapter from `v1_21_R1` to `v26_3` does), so **updating FAWE
does not fix it**; FAWE declares no `folia-supported` and does not support region threading.

## Decision

Paper's field is restored by MC feature patch 0077 (`restore MinecraftServer.currentTick as a global-tick counter`):

- `public static volatile int currentTick` on `MinecraftServer` (Paper's name and descriptor, so `getstatic` links);
  the baseline's commented-out Paper line is left as is.
- `bridge/LegacyServerTick.globalTick()` is called once per global tick at the start of the global tick handle's
  `tickRegion` (before its tasks, as Paper incremented at tick start). The global tick thread is the only writer.
- Semantics: **a global-tick counter, not a region tick.** 0 during startup, +1 per global tick, wraps like Paper's
  `int`. From region or async threads it is only approximately current, because the global tick and the regions do
  not tick in lockstep. It is not `Bukkit.getCurrentTick()` (the current region's tick) and must not be compared with
  it or used as game time.
- No server code reads it: Paper's former readers (`Connection` packet flush, `ServerPlayerGameMode.gameTicks`) stay
  commented out in the baseline. The patch adds no guard, so the inherited-guard ledger is unchanged.

Tests: `LegacyServerTickTest` (field linkage public static volatile int, +1 per call, int wrap, patch shape: one
writer, before `drainTasks`, nothing removed).

## Evidence

Agent test server run `opus-panel-fixes-fawe-2` (jar from branch `fix/legacy-current-tick`, `aurora.bridge.mode =
"safe"`, `--large-plugin-jar`), FastAsyncWorldEdit 2.15.4 + WorldGuard 7.0.19 from the panel setup: FAWE enabled,
WorldGuard enabled and loaded its configuration for `world`, `world_nether`, `world_the_end` (the same
`postLoad -> BukkitWorld.<init>` path that failed), no `NoSuchFieldError`, `Done (9.762s)`, `stop` exit 0. FAWE's
async edit path (`setBlockState` off the main thread) was not exercised; FAWE stays a bridged, unqualified plugin.

Side finding (not fixed here): the bridge pre-scan logs `Plugin WorldGuard depends on missing plugin(s) WorldEdit;
the plugin manager will not load it` although FAWE `provides` WorldEdit and WorldGuard then loads; the check ignores
`provides`.
