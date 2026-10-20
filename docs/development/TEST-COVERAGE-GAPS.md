# Test coverage gaps — prioritised list

Status: **a list of tests to write, none of them written here.** Audit row OBS-14
(`docs/audits/2026-10-14-system-audit.md` §4) found coverage holes in `engine`, `hud`, `maxplayers`,
`lang`, `update` and `bootstrap`, and timing-sensitive tests. This page turns that row into a
prioritised backlog with file evidence, verified 2026-10-20 against `sourbycraft-server/src/test/java`
(115 `*Test.java` files, 124 Java files in all, against 275 main-source files). It complements the
release-level matrix in `DEVELOPMENT.md` §20, which lists *what* a candidate must cover, not *which
unit tests are missing*. When a test below lands, move its line to a "closed" section with the test
class name; do not delete it.

How the priorities were chosen: P1 is code that modifies the installation or decides who may join
(damage if wrong is not recoverable by a restart); P2 is gameplay- or operator-visible logic with no
test at all; P3 is engine internals that only have documentation tests, and test hygiene.

## P1

| # | Package / class | Gap (evidence) | Test to write |
|---|---|---|---|
| 1 | `update/` — **Covered 2026-10-20**: `SemVerTest`, `BuildVersionTest`, `UpdateChannelTest`, `AutoUpdateSettingsTest`, `HttpTransportTest` (local HttpServer: redirects, timeouts, size bound, scheme refusal) on top of the existing updater tests; connect timeout untested (needs an unroutable host). Original: `UpdateApplier`, `UpdateRollbackGuard`, `ReleaseSignatureVerifier`, `HttpTransport`, `SemVer`, `UpdateChannel`, `BuildVersion`, `AutoUpdateSettings` | 12 main files, one test (`update/SourbyUpdaterLifecycleTest`). The updater swaps the running jar; `apply_mode=auto` is the default (AGENTS.md). Audit SEC-2/SEC-3/SEC-9/SEC-10 fixes are in flight under the `updater-hardening` owner, who adds tests for the paths they change; the rest stays open | Apply + rollback round trip on a temp dir (`.prev` restore, same-MC gate); signature verification accept/reject/tampered; `SemVer` ordering and prerelease handling; channel resolution from the branch name; `HttpTransport` redirect/size/timeout limits with a local HTTP server |
| 2 | `bootstrap/` — **Covered 2026-10-20**: `Sha256VerifierTest`, `BootstrapPluginSettingsTest`, `PluginProvisionerTest`, `ProtocolLibProvisionerTest` (71 tests, stub transport, offline refusal, no overwrite of operator jars/config); `--plugins` selection and `MinecraftInternalPlugin` still need a server. Original: `PluginProvisioner`, `ProtocolLibProvisioner`, `Sha256Verifier`, `BootstrapManifest`, `BootstrapPluginSettings`, `CdsEnvironment`, `SourbyBootstrap` | 10 main files, one Java test (`bootstrap/LibDownloaderTest`). `scripts/test_plugin_provisioning.py` runs installation/configuration probes against this package from Python, so provisioning is not untested, but no Java unit test covers the verifier or the manifest parser | `Sha256Verifier` mismatch/short-read/empty file; `BootstrapManifest` parse of a malformed `META-INF/libraries` entry; `PluginProvisioner` idempotence (verified jar present → no download, user config untouched) with a stub transport; offline mode refuses downloads |
| 3 | `maxplayers/` — `MaxPlayersBypass`, `MaxPlayersConfig` | **Covered 2026-10-20** (`b5766689` + the config seam commit): `MaxPlayersBypassTest`, `MaxPlayersConfigTest` (persist, boot apply, unwritable path) through `SourbyCraftConfig.installForTesting`. Original gap: no test named either class (`rg -l 'MaxPlayers' src/test` → none). `MaxPlayersBypass` flips a `KICK_FULL` login to `ALLOWED` for permission holders (`MaxPlayersBypass.java:69`); `MaxPlayersConfig.applyAtBoot()` persists `/maxp` | `PlayerLoginEvent` with `KICK_FULL` + permission → `ALLOWED`; any other kick reason untouched; listener not registered when the key is `false` (default); boot apply reads the persisted value and rejects a non-numeric one |
| 4 | `core/SourbyCraftBootstrap` stage accounting | **Covered 2026-10-20**: `core/SourbyCraftBootstrapTest` (source count of `stage("` == `TOTAL_STAGES`; a throwing stage is counted, recorded unsuccessful in `bootStages()`, later stages still run, verdict DEGRADED). `init()` itself still needs a server. Original gap: `TOTAL_STAGES = 14` is a hand-maintained constant and the file has exactly 14 `stage("` calls today; nothing checks they stay equal, and the boot bar's denominator and the COMPLETE/DEGRADED/FAILED verdict depend on it (`SourbyCraftBootstrap.java:181`, `AuroraBoot.summary`) | A source-reading test (the pattern `architecture/UnboundedQueueAuditTest` already uses) asserting the count of `stage("` equals `TOTAL_STAGES`; a unit test that a throwing stage yields DEGRADED and `bootStages()` records it |

