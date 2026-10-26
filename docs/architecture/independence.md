# SourbyCraft Independence Architecture

> **Role:** independence levels, rules and build work items. **Status:** Active for levels and rules; the §2 table is qualitative. The enforced ledger is [dependency-ledger.md](dependency-ledger.md); §2.1 and §2.2 re-counted against the pinned tests and the tree on 2026-10-25 (PR #62 docs pass); §9 B1 written the same day.
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
| Engine Spark platform (inherited from Canvas: `dev.iyanz.aurora.engine.spark`, `me/lucko` overrides) | Runtime integration | Region-aware Spark module/provider implementation | Partially | Move Sourby-specific metadata, metrics, and config behavior behind Sourby-owned integration points |
| Engine server/world config (`config/aurora-server.yml`, `config/aurora-worlds.yml`; the Canvas-era `canvas-*.yml` are migrated once since Build 49, section 5) | Runtime configuration | Region-threading engine settings | Yes | Engine configuration surface; new Sourby features use `sourbycraft_config/`; no conversion that changes values |
| Paper/Bukkit API | Runtime/API | Plugin ecosystem compatibility | Yes | Preserve intentional compatibility |
| Folia-derived threaded-region contracts | Runtime engine | Ownership/scheduling model | Internal + plugin-visible semantics | Preserve behavior while reducing direct Sourby coupling to concrete internals |
| Spark upstream library | Diagnostics | Profiler implementation/viewer ecosystem | Yes | Keep narrow integration until a deeper Sourby-specific fork is justified |
| SourbyClip | Bootstrap | Dependency resolution / launch | Yes as Sourby component | Audit and keep Sourby-owned |
| Metal | Build-time | Minecraft source/codegen support | No | Keep vendored/reproducible unless replaced by a better supported build path |
| SourbyPatcher `paper-toolchain` (private) | Build-time | The active 26.2 build requires it: `settings.gradle.kts` refuses to configure without the hash-verified jar/POM/`.module` (`patcherSha256`, ...); see `docs/development/PRIVATE-TOOLCHAIN.md`. Not "legacy" for this line. | No | Keep pinned; replace only by an owner decision (see 2.2) |

Update this table whenever a hard dependency is added, removed, or replaced.

## 2.1 Measured Canvas coupling

The table above is qualitative. This is the counted version, produced by
`scripts/independence_policy.py` and pinned by `scripts/test_independence_policy.py`
(recounted from the pinned sets on 2026-10-25).

| Surface | Count | What it is |
| --- | ---: | --- |
| Engine/Canvas symbols reached from SourbyCraft-owned code (`CanvasSourceCouplingTest.APPROVED`) | **4**, in **3** files | `GlobalConfiguration.reload()` and `WorldConfig.reload()` in `config/upstream/CanvasConfigBridge`; `SaveAllTicket` in `awf/world/AuroraWorldsService`; `SchedulerUtil` in `perf/LiveSchedulerSource` (read-only `/perf scheduler`). Was 7 until Build 48, when the plugin API types `WorldUnloadResult`, `WorldUnloadAsyncEvent` and `WorldRegionizer` moved from `io.canvasmc` to SourbyCraft's own `dev.iyanz.sourbycraft.api` and stopped counting as engine coupling (recounted 2026-10-07 at 5; was 2, both in `SourbyCraftConfig`) |
| Engine symbols added by feature patches (`PATCH_APPROVED`; `minecraft-patches/features` + `paper-patches/features`, scanned since 2026-10-20, audit OBS-7) | **3**, all in MC 0086 | `GlobalConfiguration.getInstance` (the cross-region portal loading-screen setting, read before the entity detaches) and `EntityRegionTransferState` / `EntityRegionTransferState.PluginTeleportAsyncState` (a cancelled legacy `PlayerTeleportEvent` clears the engine's plugin transfer state, mirroring the baseline's `EntityTeleportAsyncEvent` branch). Was 2 (Paper feature 0014's `WorldUnloadResult` constants) until Build 48, then 0 until MC 0086 (2026-10-22) |
| `io.canvasmc` strings anywhere | **0** | `CanvasPackageRemovedTest` fails on any `io.canvasmc` string in `sourbyapi/src`, `sourbycraft-server/src/main`, `src/log4jPlugins` or any patch tree, the engine baseline included (Build 48 removed the package) |
| Engine baseline (`minecraft-patches/sources`, `paper-patches/files`, former Canvas/Folia changes) | — | Excluded from the measure: since the 2026-10-05 migration this is the SourbyCraft-owned engine Aurora integrates with, not a dependency of it |

**SourbyCraft's own code barely touches the engine package.** The entire live coupling is two
static config-reload calls, the AWF world-lifecycle doorway in `AuroraWorldsService` and the
read-only `/perf scheduler` reader in `LiveSchedulerSource`: three files, the doorways
`UpstreamDependencyLedgerTest.canvasIsReachableOnlyThroughItsRecordedDoorways` pins. That is a
materially better position than the ledger above suggests on its own, and it means replacing the
engine package would not require rewriting SourbyCraft code.

What SourbyCraft *is* coupled to is the region-threading contract, which Canvas inherited
from the Folia lineage rather than originating:

| Namespace | Sites | Used for |
| --- | ---: | --- |
| `io.papermc.paper.threadedregions` (internal; `SchedulerCouplingTest.APPROVED`) | 59 non-comment lines in 7 files (2026-10-25) | Four adapters: `RegionizedServer`/`TickRegionScheduler` in `execution/region/FoliaRegionBackend` (15), `EntityScheduler` in `execution/RegionOwnerHandoff` (1), `RegionizedServer` in `awf/world/AuroraWorldsService` (5), and `RegionizedWorldData` with the region types in `compat/luminol/RegionizedWorldDataPool` (8) and its lease helper `WorldDataAccess` (4), the relaxed-thread-checks world-data pool (owner decision 2026-10-22; dead code while checks are enforced). Two read-only views: `ThreadedRegionizer`/`TickRegions`/`TickRegionScheduler`/`RegionizedTaskQueue`/`RegionizedServer` in `perf/LiveSchedulerSource` (23) and `ThreadedRegionizer`/`TickRegions` in `perf/RegionLocator` (3), for `/perf scheduler` and `/perf region <world> <x> <z>`. History: 4 sites in `PerformanceCollector`, `HudBars`, `AsyncPathCompletion`; 11 sites in 3 files on 2026-10-07 |
| `ca.spottedleaf` (`common.time`, `moonrise`, `concurrentutil`) | 10 files | Not counted by the policy script; each file is pinned by `UpstreamDependencyLedgerTest` ([dependency-ledger.md](dependency-ledger.md) §1.2). `common.time` `TickData`/`TickTime` in `perf/RegionTickMetrics`, `RegionTickMetricsHolder`, `RegionTickDataView`; Moonrise `MoonriseRegionFileIO` in `perf/RegionIoQueue` and `awf/world/AuroraWorldsService` (flush), `RegionDataController.ReadData`/`WriteData` in `awf/RegionFileStorageHooks`, `TickThread` in `execution/region/MoonriseRegionOwnership` and `compat/luminol/RegionizedWorldDataPool` (with `CoordinateUtils`, `ChunkHolderManager`, `ChunkSystemTicketType`), the `MoonriseCommon` pools in `execution/region/FoliaRegionBackend`; concurrentutil `Priority` (`MoonriseRegionOwnership`), `SchedulableTick` (`LiveSchedulerSource`), `BalancedPrioritisedThreadPool` (`FoliaRegionBackend`), `ConcurrentChainedLong2ReferenceHashTable` (`RegionizedWorldDataPool`) |

So the real independence question is not "how do we stop depending on Canvas" — that
dependency is already three files wide — but "what region-threading contract do we depend
on, and is it stable across engines". That is section 7's minimal scheduler contract, and
it is where the effort belongs.

Both counts are pinned. New engine coupling fails `test_independence_policy.py` until it
is added to the approved set with a reason, and a patch adding a live engine call fails
until it is recorded in `PATCH_APPROVED` with a reason (today: the three MC 0086 symbols
above). The scheduler scan likewise covers both patch directories. The recorded patch sites are
Paper 0005 and Paper 0019 (`RegionizedServer.getGlobalTickData` for `getTPS` and
`getAverageTickTime` off any region), Paper 0014 and MC 0062 (`RegionizedServer.getInstance` for
the global-tick spawn stages and the player-collision team), MC 0082
(`TickRegionScheduler.getCurrentRegionizedWorldData`, the path-type cache guard split from 0006),
and the relaxed-thread-checks patches MC 0084, MC 0085 and Paper 0029 (the global-tick synchronous
chunk load, the world-data leases, `isGlobalTickThread`). Comments naming a Canvas type are excluded from both — a Javadoc line
explaining which engine class a bridge talks to is not coupling, and the one apparent
patch-side call turned out to be a comment recording a call that had been removed.

## 2.2 Remaining hard Canvas-named surfaces

Everything below was verified against the tree on 2026-10-20 and re-checked on 2026-10-25 (Build 50
candidate, PR #62). "Hard" means removing or renaming it breaks
existing plugins, existing servers' data, or the engine itself. None of it is described as removed. The
engine baseline is SourbyCraft-owned (section 2.1); these are names and contracts that still say Canvas.

| Surface | Where | Why it stays | Compatibility effect of renaming |
| --- | --- | --- | --- |
| Plugin API package (21 files): `Unsupported`, `WorldUnloadResult`, `event.*`, `event.world.WorldUnloadAsyncEvent`, `region.*`, `simd.*` | `sourbyapi/src/main/java/dev/iyanz/sourbycraft/api` | **Renamed in Build 48** (owner decision 2026-10-21, "canvasmc dihapus total, tidak ada shim tipis"): the package moved from `io.canvasmc.canvas` to `dev.iyanz.sourbycraft.api`, with no compatibility shim. The Bukkit entry points (`World.getRegionizer()`, `Server.unloadWorldAsync`, `createRegionizedData`, `getLocalRegionizedData`, `getRegionThreadingTickRateManager`, `MapPalette` SIMD path, `@Unsupported`) keep their names and now use the new types; the engine baseline names them by simple name and three SourbyCraft feature patches (MC 0059, Paper 0018, API 0001) supply the imports. | **Deliberate binary break** for every plugin compiled against the old package: such a plugin fails to load or link (`NoClassDefFoundError`) until rebuilt against `sourbyapi` Build 48. The old-to-new class table is in `sourbyapi/README.md`. |
| Engine config file names `config/canvas-server.yml`, `config/canvas-worlds.yml` | `aurora/engine/GlobalConfiguration`, `WorldConfig`, `aurora/engine/configuration/EngineConfigFiles` | **Renamed in Build 49** to `config/aurora-server.yml` / `config/aurora-worlds.yml` (owner decision 2026-10-22, reversing the 2026-10-21 keep). `EngineConfigFiles.migrate` copies an old file under the new name once (values and per-key comments kept, new header), renames the old one `*.migrated` and logs one INFO; with both present the new file wins and one WARN names the stale old file; a corrupt old file fails the load naming both paths and writes nothing. The per-world `canvas-patch.yml` keeps its name (dimension storage directory, separate plan). | None for a migrated server; a downgrade to a pre-49 jar reads `canvas-*.yml` again, so copy `aurora-*.yml` over those names first (docs/AURORA-CONFIG.md). |
| Plugin descriptor key `canvas-supported` (read beside `folia-supported`) | `startup/PluginDescriptor:160-171`, `startup/StartupIndexStage:51` (message text), Paper feature 0024 (refusal line) | Plugins in the wild declare it. | Plugins that only declare it would be treated as not region-safe. |
| Engine member names `canvas$...` / `canvasConfig()` / `reloadCanvasConfig`, class name `CanvasRegionizedWorldData` (357 `canvas$` occurrences in 56 files of the engine baseline patches and engine code on 2026-10-25; a few feature patches and owned files name them too, e.g. `canvas$saveAllTicket` in `awf/world/AuroraWorldsService`) | `minecraft-patches/sources`, `paper-patches/files`, `aurora/engine` | Engine-internal. Renaming is churn across the engine baseline that `build-data/engine-baseline.sha256` would show as a reviewed diff, with no behaviour change. | None for plugins; high patch churn and rebase risk. |
| Environment variable `CANVAS_TEST_STRICT` set for the server tests | `sourbycraft-server/build.gradle.kts.patch:198`, read by `VanillaFixesConfigurationTest` | Test-only switch for the engine's strict guard mode. | Test configuration only. |
| ~~CI/release branch name `release/26.2-canvas`~~ — **retired** (owner decision 2026-10-21) | `.github/workflows/build.yml` publishes from `26.2` (comment at line 209); `AGENTS.md` records the retirement | No longer a surface: the publish job runs for `26.2` (and `hotfix/26.2-r<N>.<M>` branches) while `release=true\|pre` and no `v26.2-r<sourbyBuild>` tag exists. | None. |

Not hard, listed so nobody re-audits them: the `/canvas` command tree is not registered (feature patch 0020);
public build identity carries no Canvas suffix from Build 47 (`BuildInfo` only mentions the old `c` suffix in
comments and strips it from legacy values); Canvas is no longer an upstream or toolchain input (section 2).

Renamed in Build 48 (owner decision 2026-10-21, "replace every remaining user/plugin-visible Canvas
identifier"; the old names were read with a deprecation warning through Build 49). On the Build 50
candidate (PR #62) the old names that nothing persists are no longer read; the readers of persisted
data stay until owner decision PM-179. Code written and statically reviewed; not built; not tested by
the owner. The published `v26.2-r50` prerelease predates PR #62 and still reads every old name. Schedule
and evidence: [26.2-build-50-aurora-nexus.md, *Canvas-era name removal*](../releases/26.2-build-50-aurora-nexus.md#canvas-era-name-removal-deprecation-schedule).

| Was | Now | Old name (Build 50 candidate) |
| --- | --- | --- |
| Permission `canvas.broadcasting.receiver` | `aurora.broadcasting.receiver` (`aurora/engine/BroadcastPermission`) | grants nothing; an explicit grant logs one WARN per player naming the new node |
| JVM flags `-DCanvas.AggressiveChunkSystem`, `-DCanvas.DisableRegionProfiler`, `-DCanvas.isRunningInIDE` | `-DAurora.AggressiveChunkSystem`, `-DAurora.DisableRegionProfiler`, `-DAurora.isRunningInIDE` (`aurora/engine/util/LegacyNames`, engine baseline) | ignored; one WARN `JVM flag -DCanvas.… is ignored since Build 50` per name (`LegacyNames.REMOVAL_BUILD`) |
| Saved data `canvas:pearls` (`data/canvas/pearls.dat`) | `aurora:pearls` (`data/aurora/pearls.dat`) | **kept** (persisted, PM-179): moved once at world load by `util/migration/PearlsMigration`; a leftover old file beside a new one is renamed `pearls.dat.superseded`, never deleted |
| `paper:level_overrides` key `Canvas.Distance` | `Aurora.Distance` (`PerWorldDistanceConfig.LEVEL_OVERRIDES_FIELD`) | **kept** (persisted, PM-179): read when the new key is absent, with a one-time deprecation line; with neither key (a Paper-made world) the world has no override, no error; the next save writes only the new key |
| Ticket types `canvas:end_credits`, `canvas:region_profiler_hold` | `aurora:end_credits`, `aurora:region_profiler_hold` | none needed: not persisted |
| `ServerBuildInfo.BRAND_CANVAS_ID` (`canvasmc:canvas`), fallback brand name `Canvas` | `BRAND_SOURBYCRAFT_ID` (`sourbycraft:sourbycraft`), fallback `SourbyCraft` (sourbyapi feature 0002, Paper feature 0020) | **removed, no shim** (breaking API change, `sourbyapi/README.md`) |
| bStats service `Canvas`, version `Canvas-V3-…`, chart `canvas_version` | `SourbyCraft`, `SourbyCraft-…`, `sourbycraft_version` (Paper feature 0020) | none: nothing is submitted (Paper feature 0001) |
| `CanvasVersionFetcher` (commit link to CraftCanvasMC/Canvas) | `AuroraVersionFetcher`, links to `github.com/YanIanZ/SourbyCraft` | none (internal class) |
| `CanvasFileMigration` log lines "Canvas filesave features" | `AuroraFileMigration`, "Aurora engine save files" | none |
| Spark config group `canvas/` (`CanvasSplitParser`) | `aurora/` (`AuroraSplitParser`); the entries inside are the real file names (`aurora-server.yml`, `aurora-worlds.yml` since Build 49, per-world `canvas-patch.yml`) | a Spark viewer comparing reports across Build 48/49 sees the renamed entries |
| GUI icon `canvas-logo.png` (Canvas logo) | `sourbycraft-logo.png` (from `.github/assets/SourbyCraft.png`, MC feature 0069) | none |
| Dormant `/canvas` help dialog and subcommands (`aurora/engine/commands/*`, `aurora/engine/subcommands/*`, permissions `canvas.command.*`, flag `Canvas.LegacyHelpCommand`) | deleted: never registered since feature 0020; vanilla/Paper `/help` unchanged | none |

Kept deliberately, not on the removal schedule: the per-world `canvas-patch.yml` name, the plugin.yml key
`canvas-supported`, and `META-INF/licenses/LICENSE_CANVAS` (attribution). The engine config files are **not**
kept under their Canvas names: `config/canvas-*.yml` became `config/aurora-*.yml` in Build 49 (row above); only
the one-time migration of the old files remains, pending PM-179.

Not verified here: whether any third-party plugin in the wild links the former Canvas API (it breaks on Build 48), and whether
any permission plugin holds `canvas.broadcasting.receiver` (it grants nothing on the Build 50 candidate); both are deployment
facts, not repository facts. Counts above come from `rg` on 2026-10-25, not from a pinned policy test;
`scripts/independence_policy.py` pins only the narrower set in section 2.1 (symbols reached from SourbyCraft-owned code and
from feature patches).

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

Rules (revised 2026-10-25 to match the migrations Build 49 ships; owner decision 2026-10-22):

- the engine's own configuration stays supported while the engine needs it; since Build 49 it lives in
  `config/aurora-server.yml` and `config/aurora-worlds.yml`
- a renamed or reformatted file is migrated **once, at boot, keeping every value**: the operator's
  original is always kept under a `*.migrated` name (renamed or copied), the migration is logged, and a file that cannot be parsed is left untouched and named (an unparseable
  `canvas-*.yml` fails the engine config load naming both paths; an unparseable TOML file is named with
  its near-line and the settings fall back to defaults).
  Implemented: `config/canvas-*.yml` → `config/aurora-*.yml` (`aurora/engine/configuration/EngineConfigFiles`;
  values and per-key comments copied, one INFO; both present = the new file wins and one WARN names the old
  one), `sourbycraft_config/*.toml` → `*.yml` (`config/ConfigFormatMigration`; a `*.yml` always wins), and the
  pre-49 key names rewritten in place (`config/ConfigMigration`; copy kept as `*.migrated`; on the Build 50
  candidate, PR #62, a leftover old name is ignored with one WARN instead of being read)
- no migration that changes an operator's **value**; renaming a key or a file happens only in the form
  above and by owner decision
- new Sourby features should not add engine (Canvas-era) config keys unless they genuinely belong to the
  region-threading engine
- profiling metadata should display Sourby-owned config under a clear `sourbycraft/` group
- sensitive values must be filtered from reports

Current layout (Build 49+, both SourbyCraft files YAML; [AURORA-CONFIG.md](../AURORA-CONFIG.md)):

```text
sourbycraft_config/
├── sourbycraft_global_config.yml   (console, players, messages, plugins, updater)
└── aurora.yml                      (aurora.<service> sections)
config/
├── aurora-server.yml, aurora-worlds.yml   (region-threading engine)
└── paper-global.yml                        (threaded-regions sizing)
sourbycraft-security.yml                    (crash-prevention and packet-guard limits)
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

Status (2026-10-25, from `.github/workflows/build.yml`, `settings.gradle.kts`, root `build.gradle.kts`,
`gradle.properties` and `scripts/private_toolchain.py`; a static reading, nothing was run). The CI
`build` job runs the steps in this order; a local build runs steps 1–7 the same way.

| # | Step | Owner | Pin |
| --- | --- | --- | --- |
| 1 | Gradle wrapper and JDK: Gradle 9.8.0; Temurin 25 through Gradle toolchains and the foojay resolver (CI: `actions/setup-java`, 25.0.4+1) | Gradle, Eclipse Temurin (upstream tools) | `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` (since PR #62) and CI wrapper validation; foojay plugin 1.0.0 in `settings.gradle.kts`; CI actions pinned by commit SHA |
| 2 | Private toolchain: `scripts/private_toolchain.py --publish` tests SourbyPatcher and SourbyClip at the locked revisions and publishes them to Maven Local (CI checks them out with deploy keys) | SourbyCraft, private repositories `YanIanZ/SourbyPatcher` and `YanIanZ/SourbyClip` ([PRIVATE-TOOLCHAIN.md](../development/PRIVATE-TOOLCHAIN.md)) | revisions in `build-data/private-toolchain.lock.json`; `patcherSha256`, `patcherPomSha256`, `patcherModuleSha256`, `clipSha256` in `gradle.properties` |
| 3 | Gradle settings: `settings.gradle.kts` refuses to configure unless the paper-toolchain jar, POM and module metadata match their hashes, then applies `dev.iyanz.sourbypatcher.paper` and `io.papermc.paperweight.core` | SourbyPatcher (SourbyCraft, private) wrapping paperweight (PaperMC) | `patcherVersion=3.0.0`; `paperweightVersion=2.0.0-beta.24`, which `private_toolchain.py` requires to equal the version the pinned POM declares |
| 4 | Metal code generation: `bash scripts/setup_metal.sh` runs `Metal/gen_sources.sh` | Metal, vendored in this repository (upstream LuminolMC/Metal is offline) | the vendored sources in `Metal/` |
| 5 | `applyAllPatches`: paperweight materializes Paper, then applies the engine baseline (`minecraft-patches/sources`, `paper-patches/files`, `sourbyapi/paper-patches/files`) and the SourbyCraft feature patches | Paper (the only upstream source input); baseline and feature patches are SourbyCraft-owned since 2026-10-05 | `paperRef=9240f586…` in `gradle.properties`; baseline pinned by `build-data/engine-baseline.sha256` (`scripts/baseline_policy.py`) |
| 6 | Compile and test: `:sourbyapi:test :sourbycraft-server:test :test-plugin:test`, then the Python `scripts/test_*.py` suite and the policy checks | SourbyCraft sources (`dev.iyanz.*`, the engine package) on the materialized Paper/Minecraft tree | dependency versions in the Gradle build files; the Python checks pin their own manifests |
| 7 | `slimServerJar`: paperweight's paperclip jar, then SourbyCraft's task in root `build.gradle.kts` strips 19 library directories that SourbyClip re-downloads by coordinate on first boot | paperweight (paperclip packaging); SourbyCraft (strip task); SourbyClip (launcher, SourbyCraft, private) | `clipVersion=3.1.3` / `clipSha256`; the `externalizeArtifactDirs` list fails the build when it strips 0 |
| 8 | Build identity and SBOM: `writeBuildInfo`, `scripts/verify_build_identity.py` (provenance hashes against the checkout), `scripts/sbom.py` (CycloneDX) | SourbyCraft | `sourbyBuild`, `release` in `gradle.properties`; `SOURCE_DATE_EPOCH` set to the commit time |
| 9 | Boot tests and Docker image check (same job) | SourbyCraft CI | `eclipse-temurin:25-jre` base image pinned by digest in `docker/Dockerfile` |
| 10 | `rebuild` job: a fresh checkout rebuilds the slim jar and compares it with the first build | SourbyCraft CI | as steps 1–7 |
| 11 | `release` job (pushes and dispatches on `26.2` or a hotfix branch, `release=true\|pre`, tag not yet published): checks the handed-over jar is the reproduced one, signs it (Ed25519, when the signing key secret is present), attests provenance and creates the GitHub release | SourbyCraft CI on GitHub Actions | actions pinned by commit SHA; tag `v26.2-r<sourbyBuild>` |

Upstream-derived steps: 1 (tools), 3 and 7 (paperweight inside the SourbyCraft adapter), 4 (vendored
Metal) and the Paper source input of step 5. Everything else is SourbyCraft-owned, including the two
private build tools; none of them needs a Canvas artifact.

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

Keep the engine config (`config/aurora-*.yml`, formerly Canvas's) as the engine's own layer while Sourby feature config remains Sourby-owned (`sourbycraft_config/`).

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
Aurora owns execution contracts, while the inherited (Folia-lineage) region scheduler remains the backend.
[Execution contract extraction](execution-contract.md) maps the concrete boundaries and the acceptance
tests. Stage status, as stated in [AURORA.md *Current state*](AURORA.md): Stage 1 (contract extraction)
implemented; Stage 2 (Aurora-owned dispatch) partial; Stage 3 (backend isolation) implemented for
SourbyCraft's own sources, where the internal scheduler is named only in the seven pinned files of §2.1;
Stage 4 partial; Stage 5 (scheduler replacement) planned, not started.
