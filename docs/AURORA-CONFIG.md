# Aurora configuration — effective 26.2 contract

This document describes **what branch `26.2` actually consumes now**. It is not a future-layout proposal.

## Implemented Aurora settings

| Key | Type / default | Lifecycle | Consumer |
| --- | --- | --- | --- |
| `aurora.entity.async-pathfinding` | boolean / `false` | LIVE | `AsyncPathProcessor` admission |
| `aurora.diagnostics.lane-sampling` | boolean / `true` | LIVE | execution-lane CPU attribution |
| `aurora.cpu.cores` | integer / `0` (AUTO) | RESTART_REQUIRED | early region-scheduler CPU budget |
| `aurora.bridge.mode` | `off` / `safe`, default `off` | RESTART_REQUIRED | plugin loader admission via `AuroraBridge` (read from the TOML files directly, before `SourbyCraftBootstrap`) |
| `aurora.bridge.quarantine-after` | integer ≥ 1 / `3` | LIVE | Aurora Bridge quarantine threshold |
| `aurora.bridge.sync-route` | `caller-region` \| `global` / `caller-region` | LIVE | `BridgeRuntime.submit`, read per task |
| `aurora.scheduler.bridge-io-threads` | integer ≥ 0 / `0` (= max(2, processors/4)) | RESTART_REQUIRED | Resource Governor `BRIDGE_IO` lane |
| `aurora.scheduler.bridge-io-queue` | integer ≥ 1 / `256` | RESTART_REQUIRED | same |
| `aurora.scheduler.storage-threads` | integer ≥ 0 / `1` (0 = 1) | RESTART_REQUIRED | Resource Governor `STORAGE` lane |
| `aurora.scheduler.storage-queue` | integer ≥ 1 / `64` | RESTART_REQUIRED | same |
| `aurora.network.counters` | boolean / `true` | LIVE | `NetworkCounters` increments (`/perf network`) |
| `aurora.awf.worlds` | list of world folder names / `[]` | RESTART_REQUIRED | `AwfEngine.attach` from `RegionFileStorage`'s constructor; read from the files directly, not the config system |
| `aurora.awf.export` | list of world folder names / `[]` | RESTART_REQUIRED | export to region files at the next load, then retire the store |
| `aurora.awf.backend` | name / `file` | RESTART_REQUIRED | `AwfBackend` the stores live on; unregistered + listed world = load failure |
| `aurora.awf.persistence` | `incremental` \| `checkpoint` \| `full` / `incremental` | RESTART_REQUIRED | AWF commit mode |
| `aurora.awf.commit-interval-seconds` | 1–86400 / `30` | RESTART_REQUIRED | age at which the collector starts a commit |
| `aurora.awf.resident-chunks` | ≥ 1 / `1024` | RESTART_REQUIRED | clean chunks kept per storage |
| `aurora.awf.retained-generations` | ≥ 1 / `3` | RESTART_REQUIRED | generations kept for recovery |
| `aurora.awf.commit-attempts` | ≥ 1 / `3` | RESTART_REQUIRED | attempts before a commit fails |

**Hot-path lookup audit (2026-09-27).** Every remaining dotted-string lookup
(`SourbyCraftConfig.cfgGet/cfgBool/cfgInt`) runs at boot, at explicit reload, on operator commands, or on a join/leave
event (`SourbyMessages` variants), never per tick. Each is one `get` on the immutable
`ConfigSnapshot` map. Aurora settings are read once into the typed `AuroraConfig` and published
at load boundaries.

The typed runtime snapshot is owned by `AuroraConfig`. Tick code should consume typed values or already-published primitives rather than repeatedly parsing dotted configuration paths.

## Configuration files and boot order

Most SourbyCraft settings continue to live in:

```text
sourbycraft_config/sourbycraft_global_config.toml
```

The early region-thread decision is special. `AuroraCpu` currently reads:

```text
sourbycraft_config/aurora.toml
```

