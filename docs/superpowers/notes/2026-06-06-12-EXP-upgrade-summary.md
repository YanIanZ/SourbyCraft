# SourbyCraft 12-EXP — Upgrade Summary

**Build:** 12-EXP (was 12-REL)
**Date:** 2026-06-06
**Jar size:** 33M (was 57M, -42%)
**Branch:** `feat/pvp-server`
**Commit range:** `c4f41a1..HEAD`

---

## At a glance

3 mega-projects shipped + 1 in-progress + 1 polish:

| Project | Status | Spec | Plan | Impl commits |
|---|---|---|---|---|
| Perf-engine P0 — Knob Registry | ✓ shipped | `2026-06-05-perf-engine-P0-knob-registry-design.md` | `2026-06-05-perf-engine-P0-knob-registry.md` | `e8b57ff` → `d9aef05` |
| Perf-engine P1 — Load Sensor + Tier | ✓ shipped | `2026-06-05-perf-engine-P1-load-sensor-tier-design.md` | `2026-06-05-perf-engine-P1-load-sensor-tier.md` | `99e3094` → `1d11c30` |
| Sourby Bootstrap — Slim jar + lazy lib downloader | ✓ shipped | `2026-06-06-sourby-bootstrap-design.md` | `2026-06-06-sourby-bootstrap.md` | `eacceb6` → `fa77c8f` |
| Perf-engine P2 — Lag-Machine Protection | spec drafted | `2026-06-06-perf-engine-P2-lag-machine-design.md` | (TBD) | (TBD) |
| Version rename + EXP suffix | ✓ shipped | (chore) | (chore) | `798ed16`, `c1ea6c4` |

---

## Perf-engine P0 — Knob Registry (shipped)

**Goal:** Typed registry for runtime-tunable performance knobs. Foundation for P7 controller.

**Ships:**
- New package `dev.iyanz.sourbycraft.perf.knob`:
  - `PerfKnob` (sealed abstract)
  - `BoolKnob` (volatile boolean + clamp-on-set no-op since bool has no range)
  - `IntKnob` (volatile int + clamp + `WARN-once` per (key, direction))
  - `KnobRegistry` (`ConcurrentHashMap`, snapshot, `logLoaded(context)` boot summary)
  - `Knobs` (public static holder, single declaration site)
- First reference knob: `Knobs.ENTITY_TICK_RATE` (default 20, range 1..20)
- `SourbyCraftConfig.entityTickRate` migrated from `public static volatile int` field to `public static int entityTickRate()` method backed by `Knobs.ENTITY_TICK_RATE.get()`
- All 6 caller sites updated (StartupOptimizer, DynamicPerformanceScaler, PerfCommand)
- Operator-yml bridge: existing Bukkit-config `entity.tick-rate` reads route through `Knobs.ENTITY_TICK_RATE.set(...)` so operator customization is preserved
- `KnobRegistry.logLoaded("boot")` emits one INFO line at end of `init()` listing every knob's resolved value
- Boot-smoke harness `test-harness/scripts/p0-knob-smoke.sh` + `:p0KnobSmokeTest` gradle task (6 scenarios: default / in-range / clamp-hi / clamp-lo / wrong-type / boot-sanity)
- CI gate in `.github/workflows/nms-compat.yml` runs the smoke on PRs touching the knob package

**Hot-path cost:** `Knobs.X.get()` = 1 volatile load (~1ns). JIT inlines through `public static final` Knobs holder. Bool-cache pattern documented for hot loops.

**Defaults:** all defaults match Paper vanilla (no behavior change on first boot).

## Perf-engine P1 — Load Sensor + Tier Classifier (shipped)

**Goal:** Multi-signal load sensor + 5-tier state machine. Feeds the future P7 controller.

**Ships:**
- New package `dev.iyanz.sourbycraft.perf.sensor`:
  - `Tier` enum: GREEN, YELLOW, ORANGE, RED, EMERGENCY (worst = higher ordinal)
  - `SensorSnapshot` record: immutable point-in-time reading of all 4 signals + tier + dwell state
  - `PerfSensor` static utility: tick entry, signal readers, classifier, hysteresis
- 4 signals: TPS rolling (1s / 1m / 5m), MSPT, mem%, GC pause ms/min (60-second ring)
- Cadence: every 20 ticks (1s at 20 TPS), configurable via `perf.sensor.cadence-ticks`
- Dwell + band hysteresis:
  - Escalation requires `dwell-samples` (default 3) consecutive samples in candidate tier
  - Recovery requires `dwell-samples × recovery-dwell-multiplier` (default 2.0 → 6 samples)
  - Bias toward acting on protection earlier than relaxing
