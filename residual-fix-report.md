# Residual Tick Metrics Correction Report

Date: 2026-09-04
Branch: `feat/custom-tick-metrics`
Starting commit: `2cc0a768b643a19ee662b80f66b3e3683755a34c`

## Scope And Commit Mapping

| Commit | Residual correction |
|---|---|
| `2cc0a768b643a19ee662b80f66b3e3683755a34c` | Unified active/sealed state in one atomic `long`; changed the collector median scratch to `float[]`; capped targetless compatibility storage at 32 samples; introduced one synchronized owner acquisition for refresh plus all five histogram merges; added the corresponding structural, race, collector, compatibility, and boundary regressions. |
| `772c26f023e0a54586f306b5c78cd2f4f9b49b65` | Completed the interrupted correction: cached active-marker overlays by atomic state and publication sequence, refreshed dirty same-epoch owner state once before histogram collection, merged every histogram at the snapshot sampling boundary, fixed the blocked-completion regression setup, asserted repeat acquisition identity, and corrected the patch hunk length. |

The requested `residual-fix-brief.md` was not present in the worktree or elsewhere under `/Users/rheninxy`. The residual items explicitly supplied in the resume request were checked against the approved design and implementation plan and used as the completion checklist.

## Correctness Evidence

- Atomic lifecycle marker: generated `RegionTickMetrics` has exactly one `private volatile long activeState = INACTIVE`; `sealed` is not a separate field. `sealRacingTickStartNeverPublishesPostSealActivityOrAcceptsCompletion` and `activeAndSealedStateShareOneAtomicLongMarker` pass.
- Coherent collector acquisition: `PerformanceCollector.accept` contains one `acquireSnapshot` invocation and no `mergeHistogram` invocation. `ownerAcquisitionKeepsEveryHistogramConsistentWithItsSnapshot` first failed with `expected: <1> but was: <2>`, exposing same-epoch bucket mutation after publication. The dirty-publication fix makes all five snapshot counts match histogram rank mass and reuses the same snapshot on a repeated unchanged acquisition.
- Same-epoch reuse: `sameEpochRefreshReusesPublicationAndNextEpochExpiresData` and the repeat-acquisition assertion pass. Active-marker overlays are cached by atomic state plus publication sequence.
- Collector median scratch: generated `PerformanceCollector` has exactly one `private float[] medianBuffer`; `floatMedianScratchPreservesFiniteOrderingAndExpectedMedian` passes.
- Targetless bound: generated `RegionTickMetrics` has exactly one `UNRESOLVED_CAPACITY = 32`; `targetlessCompatibilityRingIsExactThroughThirtyTwoAndUnavailableOnOverflow` passes.
- Consumer structure: no `computeForAllRegions`, `RegionMspt.worstMsptMs`, or `Bukkit.getTPS()` use remains under the SourbyCraft command, HUD, or performance consumer paths.

## Verification Evidence

All commands ran from `/Users/rheninxy/Sourby/SourbyCraft/.worktrees/custom-tick-metrics` unless noted.

| Gate | Command | Result |
|---|---|---|
| Patch materialization | `./gradlew applyAllPatches --no-daemon --stacktrace` | `BUILD SUCCESSFUL`; 931 Paper and 127 Canvas Minecraft source patches plus SourbyCraft patch `0019` applied. |
| Fresh API tests | `./gradlew :sourbyapi:test --no-daemon --rerun-tasks --stacktrace` | `BUILD SUCCESSFUL`; 4 tasks executed. |
| Fresh server tests | `./gradlew :sourbycraft-server:test --no-daemon --rerun-tasks --stacktrace` | `BUILD SUCCESSFUL`; 11 tasks executed. Metrics suite includes 115 passing tests; full server test XML reports zero failures and zero errors. |
| Fresh fixture tests | `./gradlew -PincludeTestPlugin=true :test-plugin:test :test-plugin:jar --no-daemon --rerun-tasks --stacktrace` | `BUILD SUCCESSFUL`; 8 tasks executed. |
| Fresh compilation | `./gradlew :sourbyapi:compileJava :sourbycraft-server:compileJava -PincludeTestPlugin=true :test-plugin:compileJava --no-daemon --rerun-tasks --stacktrace` | `BUILD SUCCESSFUL`; 3 tasks executed. |
| Structural checks | Exact-count `rg` checks plus `git diff --check` | One atomic marker, one cap constant, one float median buffer, one collector acquisition, zero collector separate merges, zero forbidden consumer scans; no whitespace errors. |
| Retained-memory probe | Temporary JDK 25 `Instrumentation#getObjectSize` transitive graph probe | `41,392,712` bytes (`39.475166 MiB`), 48,038 owned objects, under the `41,943,040` byte (`40 MiB`) limit by 550,328 bytes. |
| Slim jar | `./gradlew slimServerJar --no-daemon --rerun-tasks --stacktrace` | `BUILD SUCCESSFUL`; 19 libraries stripped; `build/libs/SourbyCraft-slim.jar` is 40,155,772 bytes with SHA-256 `d20bcf3b7b1aa464a4bcd10e81c79674db8d31dcb411e9a13a1099b475ef3e05`. |
| Fixture boot and shutdown | JDK 25, `-Xmx2G -XX:+UseG1GC`, slim jar plus `test-plugin-1.0.0.jar`, FIFO commands `tps`, `mspt`, `spark profiler info`, `stop` | Reached `Done (`; emitted both `SOURBY_METRICS_ONLOAD_OK` and `SOURBY_METRICS_ONENABLE_OK`; emitted TPS, MSPT, and spark markers; natural JVM exit status `0` within 60 seconds; no forced termination. |

## Investigation Notes

- The resumed partial patch had a stale new-file hunk length. Weaver initially materialized `RegionTickMetrics.java` at 888 lines and compilation failed at end-of-file. The source-of-truth hunk length was corrected to the actual 913 lines; no generated source was edited.
- Direct Gradle `--tests` selection conflicts with this module's intentional `**/**TestSuite.class` include. Focused verification therefore used `SourbyMetricsTestSuite`, which executed 115 tests.
- Gradle reports the existing `writeBuildInfo` configuration-cache incompatibility and discards cache entries for affected invocations. Builds still exit successfully; this correction did not modify that known build behavior.

## Residual Concerns

- The retained-memory result has 550,328 bytes (about 0.525 MiB) of headroom. It passes the all-inclusive 40 MiB requirement but leaves little room for future per-generation fields or larger primitive buffers.
- The memory probe excludes shared JVM metadata (`Class`, class loaders, modules, enum singletons, and strings) and traversing the collector thread's shared thread-group graph. It includes the collector thread object's shallow size and all telemetry-owned reachable state.
- Build output contains existing deprecation, restricted-native-access, and configuration-cache warnings; no verification gate failed because of them.
