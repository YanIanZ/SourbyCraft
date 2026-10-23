# Aurora configuration — effective 26.2 contract

> **Role:** effective Aurora configuration contract — keys, defaults, LIVE/RESTART_REQUIRED, boot order. **Status:** Active; checked against `config/AuroraConfig.java` and `awf/AwfSettings.java` on 2026-10-07 (two bridge task-limit keys are not listed yet).
>
> Entry point: [docs/architecture/AURORA.md](architecture/AURORA.md).

This document describes **what branch `26.2` actually consumes now**. It is not a future-layout proposal.

## Implemented Aurora settings

| Key | Type / default | Lifecycle | Consumer |
| --- | --- | --- | --- |
| `aurora.entity.async-pathfinding` | boolean / `false` | LIVE | `AsyncPathProcessor` admission |
| `aurora.diagnostics.lane-sampling` | boolean / `true` | LIVE | execution-lane CPU attribution |
| `aurora.diagnostics.chunk-generation-metrics` | boolean / `false` | RESTART_REQUIRED | generic chunk-generation stage timing behind `/perf chunks`; read once from the TOML at class init (before configuration loads); also on when `-Dsourbycraft.chunk-generation-metrics.enabled=true`; an invalid value is reported and stays off |
| `aurora.diagnostics.stall-recorder.enabled` | boolean / `true` | RESTART_REQUIRED | `StallRecorder` (`diagnostics/`): started by `SourbyCraftBootstrap` after the configuration stage; `false` starts no thread and registers no shutdown hook. An invalid value is reported and keeps the default (`true`). The four keys below share the lifecycle: they are read once at boot, a reload republishes `AuroraConfig` but never reaches the running recorder, and `/sourbycraft reload` lists them as restart-pending ([development/STALL-RECORDER.md](development/STALL-RECORDER.md)) |
| `aurora.diagnostics.stall-recorder.after-seconds` | integer 1–3600 / `10` | RESTART_REQUIRED | seconds the global tick, or any active region, may go without completing a tick before the first dump; invalid keeps `10` |
| `aurora.diagnostics.stall-recorder.repeat-seconds` | integer 1–3600 / `10` | RESTART_REQUIRED | seconds between dumps while the same stall lasts |
| `aurora.diagnostics.stall-recorder.max-dumps-per-stall` | integer 1–100 / `6` | RESTART_REQUIRED | dumps per stall; one more is written if the JVM shuts down during the stall |
| `aurora.diagnostics.stall-recorder.keep-files` | integer 1–1000 / `20` | RESTART_REQUIRED | newest `logs/stalls/stall-*.txt` kept; older ones are deleted when a new stall file is created |
| `aurora.cpu.cores` | integer / `0` (AUTO) | RESTART_REQUIRED | early region-scheduler CPU budget |
| `aurora.bridge.mode` | `off` / `safe`, default `off` | RESTART_REQUIRED | plugin loader admission via `AuroraBridge` (read from the TOML files directly, before `SourbyCraftBootstrap`) |
| `aurora.bridge.quarantine-after` | integer ≥ 1 / `3` | LIVE | Aurora Bridge quarantine threshold |
| `aurora.bridge.sync-route` | `caller-region` \| `global` / `caller-region` | LIVE | `BridgeRuntime.submit`, read per task |
| `aurora.bridge.thread-checks` | `relaxed` \| `bridged` \| `enforced` / `"relaxed"` (owner decision 2026-10-22) | RESTART_REQUIRED, read early (`guards/ThreadChecks` class init; `-Daurora.bridge.thread-checks=` overrides); effective only while `mode = safe` | what a failed region-ownership check does ([aurora-plugin-bridge.md, Thread checks](architecture/aurora-plugin-bridge.md#thread-checks-owner-decision-2026-10-22)); relaxed = unsafe, unqualified |
| `aurora.scheduler.bridge-io-threads` | integer ≥ 0 / `0` (= max(2, processors/4)) | RESTART_REQUIRED | Resource Governor `BRIDGE_IO` lane |
| `aurora.scheduler.bridge-io-queue` | integer ≥ 1 / `256` | RESTART_REQUIRED | same |
| `aurora.scheduler.storage-threads` | integer ≥ 0 / `1` (0 = 1) | RESTART_REQUIRED | Resource Governor `STORAGE` lane |
| `aurora.scheduler.storage-queue` | integer ≥ 1 / `64` | RESTART_REQUIRED | same |
| `aurora.network.counters` | boolean / `true` | LIVE | `NetworkCounters` increments (`/perf network`) |
| `aurora.world.command-blocks` | boolean / `false` | LIVE (caveat: a repeating block whose tick was dropped while off stays idle until re-armed by redstone, re-placement, GUI save or chunk reload) | `CommandBlockPolicy`, read on every command-block tick, GUI open and edit packet; `false` keeps the inherited hard-disable exactly; `true` runs command blocks/minecarts under SourbyCraft's region rules ([command-blocks.md](architecture/command-blocks.md)); the vanilla gamerule `command_blocks_work` (default `true`; 26.2 has no `enable-command-block` property) must also be `true`; an invalid value is reported and stays off |
| `aurora.world.tick-functions` | boolean / `false` | LIVE (read every global tick; switching on never re-runs `#minecraft:load`) | `DatapackFunctions.globalTick`: `true` runs the datapack tag `#minecraft:tick` once per server tick on the global tick with the server's game-loop source; `false` never looks the tag up. `#minecraft:load` runs once per loaded function library regardless of this key ([datapack-functions.md](architecture/datapack-functions.md)); an invalid value is reported and stays off |
| `aurora.awf.worlds` | list of world folder names / `[]` | RESTART_REQUIRED | `AwfEngine.attach` from `RegionFileStorage`'s constructor; read from the files directly, not the config system |
| `aurora.awf.export` | list of world folder names / `[]` | RESTART_REQUIRED | export to region files at the next load, then retire the store |
| `aurora.awf.backend` | name / `file` | RESTART_REQUIRED | `AwfBackend` the stores live on; unregistered + listed world = load failure |
| `aurora.awf.persistence` | `incremental` \| `checkpoint` \| `full` / `incremental` | RESTART_REQUIRED | AWF commit mode |
| `aurora.awf.commit-interval-seconds` | 1–86400 / `30` | RESTART_REQUIRED | age at which the collector starts a commit |
| `aurora.awf.resident-chunks` | ≥ 1 / `1024` | RESTART_REQUIRED | clean chunks kept per storage |
| `aurora.awf.retained-generations` | ≥ 1 / `3` | RESTART_REQUIRED | generations kept for recovery |
| `aurora.awf.commit-attempts` | ≥ 1 / `3` | RESTART_REQUIRED | attempts before a commit fails |
| `aurora.awf.commit-dirty-bytes` | 0 to 2147483647 / `0` (off) | RESTART_REQUIRED | start a commit once a storage's unsaved chunks hold this many bytes, before the interval; a trigger, not a cap |
| `aurora.awf.shutdown-deadline-ms` | 1 to 2147483647 / `30000` | RESTART_REQUIRED | `AwfSettings.readEarly`, used by `AwfEngine.beginShutdown` (called from `SourbyCraftBootstrap.close`, before the region saves): one budget for every AWF wait at shutdown (flushes, final commits, commit-lock waits, Redis calls, lease release), counted from the start of shutdown and so including the vanilla chunk save. When it passes no further commit starts, queued commits are fenced, lease renewal stops and Redis connections in use are cut; each unconfirmed storage is logged at ERROR, one `AWF-SHUTDOWN-INCOMPLETE` alert lists them, and the process exits with status 70 (MC feature 0080). Never reported as saved. Raise it for a world whose chunk save alone takes longer than the default ([aurora-world-fabric.md](architecture/aurora-world-fabric.md) D6) |

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

### Canvas-era names (renamed in Build 48, old names removed in Build 50)

Owner decision 2026-10-21. The new name wins when both are present; the old one is read with one
`[Aurora]` WARN line naming its replacement (once per JVM flag or saved key, once per player for the
permission). All are RESTART_REQUIRED except the permission, which is checked on every engine
broadcast (LIVE).

| New name | Old name (read until Build 50) | Kind | Read at |
| --- | --- | --- | --- |
| `-DAurora.AggressiveChunkSystem` | `-DCanvas.AggressiveChunkSystem` | JVM flag, `-Dx` alone means true | `MoonriseCommon` class init |
| `-DAurora.DisableRegionProfiler` | `-DCanvas.DisableRegionProfiler` | JVM flag, only `=true` counts | each region-profiler support check (`SchedulerUtil`) |
| `-DAurora.isRunningInIDE` | `-DCanvas.isRunningInIDE` | JVM flag, `-Dx` alone means true | `SharedConstants` class init |
| permission `aurora.broadcasting.receiver` | `canvas.broadcasting.receiver` (only when set explicitly; both default to operators) | permission | each `GlobalConfiguration.broadcast` |
| `Aurora.Distance` in `paper:level_overrides` | `Canvas.Distance` | saved world data | world load; the next save writes only the new key |
| `data/aurora/pearls.dat` (`aurora:pearls`) | `data/canvas/pearls.dat` (`canvas:pearls`) | saved server data | moved once by the world-folder migration before load |

The `config/canvas-server.yml`, `config/canvas-worlds.yml` and per-world `canvas-patch.yml` file
names are **not** renamed. The schedule is in `docs/releases/26.2-build-50.md`.

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