- Warmup guard: `warmup-ticks` (default 200) skipped at startup so JVM warmup doesn't fire false tier transitions
- yml-tunable thresholds per tier per signal under `perf.sensor.thresholds.<signal>.<tier>`
- Threshold monotonicity validation: WARN + revert to defaults if operator misconfigures (`mspt.yellow: 100, mspt.orange: 50` → "non-monotonic, reverting")
- NMS hook in `MinecraftServer.tickChildren` (sibling to existing BossBarTicker hook, wrapped in `try/catch` so never fails the game loop)
- `PerfSensor.loadFromYml()` wired into `SourbyCraftConfig.init()` after `Knobs.loadFromYml()`
- Operator yml bridge: `applyOperatorConfig(...)` with 21 explicit args (8 knobs + cadence + dwell + multiplier + warmup + 4×4 threshold matrix)
- Public pull-only API: `currentTier()`, `snapshot()`, `isEnabled()`, `timeInTierNanos()`, `thresholdsFor(signal)`
- `/perf tier` and `/perf sensors` Brigadier subcommands (added to existing `PerfCommand`)
- `Knobs.logLoaded()` overload: now takes a context label (e.g. `"boot"`, future `"tier-transition"`) so P7+ can distinguish

**Out of scope for P1:** Knob delta application (P7 controller wires sensor → knob writes for all sub-projects).

**Smoke harness landed then deleted** per user directive ("hapus semua test, testnya lgsg dengan run servernya"). New project policy: SourbyCraft sub-specs verify by operator booting `test-harness/TestServer-mojmap/` manually.

## Sourby Bootstrap — Slim jar + lazy lib downloader (shipped)

**Goal:** Reduce release jar from ~57M to ~30M target by externalizing optional libs to first-boot download with SHA-256 verification.

**Ships:**
- New package `dev.iyanz.sourbycraft.bootstrap`:
  - `BootstrapManifest` (record + nested `Entry` record)
  - `Sha256Verifier` (package-private)
  - `LibDownloader` (package-private; JDK HttpClient, atomic-move, hash verify)
  - `SourbyBootstrap` (public; jar Main-Class, reads `META-INF/sourby-bootstrap-manifest.json` from own jar)
