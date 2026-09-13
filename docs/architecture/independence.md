# SourbyCraft Independence Architecture

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
| Canvas Weaver/upstream patch topology | Build-time | Materializes Paper → Canvas → SourbyCraft source stack | No | Isolate and keep reproducible; replace only when a Sourby-owned build path is proven |
| Canvas region-threading implementation | Runtime engine | Region scheduler and region ownership foundation | Indirect | Wrap only where Sourby needs stable ownership boundaries; do not rewrite without evidence |
| Canvas Spark integration classes | Runtime integration | Region-aware Spark module/provider implementation | Partially | Move Sourby-specific metadata, metrics, and config behavior behind Sourby-owned integration points |
| Canvas server/world config | Runtime configuration | Upstream engine settings | Yes today | Treat as compatibility surface; migrate new Sourby features to Sourby-owned config, avoid destructive conversion |
| Paper/Bukkit API | Runtime/API | Plugin ecosystem compatibility | Yes | Preserve intentional compatibility |
| Folia-derived threaded-region contracts | Runtime engine | Ownership/scheduling model | Internal + plugin-visible semantics | Preserve behavior while reducing direct Sourby coupling to concrete internals |
| Spark upstream library | Diagnostics | Profiler implementation/viewer ecosystem | Yes | Keep narrow integration until a deeper Sourby-specific fork is justified |
| SourbyClip | Bootstrap | Dependency resolution / launch | Yes as Sourby component | Audit and keep Sourby-owned |
| Metal | Build-time | Minecraft source/codegen support | No | Keep vendored/reproducible unless replaced by a better supported build path |
| legacy sourbypatcher | Build-time legacy | Historical Folia/release compatibility | No | Remove from active 26.2 line only after CI/release no longer depends on it |

Update this table whenever a hard dependency is added, removed, or replaced.

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

If `sourbypatcher` is not required by the active Canvas-based 26.2 build, remove or isolate CI steps that still build it only after proving release artifacts remain identical/valid.

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
