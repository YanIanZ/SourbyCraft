# Aurora configuration — first implementation

Aurora uses the existing `sourbycraft_config/sourbycraft_global_config.toml`.
No extra files or empty namespaces are created. This increment implements one setting:

| Key | Type / default | Lifecycle | Consumer |
| --- | --- | --- | --- |
| `aurora.entity.async-pathfinding` | boolean / `false` | LIVE | AsyncPathProcessor admission flag |

```toml
[aurora.entity]
async-pathfinding = false
```

`AuroraConfig` and its nested entity record are immutable. `SourbyCraftConfig.aurora()`
returns the effective typed snapshot; the async processor receives the primitive value at
initialization/reload. Tick code does not parse configuration or read dotted paths.

## Compatibility and validation

The new key wins when present, including an explicit `false`. If absent, the legacy
`perf.ai.async-pathfinding` key remains readable and emits a deprecation warning at load/reload.
A malformed new key or namespace uses `false` and never falls through to a legacy `true`.
Strings such as `"true"` and integers such as `1` are invalid boolean values.

Only a first-created config receives the Aurora default. Existing operator files are neither
seeded with this new key nor automatically saved, including during reload and Spark collection.
Use nested TOML tables as shown above. Migration is an operator edit, not an automatic rewrite.

## Reload

`/sourbycraft reload` re-parses and applies the implemented Aurora setting. Its response reports
changed live Aurora values and invalid key paths. There are currently no implemented Aurora
restart-required or immutable-for-run settings, so the Aurora-only restart list is empty.
The lifecycle model supports `LIVE`, `RESTART_REQUIRED`, and `IMMUTABLE_FOR_RUN`; future settings
must implement their actual lifecycle before being exposed. Canvas and other cached utility
settings retain their separate restart caveats.

Disabling the live toggle prevents subsequent navigation checks from choosing async solves.
Already admitted solves may finish and hand results back through the existing owning-entity
scheduler; reload does not cancel them. Idle pool workers may time out and the pool remains
available for a later explicit enable. Shutdown has its existing cancellation behavior.
Async pathfinding remains experimental and default-off: this configuration migration does not
complete the snapshot/staleness or mob-compatibility audit.

## Spark

The existing `sourbycraft/` config group reports Aurora tables from the operator file and filters
credential fields recursively. It reports file contents, not resolved effective values: when
both legacy and new keys exist, both appear, with precedence defined above. Web-viewer rendering
and separate effective-runtime metadata remain open tasks. Generic key-name filtering cannot
recognize secrets placed under arbitrary innocuous names; operator hidden paths remain supported.

## Verification scope

AuroraConfigTest covers precedence, invalid types/namespaces, immutable values, legacy preservation,
first-file seeding, runtime load/reload publication, and admitted-work draining after disable.
Claude contributed a Spark group test for Aurora fields, nested redaction, and unchanged file bytes.
Full build/test and isolated boot/reload results are recorded with each delivery; no performance
improvement or stable-release qualification is inferred from these functional checks.

Aurora-1 validation on 2026-09-14: patch application and full build passed; Java reports
contained 9,894 tests (24 skipped, zero failures/errors), including 11 Aurora config tests
and 7 Spark config tests. The script suite passed 180 tests at source commit e5507bf.
An isolated cached-dependency server booted, processed normal and invalid-key reloads,
preserved fixture bytes across each operation, and stopped with exit 0 after saving all
worlds/player data. These results qualify this configuration increment, not the full Aurora
performance/soak program.