## P2

| # | Package / class | Gap (evidence) | Test to write |
|---|---|---|---|
| 5 | `lang/` — `SourbyMessages`, `SourbyJoinLeaveListener` | **Covered 2026-10-20** (`b5766689` + the config seam commit): `SourbyMessagesTest`, `SourbyJoinLeaveListenerTest`, no reflection on config internals. Original gap: no test named either class. Messages come from the operator-editable TOML (`sourbycraft_config/sourbycraft_global_config.toml`) | Placeholder substitution, missing key fallback, colour/MiniMessage parsing of a malformed value (must not throw at join time); join/leave listener respects an empty message (suppress vs send) |
| 6 | `hud/HudBars` | **Covered 2026-10-20**: `HudBarsRenderTest` (`b5766689`) and `HudBarsSchedulingTest` (updater/viewer lifecycle through `HudBars.installForTesting`). Original gap: no test named it (only `perf/MetricsConsumerTest` is nearby and does not cover rendering) | Bar rendering at 0 %, 100 %, >100 % and NaN inputs; width clamp; unicode vs ASCII style selection |
| 7 | `command/` — **Covered 2026-10-20** for Tps/Mspt/Sys/Maxp/Ping/Ver/HudBar/PerfBar (`TpsMsptCommandTest`, `SysCommandTest`, `MaxpCommandTest`, `PingCommandTest`, `VerCommandTest`, `HudBarCommandsTest`); booted-server paths (`ServerBuildInfo`, CPU identity) untested. Original: 19 main files, 6 tests (`PerfAwfView`, `PerfLanesView`, `PerfStartupAndChunkHint`, `PluginsCommand`, `SourbyReply`, `SpecCommand`) | `TpsCommand`, `SysCommand`, `UpdateCommand`, `SpeedtestCommand`, `AwfCommand` have no direct test; OBS-8 (async replies through `SourbyReply.run`) is being fixed by the `obs-java` / `updater-hardening` owners with tests for the commands they touch | Permission denial before any side effect; command-block sender gets a reply, not an exception; `/sys` and `/tps` formatting of `NaN` runtime metrics |

## P3

| # | Package / class | Gap (evidence) | Test to write |
|---|---|---|---|
| 8 | `dev.iyanz.aurora.engine` — **Covered 2026-10-20**: 18 engine classes (157 tests: `util/map`, `configuration` upgrade, `util/migration`, `world/chunk`, `world/block`, `world`, every `subcommands` entry) + 10 `spark/*` classes (53 tests); bugs fixed in `BalancedChunkSystem`, `RegionResourceBar` codec, `NodeDiff` (comment-only YAML), `FoliaSparkPlugin` version and datapack description; server-bound subcommand execution and `gameTargetTps` still need a server. Original: 91 main files, 8 tests, of which 4 are configuration *documentation* tests; the rest cover `threadedregions` world unload (2) and `util/ticket` (2). Untested subpackages: `util/map`, `util/migration`, `util/command`, `world/chunk`, `world/block`, `subcommands`, `spark/*` | The engine has been SourbyCraft-owned since 2026-10-05 (AGENTS.md); `scripts/concurrency_policy.py` now scans it, but policy scans are not behaviour tests | `util/map` concurrent map semantics under two writers; `util/migration` upgrade of an old `canvas-server.yml` key set (idempotent, unknown keys preserved); `world/chunk` helpers against a fixed fixture; one test per `subcommands` entry asserting permission and output shape |
| 9 | `sourbyapi` — **Covered 2026-10-20**: 58 tests for `io.canvasmc.canvas` (events, handler lists, `callEvent`, `region` surface with return types, `simd`, `WorldUnloadResult`, `Unsupported`); the main-thread guard and region refusals live in the server implementation. Original: 2 tests (`MetricsApiSurfaceTest`, `WorldRequestTest`) | API default methods (`RuntimeMetrics` build-44 fallbacks returning `-1`/`NaN`) are pinned by `MetricsApiSurfaceTest`; the `io.canvasmc` plugin API (`event`, `region`, `simd`) has none | Event construction/`callEvent` on the main thread guard; `region` API refusal paths |

