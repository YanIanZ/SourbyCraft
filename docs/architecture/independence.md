# SourbyCraft Independence Architecture

> **Role:** independence levels, rules and build work items. **Status:** Active for levels and rules; the §2 table is qualitative and partly predates the 2026-10-05 migration. The enforced ledger is [dependency-ledger.md](dependency-ledger.md); §2.1 counts corrected on 2026-10-07.
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

## Scope

This document tracks the technical path from the current Paper/Canvas-derived implementation to an independently owned SourbyCraft runtime.

Independence does not mean removing every upstream line of code. It means SourbyCraft can build, boot, run, profile, diagnose, configure its own features, and release under its own runtime contract without requiring another server distribution to be installed or another project to remain available at runtime.

Upstream attribution and license obligations remain mandatory.

---

# 1. Independence Levels

## Level 0 — Branding only

Not sufficient.

Changing names, banners, command output, or package labels without changing ownership does not make SourbyCraft independent.

## Level 1 — Runtime identity

SourbyCraft owns:

- public platform name
- build identity
- release identity
- `/version`
- profiler platform metadata

Canvas/Paper revisions may still be exposed as upstream/debug metadata.

## Level 2 — Runtime services

SourbyCraft owns lifecycle-managed implementations for:

- telemetry
- diagnostics
- HUD
- administrative async I/O
- Sourby-specific configuration
- health evaluation
- performance incident tracking

Upstream classes should call narrow integration hooks.

## Level 3 — Engine adapters

Hard upstream dependencies are isolated behind Sourby-owned adapters/interfaces when that materially reduces patch coupling.

Examples:

```text
SourbyRegionAccess
SourbySchedulerAccess
SourbyWorldMetricsSource
SourbyNetworkMetricsSource
```

Do not create abstraction layers merely for architectural appearance. Each adapter must reduce a real dependency or simplify testing/rebase work.

## Level 4 — Build independence

The SourbyCraft repository can reproducibly produce the server artifact from pinned source inputs using its own documented build.

No separate Canvas server binary is required.

Upstream repositories may still be fetched as source inputs if pinned and reproducible.

## Level 5 — Replaceable upstream implementation

SourbyCraft-specific services are sufficiently isolated that selected upstream engine components can be rebased, replaced, or rewritten without changing SourbyCraft's public runtime contract.

This is the long-term target, not a requirement to rewrite every subsystem immediately.

---

# 2. Current Dependency Ledger

| Dependency | Type | Why it exists | Public? | Target |
| --- | --- | --- | --- | --- |
| Paper upstream patch topology (paperweight via SourbyPatcher `paper-toolchain`) | Build-time | Materializes Paper → SourbyCraft. Canvas and Weaver were removed as upstream/toolchain on 2026-10-05 | No | Keep reproducible |
| Region-threading engine (inherited from Canvas/Folia, SourbyCraft-owned since 2026-10-05) | Runtime engine | Region scheduler and region ownership foundation; engine baseline patches plus `dev.iyanz.aurora.engine` sources | Indirect | Wrap only where Sourby needs stable ownership boundaries; do not rewrite without evidence |
| Canvas Spark integration classes | Runtime integration | Region-aware Spark module/provider implementation | Partially | Move Sourby-specific metadata, metrics, and config behavior behind Sourby-owned integration points |
| Canvas server/world config | Runtime configuration | Upstream engine settings | Yes today | Treat as compatibility surface; migrate new Sourby features to Sourby-owned config, avoid destructive conversion |
| Paper/Bukkit API | Runtime/API | Plugin ecosystem compatibility | Yes | Preserve intentional compatibility |
| Folia-derived threaded-region contracts | Runtime engine | Ownership/scheduling model | Internal + plugin-visible semantics | Preserve behavior while reducing direct Sourby coupling to concrete internals |
| Spark upstream library | Diagnostics | Profiler implementation/viewer ecosystem | Yes | Keep narrow integration until a deeper Sourby-specific fork is justified |
| SourbyClip | Bootstrap | Dependency resolution / launch | Yes as Sourby component | Audit and keep Sourby-owned |
| Metal | Build-time | Minecraft source/codegen support | No | Keep vendored/reproducible unless replaced by a better supported build path |
| SourbyPatcher `paper-toolchain` (private) | Build-time | The active 26.2 build requires it: `settings.gradle.kts` refuses to configure without the hash-verified jar/POM/`.module` (`patcherSha256`, ...); see `docs/development/PRIVATE-TOOLCHAIN.md`. Not "legacy" for this line. | No | Keep pinned; replace only by an owner decision (see 2.2) |

