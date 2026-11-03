# Aurora Implementation Workflow, Compatibility and Rollback

**Status: planned procedures for later PRs.** This document does not change the build and cannot certify any binary.

## Branch and PR discipline

Target individual changes onto the appropriate current 26.2 development branch. A future 26.3 line is separately managed per maintainer decision. Preserve the 26.2 private toolchain (SourbyPatcher, SourbyClip), pinned hashes and `paperRef` until an authorized upgrade track updates them.

Every code PR documents:
- baseline SHA and measured bug/hotspot;
- exact old implementation and changed source/patch files;
- owner lifecycle/thread context, compatibility, plugin exposure;
- new API or config semantics with default and restart behavior;
- tests to be performed by owner, and any test fixture added;
- before/after performance only if actually measured;
- data migration/rollback and failure/recovery procedure;
- source/license provenance of borrowed code.

## Patch source of truth

The repository's tracked `sourbycraft-server/minecraft-patches/*`, `paper-patches/*` and `sourbyapi/paper-patches/*` are authoritative. Do not directly commit materialized/generated `paper-server` or `sourbycraft-server/src/minecraft` copies. Follow [UPGRADING-MINECRAFT.md](../../development/UPGRADING-MINECRAFT.md) and run `scripts/patch_surface.py --check-rebuild` before invoking rebuild tasks. The private toolchain may not be available to every contributor; no fabricated build PASS.

## Allowed refactor shapes

1. Move truly cross-cutting policy into existing `dev.iyanz.aurora.engine` services with explicit lifecycle and real users.
2. Keep direct source patches only where the performance hot path warrants them.
3. Route supported internal Paper/Folia source reads through the approved execution adapters; maintain the [dependency ledger](../../architecture/dependency-ledger.md) and its tests.
4. Implement operational telemetry before selecting a new resource budget.
5. Preserve public Bukkit/Folia method semantics, event order and permissions unless explicitly migration-reviewed.

**Not allowed as a substitute for work:** renaming namespaces, adding unused `AuroraScheduler` wrappers, changing the original authenticated UUID, deleting private toolchain pins, swapping binary Moonrise mods/JARs into a patched source engine, or interpreting a concurrent map as permission for wrong-thread Minecraft mutation.

## Feature flag and compatibility policy

Any new risky feature starts disabled, with explicit config schema, default behavior, runtime capability gates and operator warning on unsupported modes. Config examples remain labelled PROPOSED until code exists. Existing command/config/reference docs must only describe implemented keys and accurate version status.

Define a rollback path for every feature: if behavior is in-memory, disable flag and restart safely; if it modifies persistent data, **do not rollback by old binary alone**. Snapshot/manifest/schema compatibility and last committed state must be checked before writable load.

## Production change control

1. Stage clone with verified backups and current baseline.
2. Deploy one experimental subsystem to canary with feature gate off.
3. Qualify source correctness and owner manual test cases; run targeted benchmark.
4. Enable canary for limited traffic where safe, monitor p99/tick/durability/errors.
5. Promote only with owner sign-off and replayable evidence; maintain old qualified JAR.
6. On anomaly: stop admission; preserve logs, journal and snapshots; disable feature, reconcile data and restore last qualified state safely.

## Shutdown and recovery order

Region/gameplay admission must stop before final state save. Required queued work must settle or time out with explicit UNKNOWN/RECOVERY outcomes. External Redis/SQL/HTTP requests cannot block the owning tick thread. Resource Governor lanes shut down after dependent services no longer need them. AWF confirmed flush occurs before world unload reports SUCCESS. On a failed save, do not silently proceed with an empty world.

## Security and performance

Treat plugin code as untrusted with respect to thread ownership; do not allow it to call arbitrary Aurora internal admin interfaces. Bounded input/queueing, redacted logs, least-privilege management commands and stable permission policy are mandatory. Avoid chasing throughput if it increases vulnerability surface, persistence risk or plugin incompatibility.

## Release boundaries

Existing Build 50 readiness and documentation truth policy remain authoritative. Later Build 56–99 work must not silently change `sourbyBuild` or release channel. Build 100 requires explicit Paper/MC 26.3 migration and an owner approved release candidate; this docs-only design does not make that migration.