## Timing-sensitive tests to convert to injected clocks

Found with `rg 'Thread\.sleep|System\.nanoTime\(\)|currentTimeMillis\(\)|TimeUnit\.[A-Z]+\.sleep' src/test`.
"Seam" says whether the class under test already accepts a clock, so the conversion is a test-only
change.

| Test | Line(s) | What it waits on | Seam in the class under test | Action |
|---|---|---|---|---|
| `execution/LaneCpuSamplerTest` | 136-137 | Busy-loops 50 ms of real CPU to make a sample non-zero | `LaneCpuSampler` is constructed by `PerformanceCollector`, which takes `LongSupplier nanoClock` (`PerformanceCollector.java:97-114`); the sampler itself reads thread CPU via the MXBean | Keep the CPU burn (it is the quantity measured) but drive the sampling window from the injected clock instead of wall time |
| `bridge/BridgeRuntimeTest` | 492 | `Thread.sleep(millis)` to let a timer fire | `BridgeRuntime.nanoClock` is a `LongSupplier` field (`BridgeRuntime.java:167`) | Advance a fake `LongSupplier`; no sleep |
| `perf/AsyncPathShutdownTest` | 206 | `Thread.sleep(10L)` polling for shutdown | None visible; shutdown is a `CountDownLatch`/future | Await the latch/future with a timeout, no sleep loop |
| `perf/AsyncPathQueueTelemetryTest` | 81 | `Thread.sleep(60)` inside a task to create queue age | None; queue age comes from `System.nanoTime()` in the queue | Give the queue a `LongSupplier` and advance it |
| `awf/AwfLifecycleFenceTest` | 58-60 | 10 s deadline poll on an async fence | `AwfEngine(AwfSettings, Executor, LongSupplier nanoClock)` (`AwfEngine.java:53-57`) | Use a same-thread executor plus the clock seam; the poll becomes a direct assertion |
| `awf/redis/RedisBackendTest` | 134, 173 | `Thread.sleep(20)`; `Thread.sleep(8000)` to outlive the 5 s lease | Lease TTL is a server-side Redis expiry; the backend's renewal timer is the thing to inject | Make the lease TTL and the renewal clock constructor parameters so the test uses a 100 ms lease; the 8 s sleep is the single slowest unit test in the suite |
| `awf/redis/RedisTestServer` | 64-65 | Waits up to 10 s for the spawned `redis-server` to accept connections | Not a test of SourbyCraft code | Leave as is (process start-up) |
| `awf/AwfQualificationTest` | 188, 212 | Measures elapsed time for a report line | Reporting only, no assertion on time | Leave as is |
| `awf/redis/AwfLoadBenchmarkTest` | 42-44 | Benchmark; timing is the subject | N/A | Leave as is; it must stay out of the default suite's pass/fail gate |
| `awf/AwfRegionStorageTest` | 126 | `System.nanoTime()` as a unique template name | Not timing-sensitive | Leave as is |

## Closed

- 2026-10-20: the six "convert" rows of the timing table. `BridgeRuntimeTest`, `AsyncPathQueueTelemetryTest`, `LaneCpuSamplerTest` use injected clocks (`BridgeRuntime.nanoClock` now also times bodies, new `AsyncPathProcessor.nanoClock`); `AsyncPathShutdownTest` and `AwfLifecycleFenceTest` poll without sleeping (no event to latch on); `RedisBackendTest` uses a 1 s lease renewed every 100 ms through a package-private `RedisBackend` constructor (production keeps `lease-seconds`, minimum 5 s) — class time 13.1 s → 4.5 s. Real time remains only where Redis's own expiry clock is the thing measured.