Update this table whenever a hard dependency is added, removed, or replaced.

## 2.1 Measured Canvas coupling

The table above is qualitative. This is the counted version, produced by
`scripts/independence_policy.py` and pinned by `test_independence_policy.py`.

| Surface | Count | What it is |
| --- | ---: | --- |
| Engine/Canvas symbols reached from SourbyCraft-owned code | **4** | `GlobalConfiguration.reload()` and `WorldConfig.reload()` in `config/upstream/CanvasConfigBridge`; `SaveAllTicket` in `awf/world/AuroraWorldsService`; `SchedulerUtil` in `perf/LiveSchedulerSource` (read-only `/perf scheduler`). Was 7 until Build 48, when the plugin API types `WorldUnloadResult`, `WorldUnloadAsyncEvent` and `WorldRegionizer` moved from `io.canvasmc` to SourbyCraft's own `dev.iyanz.sourbycraft.api` and stopped counting as engine coupling (recounted 2026-10-07 at 5; was 2, both in `SourbyCraftConfig`) |
| `io.canvasmc` calls added by feature patches (`minecraft-patches/features` + `paper-patches/features`, scanned since 2026-10-20, audit OBS-7) | **0** | Was 2 (Paper feature 0014 returning `WorldUnloadResult.SUCCESS` / `FAIL_UNKNOWN`) until Build 48 removed the `io.canvasmc` package; `PATCH_APPROVED` is empty and `CanvasPackageRemovedTest` in `scripts/test_independence_policy.py` fails on any `io.canvasmc` string in `sourbyapi/src`, `sourbycraft-server/src/main`, `src/log4jPlugins` or any patch tree, the engine baseline included |
| Engine baseline (`minecraft-patches/sources`, former Canvas sources) | — | Excluded from the measure: since the 2026-10-05 migration this is the SourbyCraft-owned engine Aurora integrates with, not a dependency of it |

**SourbyCraft's own code barely touches Canvas.** The entire live coupling is two static
config-reload calls and the AWF world-lifecycle doorway in `AuroraWorldsService` (2026-10-07). That is a materially better position than the ledger above suggests
on its own, and it means replacing Canvas would not require rewriting SourbyCraft code.

What SourbyCraft *is* coupled to is the region-threading contract, which Canvas inherits
from the Folia lineage rather than originating:

| Namespace | Sites | Used for |
| --- | ---: | --- |
| `io.papermc.paper.threadedregions` (internal) | 11 | in three adapter files plus two read-only diagnostics views: `RegionizedServer`/`TickRegionScheduler` in `execution/region/FoliaRegionBackend`, `EntityScheduler` in `execution/RegionOwnerHandoff`, `RegionizedServer` in `awf/world/AuroraWorldsService`; `ThreadedRegionizer`/`TickRegions` (and the scheduler classes) in `perf/LiveSchedulerSource` and `perf/RegionLocator` for `/perf scheduler` and `/perf region <world> <x> <z>` (added 2026-10-20; site count not recounted; recounted 2026-10-07; was 4 sites in `PerformanceCollector`, `HudBars`, `AsyncPathCompletion`) |
| `ca.spottedleaf.common.time` | 2 | `TickData` and `TickTime` in `RegionTickMetrics` and its holder |

So the real independence question is not "how do we stop depending on Canvas" — that
dependency is already two files wide — but "what region-threading contract do we depend
on, and is it stable across engines". That is section 7's minimal scheduler contract, and
it is where the effort belongs.

