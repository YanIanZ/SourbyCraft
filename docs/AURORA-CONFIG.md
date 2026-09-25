# Aurora configuration — effective 26.2 contract

This document describes **what branch `26.2` actually consumes now**. It is not a future-layout proposal.

## Implemented Aurora settings

| Key | Type / default | Lifecycle | Consumer |
| --- | --- | --- | --- |
| `aurora.entity.async-pathfinding` | boolean / `false` | LIVE | `AsyncPathProcessor` admission |
| `aurora.diagnostics.lane-sampling` | boolean / implementation default | LIVE | execution-lane CPU attribution |
| `aurora.cpu.cores` | integer / `0` (AUTO) | RESTART_REQUIRED | early region-scheduler CPU budget |

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
