# Task 7 Report

## Status

Completed command and HUD migration to the cached `PerformanceSnapshot`. Each `/tps`, `/mspt`,
`/sys`, and HUD update acquires one snapshot and passes it through render helpers. The obsolete
on-demand region scanners were deleted.

## Inherited Partial State

- Work resumed from an aborted dispatch at Task 6 baseline
  `a78b2c970d3723a5ae9f02c65a43b283f7ee17eb`.
- The inherited worktree already contained all nine Task 7 implementation/test paths as modified,
  deleted, or new files. Those edits were reviewed and retained where valid.
- The first focused suite invocation reported `UP-TO-DATE`, so it did not establish fresh RED or
  GREEN evidence. A forced rebuild exceeded the initial 120-second tool timeout before tests ran;
  this was not treated as a test failure.
- The module only executes classes selected through `SourbyMetricsTestSuite`; a direct method filter
  produced `No tests found`. Subsequent TDD runs selected the suite.

## Changed Files

- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/TpsCommand.java`
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/MsptCommand.java`
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/command/SysCommand.java`
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/hud/HudBars.java`
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/MetricsRuntime.java`
- Deleted `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/PerfMetrics.java`
- Deleted `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/RegionMspt.java`
- `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/MetricsConsumerTest.java`
- `sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/perf/SourbyMetricsTestSuite.java`
- `.superpowers/sdd/2026-09-02-custom-tick-metrics/task-7-report.md`

No generated `paper-*`, `canvas-*`, or `sourbycraft-server/src/minecraft` trees were changed or
committed. Public `sourbyapi` was not changed.

## Behavior

- `/tps` renders dynamic target TPS; worst, median, and aggregate TPS; worst average MSPT; active
  region count; separately retained global-region status; runtime values; and freshness.
- `/mspt` renders worst average MSPT against the dynamic tick budget, estimated p95/p99 with an
  unconditional approximation label, exact recent maximum, and freshness.
- `/sys` reuses the one entry snapshot for region, heap, RSS, and GC values. It no longer samples
  JVM heap separately while rendering.
- TPS HUD renders worst active TPS/MSPT, uses `targetTps()` for progress and thresholds, and marks
  WARMING, STALE, and UNAVAILABLE explicitly.
- NaN values format as `unavailable`; unavailable topology is not presented as a numeric healthy
  value.
- `MetricsRuntime.globalWindow` is a narrow server-internal accessor for Task 6's separate global
  snapshot. No public API surface was expanded.

## TDD Evidence

- Inherited test state: the initial focused invocation was `UP-TO-DATE`, so no inherited RED claim
  is made.
- RED: `./gradlew :sourbycraft-server:test --tests dev.iyanz.sourbycraft.perf.SourbyMetricsTestSuite`
  ran 88 tests and failed only
  `msptLabelsEstimatedTailsAndExactRecentMaximum`; estimated tails omitted `approximate` when the
  window-level approximate flag was false.
- GREEN: the same focused suite completed successfully after making the estimated-tail label
  unconditional.
- RED: the focused suite then ran 88 tests and failed only
  `unavailableStatesAndNanNeverRenderAsHealthyZero`; WARMING/UNAVAILABLE topology still rendered a
  numeric active-region count.
- GREEN: the same focused suite completed successfully after rendering unavailable topology
  explicitly. XML results record 88 tests, zero failures, zero errors, and zero skipped tests.
- Final focused invocation completed successfully; Gradle considered all tasks up to date after the
  preceding executed GREEN run.

## Compile And Search Evidence

- `./gradlew :sourbycraft-server:compileJava` completed successfully.
- `git diff --check` produced no output.
- Required no-scan search produced no matches under SourbyCraft command/HUD/perf sources:
  `computeForAllRegions|RegionMspt\.worstMsptMs|Bukkit\.getTPS\(\)`.
- Additional consumer search produced no matches under command/HUD sources:
  `Runtime\.getRuntime\(\)|Bukkit\.getAverageTickTime\(\)|PerfMetrics\.snapshot\(\)`.
- Dynamic-target search found no TPS clamp/progress division by a hard-coded 20 in `/tps`, `/mspt`,
  or `HudBars`.

## One-Read Evidence

- `MetricsConsumerTest.eachMetricsRendererReadsExactlyOneImmutableSnapshot` supplies a provider whose
  `snapshot()` increments a sequence and verifies `/tps`, `/mspt`, `/sys` performance rendering, and
  TPS HUD each read it once and render the same sequence marker.
- Production command entry points and the HUD updater load one `PerformanceSnapshot` before invoking
  snapshot-only helpers. `/sys` passes that same value to its cached performance block.

## Commit

- `ef0ed774b9ca2cf319fee821d2da3eb818987706`
  `perf(metrics): serve commands and HUD from cache`

## Self-Review

- Confirmed all requested consumer labels and unavailable states are covered by focused tests.
- Confirmed dynamic `targetTps()` controls TPS progress, color thresholds, MSPT budgets, and HUD
  progress.
- Confirmed the global-region data remains separate from active spatial-region distributions.
- Confirmed `PerfMetrics` and `RegionMspt` had no remaining callers before deletion.
- Confirmed final tracked implementation changes are limited to Task 7 paths.

## Concerns

- Gradle continues to report the pre-existing `writeBuildInfo` configuration-cache serialization
  warning during test runs; tests and compilation complete successfully.
- The suite-only Gradle include prevents direct execution of an individual test method; focused runs
  must select `SourbyMetricsTestSuite`.
- The initial forced rebuild needed more than the original 120-second tool timeout because it
  recompiled the large generated upstream API/server trees. Later incremental runs completed normally.