Both counts are pinned. New Canvas coupling fails `test_independence_policy.py` until it
is added to the approved set with a reason, and a patch adding a live Canvas call fails
until it is recorded in `PATCH_APPROVED` with a reason (today: the two `WorldUnloadResult`
constants above). The scheduler scan likewise covers both patch directories: Paper 0005
(`RegionizedServer.getGlobalTickData` for `getTPS` off any region) and Paper 0014
(`RegionizedServer.getInstance` for the global-tick spawn stages) are the recorded patch sites
beside MC 0006's `getCurrentRegionizedWorldData`. Comments naming a Canvas type are excluded from both — a Javadoc line
explaining which engine class a bridge talks to is not coupling, and the one apparent
patch-side call turned out to be a comment recording a call that had been removed.

## 2.2 Remaining hard Canvas-named surfaces

Everything below was verified against the tree on 2026-10-20. "Hard" means removing or renaming it breaks
existing plugins, existing servers' data, or the engine itself. None of it is described as removed. The
engine baseline is SourbyCraft-owned (section 2.1); these are names and contracts that still say Canvas.

| Surface | Where | Why it stays | Compatibility effect of renaming |
| --- | --- | --- | --- |
| Plugin API package (21 files): `Unsupported`, `WorldUnloadResult`, `event.*`, `event.world.WorldUnloadAsyncEvent`, `region.*`, `simd.*` | `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api` | **Renamed in Build 48** (owner decision 2026-10-21, "canvasmc dihapus total, tidak ada shim tipis"): the package moved from `io.canvasmc.canvas` to `dev.iyanz.sourbycraft.api`, with no compatibility shim. The Bukkit entry points (`World.getRegionizer()`, `Server.unloadWorldAsync`, `createRegionizedData`, `getLocalRegionizedData`, `getRegionThreadingTickRateManager`, `MapPalette` SIMD path, `@Unsupported`) keep their names and now use the new types; the engine baseline names them by simple name and three SourbyCraft feature patches (MC 0059, Paper 0018, API 0001) supply the imports. | **Deliberate binary break** for every plugin compiled against the old package: such a plugin fails to load or link (`NoClassDefFoundError`) until rebuilt against `sourbyapi` Build 48. The old-to-new class table is in `sourbyapi/README.md`. |
| Engine config file names `config/canvas-server.yml`, `config/canvas-worlds.yml`, per-world `canvas-patch.yml` | `aurora/engine/GlobalConfiguration`, `WorldConfig` | Kept deliberately (owner decision 2026-10-21): existing servers keep their settings. The files say so in their own header ("historical path is retained"). | Silent loss of operator settings unless a migration is written. SourbyCraft must not rewrite them on its own (section 4). |
| Plugin descriptor key `canvas-supported` (read beside `folia-supported`) | `startup/StartupIndexStage:56`, `SourbyCraftConfig:323`, `startup/PluginDescriptor` | Plugins in the wild declare it. | Plugins that only declare it would be treated as not region-safe. |
| Engine member names `canvas$...` / `canvasConfig()` / `reloadCanvasConfig`, class name `CanvasRegionizedWorldData` (about 136 `canvas$` occurrences in 34 files of the patch sources and engine code) | `minecraft-patches/sources`, `aurora/engine` | Engine-internal. Renaming is churn across the engine baseline that `build-data/engine-baseline.sha256` would show as a reviewed diff, with no behaviour change. | None for plugins; high patch churn and rebase risk. |
| Environment variable `CANVAS_TEST_STRICT` set for the server tests | `sourbycraft-server/build.gradle.kts.patch:192` | Test-only switch for the engine's strict guard mode. | Test configuration only. |
| CI/release branch name `release/26.2-canvas` | `.github/workflows/build.yml` (`on.push.branches`, release gating), `AGENTS.md` | The publish job is gated on this ref name. | Changing it changes who publishes; a CI and owner decision. |

