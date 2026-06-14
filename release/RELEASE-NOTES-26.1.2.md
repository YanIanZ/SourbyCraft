# SourbyCraft 26.1.2-EXP

Released: 2026-06-14 on branch `release/26.1.2`.

## Highlights

- **Paper 26.1.2 upstream** — year-based versioning (Minecraft 26.1.2 = same era as 1.21.11 codebase line, fully replaced).
- **Java 25** required (Paper 26.1.2 uses unnamed variables).
- **Bootstrap shipped** — lazy lib download keeps release jar at 31M (was 57M fat jar in 12-EXP era).
- **PvP variant removed** — single-jar general SMP baseline only.
- **Reobf pipeline dropped** — Paper 26.1.2 ships mojmap-only.
- **Spigot mapping support removed** upstream — no more CraftBukkit `v1_21_R7` package version; flattened to `org.bukkit.craftbukkit.*`.

## Compatibility breaks

- NMS plugins importing `org.bukkit.craftbukkit.v1_21_R7.*` will fail to load. They must update to flattened CB package.
- Plugins relying on the deleted SourbyCraft NMS hooks (Pufferfish DAB writes, wildstacker NMS-only mode, parallel tick router, BossBar ticker) will see no-op behavior — their config knobs are dead.
- PvP variant is gone — `variant=pvp` CLI flag is no longer recognized.

## SourbyCraft patches (5 minecraft + 2 api + 14 server buildscript + 2 api buildscript)

### Minecraft source patches

| # | Patch | Effect |
|---|-------|--------|
| 0001 | perf-engine P1 sensor tick hook | Multi-signal load sensor running every cadence on main thread; feeds 5-tier state machine (GREEN/YELLOW/ORANGE/RED/EMERGENCY) |
| 0002 | perf-engine P2 disable saving snowballs | NBT save skip for snowballs when knob is set — drops cost-zero lag-machine vector |
| 0003 | perf-engine P2 disable saving fireworks | NBT save skip for fireworks (same rationale) |
| 0004 | raise fast-drop kick threshold 20 to 200 | Ctrl+Q on full inventory no longer trips anti-hack disconnect on fast clients |
| 0005 | defer POI worldgen updates via scheduleOnMain | Moonrise treats chunk-gen workers as tick threads; `getServer().execute()` runs inline on worker and trips PoiManager's strict main-thread guard. Force-defer via `scheduleOnMain` |

### API patches (preserved from prior baseline)

- 0001 Setup gitignore
- 0002 Pufferfish-api-patches (test deps)
- 0003 Add CloudPlane configuration
- 0004 Changed branding

### Buildscript patches (server)

14 patches refreshed against Paper 26.1.2 paperweight beta.21:
- Drop `spigot { }` block (removed upstream)
- Drop `reobfPackagesToFix` (reobf dropped upstream)
- Drop all `runReobf*` task registrations
- Rename `includeMappings`/`createMojmap*` → `createPaperclipJar`/`createBundlerJar`/`tasks.jar`
- Bump mache → 26.1.2+build.3, paperclip → 3.0.4, fill plugin → 1.0.11
- Bump runServer Java launcher 21 → 25
- Pufferfish sourceset, branding manifest, version-format-EXP/REL/DEV logic, SWM compile-only deps, ByteBuddy test JVM args — all re-anchored

### Buildscript patches (api)

2 patches refreshed:
- Pufferfish-API-Buildscript-Patches re-anchored on upstream's now-current versions (junit 6.0.3, mockito 5.22.0, asm 9.9.1)
- Setup-paperweight-fork-changes byte-identical after rebuild

## Source code cleanup (src/main/java/dev/iyanz/sourbycraft)

### Deleted (orphaned by bulk-patch removal)

- `async/` — AsyncWorkerPool, CircuitBreaker, Watchdog, DimensionThreadManager, PoolMetrics
- `tick/` — BatchPhysicsTicker(s), EntityTickRouter, EntityTickClassifier, EntityClass, ItemEntityPhysics, ParallelTickDiff/Snapshot
- `pool/` — Vec3Pool, ItemStackPool, ObjectPool
- `io/` — ChunkSaveBatcher, ChunkSaveSnapshot, ChunkSaveDiff, RegionIoPool
- `item/` — ItemEntityPool
- `wildstacker/` — WildstackerManager
- `optimizer/` — MemoryOptimizer
- `mod/` — ModScanner
- `combat/` — ReachTracker (PvP)
- `command/` — ReachCommand, WildstackerDebugCommand
- `perf/` — PerfCommand, PerfV9Subcommand, DynamicPerformanceScaler, StartupOptimizer, HealthMonitor, LagMachineCounters

Total: 53 files, ~3235 lines.

### Preserved

- `perf/sensor/` + `perf/knob/` — wired by patches 0001-0003
- `bootstrap/` — slim jar boot wiring (lazy lib download)
- `brand/` — startup banner, plugin log, gc advisor (PvP framing stripped)
- `command/` (non-PvP) — remaining subcommands
- `install/` — plugin auto-installer
- `security/`
- `swm/` — Slime World Manager bridge ported to Paper 26.1.2 NMS API
- `util/`

## Known issues / dropped patches

Group A (perf-engine):
- 0048 P2 projectile chunk-load throttle — `LagMachineCounters` dropped + blob mismatch
- 0049 P2 remove excess minecarts — blob mismatch
- 0050 P2 remove excess boats — blob mismatch

Group B (general optimization):
- 0003 Optimise non-flush packet sending — netty `AbstractChannelHandlerContext.safeExecute` reflection conflicts with Paper 26.1.2 netty internals
- 0018-0019 Improve-Player-canSee — requires CraftPlayer.canSee(NMS Entity) overload which was deleted
- 0020 Keep-track-of-brain-behaviors-directly — 194-line Brain.java structural change conflicts
- 0021 Convert-spawn-category-limits-and-ticks-to-array — Paper 26.1.2 still uses Object2LongOpenHashMap
- 0008-0017 (Player events, item lore, locale resend, redstone desync fix, etc.) — blob mismatch; each needs individual re-anchoring

Group C (Pufferfish): not attempted. Whole Pufferfish patchset would need re-derivation against new mob brain / activation range / SIMD detection APIs. `DabState` stub remains in place to preserve `gg.pufferfish.pufferfish.PufferfishConfig.setEnabled` config writes (reads now no-op).

## Build

```bash
./gradlew assembleReleaseArtifacts
# -> release/SourbyCraft-26.1.2-EXP.jar (31M)
```

Bootstrap rebuilds slim jar with lazy lib manifest. First-boot downloads:
- sqlite-jdbc, mysql-connector-j (JDBC drivers, ~17M combined)
- spark-paper (profiler)
- Flare (Pufferfish flare deps)
- sentry (crash reporting)
- speedtest binary (post-boot perf scan)

## Verification

User boots TestServer manually (per project memory — no JUnit, no smoke harness). Expected banner:

```
Loading SourbyCraft 26.1.2-EXP <day, dd Month YYYY> for Minecraft 26.1.2
```
