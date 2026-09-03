# Task 8 Report

## Status

Completed the Canvas embedded spark adapter migration to SourbyCraft's cached performance snapshots.
Normal all-region statistics no longer traverse regionizers on read, while selected-region profiling
continues to read only the selected handle's telemetry generation.

## Changed Files

- `sourbycraft-server/canvas-patches/files/src/main/java/io/canvasmc/canvas/spark/plugin/FoliaTickStatistics.java.patch`
- `sourbycraft-server/canvas-patches/files/src/main/java/io/canvasmc/canvas/spark/FoliaSparkPlugin.java.patch`
- `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/FoliaTickStatisticsTest.java`
- `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/SourbyMetricsTestSuite.java`
- `.superpowers/sdd/2026-09-02-custom-tick-metrics/task-8-report.md`

Generated `canvas-server` and `paper-server` trees were used only to materialize and verify patches;
they are not tracked by the root repository and were not committed.

## TDD Evidence

- Baseline: `./gradlew :sourbycraft-server:test --tests
  dev.iyanz.sourbycraft.perf.SourbyMetricsTestSuite` completed successfully before Task 8 changes.
- The module's suite-only include rejected a direct `FoliaTickStatisticsTest` filter with `No tests
  found`; this was an invocation/configuration failure and was not treated as RED evidence.
- RED: the registered `SourbyMetricsTestSuite` ran 97 tests against the current Canvas adapter and
  failed the four new adapter tests. Normal reads entered `RegionizedServer` traversal, duration
  support returned false/zero values, and selected 10-second statistics entered the old compatibility
  path. The duration-support assertion specifically reported expected `true`, actual `false`.
- GREEN: after patch materialization, the same focused suite ran successfully with all 97 tests.
- The first materialization attempt rejected malformed hand-authored hunk counts before changing
  behavior. Exact unified-diff counts were regenerated from temporary desired-source files; the
  subsequent materialization applied all four Canvas server file patches successfully.

## Mapping And Duration Evidence

- Distinct aggregate TPS fixtures verify exact `FIVE_SECONDS`, `TEN_SECONDS`, `ONE_MINUTE`,
  `FIVE_MINUTES`, and `FIFTEEN_MINUTES` mappings: 5, 10, 60, 300, and 900 respectively.
- A read-counting canonical provider proves each normal accessor acquires exactly one immutable
  snapshot and returns `WindowMetrics.aggregateTps()`.
- `isDurationSupported()` is true. The 10-second, 1-minute, and 5-minute accessors expose
  `worstAverageMspt()` as spark's conservative region-aware mean, plus matching minimum, maximum,
  median, and estimated p95 values.
- An unavailable-window fixture verifies mean, minimum, maximum, median, and p95 remain `NaN` rather
  than becoming healthy-looking zeroes.
- `DoubleAverageInfo.percentile(0.5)` and `percentile(0.95)` back the existing spark median and p95
  accessors; unsupported percentiles return `NaN`.

## Selected Region And No-Scan Evidence

- `selectedRegionUsesHandleMetricsWithoutReadingGlobalProvider` installs a real
  `RegionScheduleHandle`, records 120 local samples into its `sourbyTickMetrics` holder, and installs
  a canonical provider that throws if read. The true 10-second accessor returns the handle's 10 TPS
  without triggering that provider.
- The selected branch reads `handle.sourbyTickMetrics.current().snapshot(System.nanoTime())` and maps
  the requested window directly, preserving region-local behavior for both TPS and duration calls.
- Inspection of the materialized `FoliaTickStatistics.java` found no `RegionizedServer`,
  `computeForAllRegions`, `getTPSAverage`, or `generateTickReport` references. Its normal branches
  contain one `metrics.snapshot()` call and no region/world traversal.

## Compatibility

- `FoliaSparkPlugin#createTickStatistics()` injects `MetricsRuntime.provider()`.
- The no-argument `FoliaTickStatistics` constructor remains and delegates to the canonical provider.
- `TickStatistics#gameTargetTps()` remains implemented using Canvas's configured tick rate.
- `getPermissions()` retains
  `platform.getCommandManager().getAllSparkPermissions()` for spark-paper 1.10.172 compatibility.
- Command execution/tab completion, profiler start/stop/open/cancel, sampler, upload/live viewer,
  protobuf behavior, URLs, `spark.lucko.me`, and plugin lifecycle code were not modified.

## V3 Verification

The required stages were run separately:

1. `./gradlew applyAllPatches --no-daemon` succeeded and applied four Canvas server file patches.
2. `./gradlew :sourbycraft-server:test --tests
   dev.iyanz.sourbycraft.perf.SourbyMetricsTestSuite --no-daemon` succeeded with 97 tests.
3. `./gradlew :sourbycraft-server:compileJava --no-daemon` succeeded.

## Commit

- `17a21a6549afae1657f3965e5aea6499e3973229`
  `perf(spark): use cached SourbyCraft tick metrics`

## Self-Review

- Confirmed all production edits are represented by tracked `canvas-patches/files` patches.
- Confirmed five distinct TPS values prevent accidental window aliases, especially 10s to 15s.
- Confirmed duration values use existing spark `DoubleAverageInfo` fields and preserve unavailable
  values as `NaN`.
- Confirmed selected-region logic runs before provider access and reads the selected generation.
- Confirmed the normal branch is O(1) with no world/region scan or report generation.
- Confirmed the generated adapter compiles against the current spark dependency while retaining the
  requested 1.10.172 compatibility methods.
- Confirmed the implementation commit contains only the four Task 8 patch/test files.

## Concerns

- Gradle continues to report the pre-existing `writeBuildInfo` configuration-cache serialization
  warning during test runs; the suite and standalone compilation succeed.
- `git diff --check` reports whitespace on blank unified-diff context lines in the newly added `.patch`
  files. Those leading spaces are required diff syntax, and the patch engine applies them cleanly.
- The repository currently resolves `spark-paper:1.10.180` even though the embedded adapter reports
  1.10.172 and the task requires retaining the 1.10.172 compatibility methods. This task did not alter
  dependency versions or existing spark protocol behavior.