Not hard, listed so nobody re-audits them: the `/canvas` command tree is not registered (feature patch 0020);
public build identity carries no Canvas suffix from Build 47 (`BuildInfo` only mentions the old `c` suffix in
comments and strips it from legacy values); Canvas is no longer an upstream or toolchain input (section 2).

Renamed in Build 48 (owner decision 2026-10-21, "replace every remaining user/plugin-visible Canvas
identifier"; old names still read until Build 50, see `docs/releases/26.2-build-50.md`):

| Was | Now | Old name |
| --- | --- | --- |
| Permission `canvas.broadcasting.receiver` | `aurora.broadcasting.receiver` (`aurora/engine/BroadcastPermission`) | still receives when a permission plugin sets it explicitly; one deprecation line per player |
| JVM flags `-DCanvas.AggressiveChunkSystem`, `-DCanvas.DisableRegionProfiler`, `-DCanvas.isRunningInIDE` | `-DAurora.AggressiveChunkSystem`, `-DAurora.DisableRegionProfiler`, `-DAurora.isRunningInIDE` (`aurora/engine/util/LegacyNames`, engine baseline) | still read when the new flag is absent; one deprecation line |
| Saved data `canvas:pearls` (`data/canvas/pearls.dat`) | `aurora:pearls` (`data/aurora/pearls.dat`) | moved once at world load by `util/migration/PearlsMigration`; a leftover old file beside a new one is renamed `pearls.dat.superseded`, never deleted |
| `paper:level_overrides` key `Canvas.Distance` | `Aurora.Distance` (`PerWorldDistanceConfig.LEVEL_OVERRIDES_FIELD`) | read when the new key is absent; with neither key (a Paper-made world) the world has no override, no error; the next save writes only the new key |
| Ticket types `canvas:end_credits`, `canvas:region_profiler_hold` | `aurora:end_credits`, `aurora:region_profiler_hold` | none needed: not persisted |
| `ServerBuildInfo.BRAND_CANVAS_ID` (`canvasmc:canvas`), fallback brand name `Canvas` | `BRAND_SOURBYCRAFT_ID` (`sourbycraft:sourbycraft`), fallback `SourbyCraft` (sourbyapi feature 0002, Paper feature 0020) | **removed, no shim** (breaking API change, `sourbyapi/README.md`) |
| bStats service `Canvas`, version `Canvas-V3-…`, chart `canvas_version` | `SourbyCraft`, `SourbyCraft-…`, `sourbycraft_version` (Paper feature 0020) | none: nothing is submitted (Paper feature 0001) |
| `CanvasVersionFetcher` (commit link to CraftCanvasMC/Canvas) | `AuroraVersionFetcher`, links to `github.com/YanIanZ/SourbyCraft` | none (internal class) |
| `CanvasFileMigration` log lines "Canvas filesave features" | `AuroraFileMigration`, "Aurora engine save files" | none |
| Spark config group `canvas/` (`CanvasSplitParser`) | `aurora/` (`AuroraSplitParser`); the entries inside keep the real file names (`canvas-server.yml`, ...) | none |
| GUI icon `canvas-logo.png` (Canvas logo) | `sourbycraft-logo.png` (from `.github/assets/SourbyCraft.png`, MC feature 0069) | none |
| Dormant `/canvas` help dialog and subcommands (`aurora/engine/commands/*`, `aurora/engine/subcommands/*`, permissions `canvas.command.*`, flag `Canvas.LegacyHelpCommand`) | deleted: never registered since feature 0020; vanilla/Paper `/help` unchanged | none |

Kept deliberately: the `config/canvas-*.yml` file names, the plugin.yml key `canvas-supported`, and
`META-INF/licenses/LICENSE_CANVAS` (attribution).

Not verified here: whether any third-party plugin in the wild links the former Canvas API (it breaks on Build 48), and whether
any permission plugin holds `canvas.broadcasting.receiver`; both are deployment facts, not repository facts. Counts above come
from `rg` on 2026-10-20, not from a pinned policy test; `scripts/independence_policy.py` pins only the narrower
set in section 2.1 (symbols reached from SourbyCraft-owned code and from feature patches).

---

# 3. Runtime Independence Rules

A production SourbyCraft process must not require:

- a separate Canvas JAR
- a separately running Canvas process
- a Canvas remote API
- Canvas network availability after local bootstrap is complete
- automatic upstream configuration downloads

Optional updater/profiler uploads are separate operator-controlled features and must not be required for core runtime operation.

---

# 4. Source Ownership Boundary

Sourby-owned implementation belongs under Sourby-owned source whenever practical.

Preferred:

```text
upstream patch: 5–30 lines
       ↓
call Sourby-owned service
       ↓
dev.iyanz.sourbycraft.* implementation
```

Avoid embedding hundreds of lines of Sourby logic into a Canvas/Paper class when the same behavior can live behind a narrow hook.

Benefits:

- smaller rebases
- clearer ownership
- simpler tests
- easier replacement of upstream internals
- fewer patch conflicts

---

# 5. Configuration Independence

SourbyCraft configuration should gradually become the authoritative surface for Sourby-specific features.

Rules:

- existing Canvas configuration remains supported while required by the engine
- SourbyCraft must not silently rewrite Canvas files into Sourby files
- no auto-migration that changes operator values without explicit action
- new Sourby features should not add new Canvas-specific config keys unless they genuinely belong to the upstream engine
- profiling metadata should display Sourby-owned config under a clear `sourbycraft/` group
- sensitive values must be filtered from reports

Long-term desired layout may converge toward:

```text
sourbycraft_config/
├── sourbycraft_global_config.toml
├── performance.toml      (only if separation becomes useful)
├── network.toml          (only if Sourby owns those settings)
└── security.yml / equivalent
```

Do not split files merely for aesthetics.

---

# 6. Observability Independence

SourbyCraft should be the canonical source for its health information.

Target flow:

```text
engine counters / region counters / runtime sampler
                    ↓
             Sourby MetricsRuntime
                    ↓
       immutable PerformanceSnapshot
                    ↓
 /tps /mspt /ram /perf HUD Spark API
```

There must not be multiple independent definitions of TPS/MSPT across commands and profiler integrations.

Spark should consume Sourby measurements where the metric semantics overlap.

---

# 7. Scheduler Independence

Do not attempt to rewrite the region scheduler only to remove the Canvas name.

Instead:

1. document the scheduling contract SourbyCraft depends on
2. isolate Sourby-specific callbacks/metrics
3. eliminate unnecessary direct field access to concrete scheduler internals
4. create adapters only where repeated hard coupling exists
5. validate with region transition and ownership tests

Scheduler replacement becomes feasible only after the required contract is explicit.

---

# 8. Spark Independence

Current policy:

- keep upstream Spark implementation where it is reliable
- extend it with SourbyCraft metrics/config/platform integration
- do not duplicate profiler engines unnecessarily
- do not call the integration a standalone SourbySpark fork unless source ownership actually changes

A dedicated SourbySpark fork becomes justified if SourbyCraft needs capabilities such as:

- region-specific profile metadata unavailable through the adapter surface
- Sourby runtime event annotations
- Sourby-specific profiler viewer extensions
- plugin/region attribution that requires profiler-core modification

Until then, narrow integration has lower maintenance risk.

---

# 9. Build Independence Work Items

## B1 — Active build path inventory

Document every step required by:

```text
./gradlew applyAllPatches
compile
slimServerJar
release artifact
```

Mark each step Sourby-owned vs upstream-derived.

## B2 — Remove inactive active-line dependencies

Status (2026-10-20): the condition is not met. The active 26.2 build requires the private SourbyPatcher `paper-toolchain` (see the ledger in section 2), so there is no inactive dependency to remove. Revisit only if the owner decides to replace the patch applier; removing it would then need identical/valid release artifacts proven first.

## B3 — Pin upstream inputs

Every source dependency must use an explicit reproducible revision.

## B4 — Build without developer machine state

CI and a clean checkout must be able to build without:

- pre-existing local Maven artifacts except those intentionally vendored/published during the build
- generated directories left by a previous run
- manual IDE state

## B5 — Offline-after-bootstrap validation

After first dependency acquisition, validate that the built server can boot/run without upstream network access where dependencies are already cached.

---

# 10. Runtime-Service Migration Pattern

When moving functionality out of upstream classes:

1. identify current hook and ownership
2. write/retain regression tests
3. create Sourby-owned service/model
4. move implementation without semantic changes
5. reduce patch to integration hook
6. run compile + tests + boot
7. run targeted benchmark if the path is hot
8. only then optimize

Do not combine ownership migration and aggressive optimization in one unreviewable change unless unavoidable.

---

# 11. High-Priority Migration Candidates

Priority is based on existing Sourby ownership and rebase pressure.

## P1 — telemetry / profiler bridge

Already largely Sourby-owned. Continue reducing direct Canvas-specific metric logic.

## P2 — configuration bridge

Keep Canvas engine config as compatibility layer while Sourby feature config remains Sourby-owned.

## P3 — runtime lifecycle

Ensure Sourby-owned executors/services start and stop through one documented lifecycle.

## P4 — diagnostics / HUD

Keep fully Sourby-owned and upstream-agnostic except for metric inputs.

## P5 — performance integration hooks

Move optimization implementation bodies into Sourby classes when doing so does not add hot-path indirection or allocation.

---

# 12. What Not to Do

Do not:

- rename upstream packages wholesale
- copy entire Canvas subsystems into `dev.iyanz` only to claim ownership
- delete attribution/license headers
- rewrite the scheduler without a benchmark/correctness reason
- remove compatibility layers before replacements exist
- create wrappers for every upstream class
- introduce reflection to avoid compile-time dependencies where a stable direct API is safer
- make a server slower or less stable simply to reduce references to Canvas

Independence must improve maintainability or runtime ownership, not merely increase code volume.

---

# 13. Independence Acceptance Tests

At minimum:

- clean checkout builds `26.2`
- produced JAR boots under Java 25
- no separate Canvas JAR is installed
- SourbyCraft identifies itself correctly
- `/tps`, `/mspt`, `/perf` function without a standalone profiling plugin
- Spark metadata reports SourbyCraft correctly when Spark integration is active
- SourbyCraft config appears in profiler configuration metadata with sensitive values filtered
- server can boot from local cached dependencies while external upstream access is unavailable
- shutdown leaves no Sourby-owned non-daemon worker behind
- world reload/restart persistence remains correct

---

# 14. Independence Metrics

Track progress with engineering metrics rather than branding counts.

Useful measures:

- number of large (>100-line) Sourby patches inside upstream classes
- number of Sourby runtime services living in Sourby-owned source
- number of direct Canvas-specific accesses from Sourby-owned packages
- active build steps requiring legacy tooling
- number of public commands/configs still branded as upstream rather than SourbyCraft
- rebase conflict count per upstream bump

The target is decreasing hard coupling and conflict cost over time.

---

# 15. Completion Condition

SourbyCraft can be considered independently owned when:

- its public runtime contract is Sourby-defined
- its performance/diagnostic system is Sourby-defined
- its Sourby features use Sourby-owned configuration
- its build is reproducible from its repository
- no second server distribution is required to run it
- upstream components can be updated without forcing a redesign of Sourby public behavior
- remaining hard upstream dependencies are explicit, intentional, and replaceable in principle

The desired end state is not “zero upstream code.”

The desired end state is:

> SourbyCraft controls its own behavior, lifecycle, diagnostics, releases, and feature architecture while using upstream components as implementation inputs rather than as its identity.

## Independent-engine continuation

The active direction is [AURORA-INDEPENDENT-ENGINE.md](AURORA-INDEPENDENT-ENGINE.md):
Aurora owns execution contracts, while current Folia/Canvas scheduling can remain a backend.
[Execution contract extraction](execution-contract.md) maps the first concrete boundaries,
records owner-identity/stale-result gaps in async path completion, and defines acceptance tests
before adding an Aurora dispatcher. This is contract extraction, not completed scheduler isolation.