during Paper/Folia global configuration loading, **before** `SourbyCraftBootstrap.init()` and the normal typed config lifecycle. That separation is an implementation fact and must be documented until the boot order is redesigned.

Current precedence for region tick threads is:

1. explicit `threaded-regions.threads: N` in `paper-global.yml`;
2. `aurora.cpu.cores = N` from `sourbycraft_config/aurora.toml`;
3. AUTO: `Runtime.getRuntime().availableProcessors()`.

`aurora.cpu.cores` is clamped to the JVM-visible processor count. JVM-visible processors are preferred to host/NUMA totals so container CPU quotas are respected.

Example:

```toml
[aurora.cpu]
cores = 8
```

Because the scheduler is sized before normal SourbyCraft bootstrap, changing this value at runtime does **not** resize region tick threads. A restart is required.

## Live settings

Example logical values:

```toml
[aurora.entity]
async-pathfinding = false

[aurora.diagnostics]
lane-sampling = true
```

`/sourbycraft reload` may apply implemented LIVE Aurora settings. Reload output must explicitly separate live changes from restart-required values.

Disabling async pathfinding prevents new navigation checks from selecting async solves. Already admitted work may finish and return through the owning-entity handoff. Shutdown has stronger admission/cancellation behavior.

Async pathfinding remains **EXPERIMENTAL and default-off**. Functional tests or a successful boot do not make it qualified.

## Legacy compatibility

Where the branch still supports legacy keys such as `perf.ai.async-pathfinding`, the Aurora key has precedence when present. Invalid Aurora values must not silently fall through to a legacy value that changes operator intent.

Deprecation support is compatibility, not permission to document the legacy key as the preferred interface.

## Development rules for configuration claims

Documentation and release notes must distinguish:

- **implemented key** — parser + consumer exist;
- **live key** — changing it can affect the running server without restart;
- **restart-required key** — parser may reload it, but the active subsystem cannot;
- **planned namespace** — architecture only, not a usable setting.

Do not say a config file is the single source of truth if an early-boot consumer bypasses it. Do not say a reload "applied" a value whose consumer was already constructed and cannot change.

## Verification

Configuration tests validate parsing, precedence, lifecycle publication and compatibility behavior. They are correctness evidence only. They do not establish a performance improvement, soak stability, or release qualification.

When this document conflicts with code on branch `26.2`, verify the active consumer and boot order, then update this document in the same change.

## UI settings and reload presentation (2026-10-06)

The utility TOML owns `[ui]`; Aurora service settings stay in `aurora.toml`.

| Key | Default | Lifecycle |
| --- | --- | --- |
| `ui.console-style` | `"auto"` | RESTART_REQUIRED for startup output; `plain`/`rich` also supported, JVM property overrides |
| `ui.plugins-page-size` | `12` | LIVE after reload, clamped to 1–40 |

`/sourbycraft config` and `/aurora config` show loaded values and the file responsible for
those values. Bridge mode shows configured and active values separately. `/sourbycraft reload`
reports file-read/apply failures, invalid Aurora values and region-config reload failures.
It does not claim that every engine option changed immediately.

CPU, bridge admission, scheduler budgets and selected utility restart changes are compared
against the boot snapshot, so repeated reloads do not clear a still-pending restart requirement.
This comparison does not enumerate AWF or every historical region-engine option. AWF,
construction-cached engine options, startup provisioning, MOTD, login bypass and a scheduled
updater interval remain restart-required. Editing the saved max-player value requires restart;
`/maxp` itself changes the live slot count and persists it.

Existing utility and Aurora TOML files are never automatically reformatted by boot/reload. New utility files receive
annotated UI defaults and Aurora files receive their own annotated service defaults. To add the
new UI options to an existing server, merge [this section](config-examples/utility-ui.toml).
[The Aurora example](config-examples/aurora.toml) lists all implemented defaults, including AWF;
use it as a reference rather than replacing tuned operator settings.