- New root gradle task `createSlimPaperclipJar`:
  - Reads fat paperclip output
  - Strips externalized lib entries from `META-INF/libraries/<path>`
  - Keeps `META-INF/libraries.list` intact (paperclip's cache-hit logic adds pre-downloaded libs to classpath via SHA match)
  - Embeds `META-INF/sourby-bootstrap-manifest.json` with `{paperclipPath, downloadUrl, sha256, sizeBytes}` per entry
  - Rewrites `META-INF/MANIFEST.MF` `Main-Class` to `dev.iyanz.sourbycraft.bootstrap.SourbyBootstrap`
  - Copies bootstrap `.class` files from `sourbycraft-server.jar` to the slim jar root (they normally live inside the patched server jar; need to be JVM-bootstrappable from the outer jar)
- `assembleReleaseArtifacts` rewired to consume the slim jar
- 6 libs externalized (~28M total cut): sqlite-jdbc, mysql-connector-j, spark-paper, Flare, protobuf-java, sentry, parchment-data (deferred — upstream 404)
- 1 lazy binary: Ookla speedtest CLI (multi-OS download on first `/speedtest`)
- `SpeedtestCommand` rewritten for multi-OS detection (Linux x86-64/aarch64/armhf, macOS universal, Windows x64/arm64); tarball/zip extraction in-process via JDK `GZIPInputStream` + minimal tar reader
- Hard-fail on offline first boot: prints actionable diagnostic + every URL + every destination path under `libraries/`, exits 3
- Subsequent boots are silent cache-hit (zero downloads, baseline boot time)
- README + RELEASE-NOTES updated with First-Boot section

**Result:** jar 57M → 33M (-42%). Hit the original ~30M spec target.

**Key discovery:** Paper's "spark bundled" detection works via classpath presence (`me.lucko.spark.paper.api.PaperClassLookup` must be loadable), not via `META-INF/libraries/<path>` bytes being in the outer jar. paperclip's `FileEntry.extractFile` cache-hit logic correctly adds pre-downloaded libs to the URL classloader when SHA-256 matches. Earlier failure during multi-lib test was caused by stale `libraries/` state from a crashed boot, not by Paper special-casing.

## Perf-engine P2 — Lag-Machine Protection (spec drafted)

**Spec at:** `docs/superpowers/specs/2026-06-06-perf-engine-P2-lag-machine-design.md`

**Pending implementation.** 8 new Knobs:
- Snowball NBT save skip (default ON, UniverseSpigot recommended)
- Firework NBT save skip (default ON)
- Projectile chunk-load throttle: per-tick + per-projectile counters (default 10/10)
- Excess minecart removal on collision (default OFF; toggle + limit)
- Excess boat removal on collision (default OFF; toggle + limit)

5 NMS patches (one per protection) + 1 helper class `LagMachineCounters` for the per-tick counter.

Plan + implementation deferred until user signals.

## Version + chore (shipped)

- `v12-REL` → `12-REL` (drop `v` prefix from `internalVersion` in `gradle.properties`). Commit `798ed16`. Affects jar filename + banner string + 3 smoke scripts + BuildInfoTest + README badges + RELEASE-NOTES.
- `12-REL` → `12-EXP` (codename `rel` → `exp` per gradle.properties edit). Commit `c1ea6c4`. Build artifact now `SourbyCraft-12-EXP.jar`.

## Memory / policy notes (saved across sessions)

- **`feedback-no-junit-for-sourbycraft`** — SourbyCraft sub-specs do NOT add JUnit unit tests. Verification = real-server boot.
- **`feedback-no-smoke-harness`** — Supersedes JUnit feedback for NEW work. Operator boots TestServer manually; no automated test surface added for NEW sub-specs. Pre-existing harnesses (p0-knob-smoke, particle-smoke, nms-compat) stay.
- **`sourbycraft-perf-engine-roadmap`** — 9 sub-projects. P0 ✓ P1 ✓ P2 (spec). Ship order: P0 → P1 → P2 → P3 → P7-skeleton → P4 → P5 → P6 → P7-full → P8.
- **`sourby-bootstrap-blocked` → fully shipped** — partial-shipment memory entry now flipped to "fully shipped" after final pass externalized all 6 libs.
- **`proxy-support-deferred`** — BungeeCord/Waterfall/XCord/Velocity compat mega-project. Not started.

## Commits since `c4f41a1` (32 total)

```
c1ea6c4 fix build version (12-REL → 12-EXP)
fa77c8f feat: sourby-bootstrap full — externalize 6 libs (jar 57M → 33M, -42%)
14df175 docs: sourby-bootstrap — First Boot section + RELEASE-NOTES migration note
7bc8654 feat: sourby-bootstrap — lazy speedtest binary download
615845e feat: sourby-bootstrap — slim release (~41M) via sqlite+mysql lazy download
13f2471 revert: sourby-bootstrap — release stays fat (slim jar blocked on paperclip integration)
ed31f35 build: sourby-bootstrap — createSlimPaperclipJar gradle task
28c3a75 feat: sourby-bootstrap — SourbyBootstrap main shim with manifest parse + delegate
7ed6d4a feat: sourby-bootstrap — manifest + sha256 + downloader utilities
eacceb6 plan: sourby-bootstrap — slim jar + lazy lib downloader (6 tasks)
3a1585f docs: sourby-bootstrap — slim jar + first-boot lib downloader design spec
798ed16 chore: drop 'v' prefix from internalVersion (v12-REL → 12-REL)
1d11c30 chore: perf-engine P1 — delete smoke harness per user directive
0c93fbf feat: perf-engine P1 — /perf tier + /perf sensors subcommands + SCENARIO_7
855112f feat: perf-engine P1 Task 3 — PerfSensor + yml wiring + NMS tick hook + SCENARIO_1/2
e3dc715 feat: perf-engine P1 — Tier enum + SensorSnapshot record
041a1db fix: perf-engine P1 — boot_and_assert kills server before return + RCON heredoc safe
9f07f8e test: perf-engine P1 — tier smoke skeleton (boot sanity scenario)
99e3094 plan: perf-engine P1 — Load Sensor + Tier impl plan (6 tasks)
ce03ff6 docs: perf-engine P1 — Load Sensor + Tier classifier design spec
d9aef05 polish: perf-engine P0 — final-review followups
073a3ba ci: gate smoke-step uploads on specific step failures
c4d9d3a ci: gate perf-engine P0 knob smoke on changed paths
92fd38c polish: perf-engine P0 — exit-code map + tighten smoke assertion regexes
d543086 test: perf-engine P0 — clamp + wrong-type smoke scenarios
c74b1f4 polish: perf-engine P0 — context label on logLoaded + explicit smoke guard
03da87c refactor: migrate entityTickRate field → Knobs.ENTITY_TICK_RATE getter
c81e2bd docs: perf-engine P0 — accurate yml + smoke comments per Task 2 review
8e6eb6b feat: perf-engine P0 — Knob abstraction + ENTITY_TICK_RATE + yml load
79113d8 test: perf-engine P0 — smoke harness skeleton (boot sanity scenario)
e8b57ff plan: perf-engine P0 — Knob Registry impl plan (5 tasks)
c4f41a1 docs: perf-engine P0 — Knob Registry design spec
```

## Verified at 12-EXP

- Build: `BUILD SUCCESSFUL in 47s` → `release/SourbyCraft-12-EXP.jar` (33M)
- First boot (empty `libraries/`): downloads 6 libs (23M) in ~4s, `Done (22.7s)` total
- Boot banner: `SourbyCraft 12-EXP Saturday, 6 June 2026 for Minecraft 1.21.11`
- `[spark] This server bundles the spark profiler.` log line — spark on classpath via paperclip cache-hit
- `server.properties` auto-generated with `allow-nether=true` (vanilla Paper default, present from boot 1)

## Next on roadmap

- **P2 Lag-Machine Protection** — spec written; awaits plan + implementation
- **P3 Adaptive Entity AI** — tier-aware DAB, dynamic-brain throttle
- **Proxy support mega-project** — BungeeCord/Waterfall/XCord/Velocity audit
