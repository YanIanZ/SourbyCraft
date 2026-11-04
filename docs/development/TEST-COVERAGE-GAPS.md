# Test coverage gaps — prioritised list

Status 2026-10-25: **every row of the 2026-10-20 backlog got the tests named in it.** Those rows are now
under [Closed](#closed) with their test classes; only their residual gaps stay open below. The second open
list is new: the tests that PR #62 (the Build 50 work on `build50/pm-completion`) deliberately did **not**
write, because the owner ruled out new tests in that PR. They are owner or test-wave work. The PR #62 code
itself is code written and statically reviewed; not built; not tested by the owner. Each item names its PM
id; the [PM matrix](../releases/BUILD-50-PM-MATRIX.md) has the done criteria and the owner's manual scenario.

Origin: audit row OBS-14 of the 2026-10-14 system audit found coverage holes in `engine`, `hud`, `maxplayers`,
`lang`, `update` and `bootstrap`, and timing-sensitive tests (the report, `docs/audits/2026-10-14-system-audit.md`
§4, was removed from the repository in `c86b3be` and is in git history only). The first version of this page,
verified 2026-10-20 against `sourbycraft-server/src/test/java` (115 `*Test.java` files, 124 Java files in all,
against 275 main-source files), turned that row into a prioritised backlog. On 2026-10-25 the same tree holds
359 `*Test.java` files, 383 Java files in all, against 475 main-source files (file counts, not coverage). This
page complements the release-level matrix in `docs/DEVELOPMENT.md` §20, which lists *what* a candidate must
cover, not *which unit tests are missing*. When a test below lands, move its line to "Closed" with the test
class name; do not delete it.

How the priorities were chosen: P1 is code that modifies the installation or decides who may join
(damage if wrong is not recoverable by a restart); P2 is gameplay- or operator-visible logic with no
test at all; P3 is engine internals that only have documentation tests, and test hygiene.

## Open: residual gaps of the 2026-10-20 rows

| # | Package / class | Still untested | Why |
|---|---|---|---|
| 1 | `update/HttpTransport` | Connect timeout against a host that never answers. PM-297 asks for a URL-opener seam in `HttpTransport.jdk` plus a deterministic test | Not done in PR #62: a seam without its test was judged speculative |
| 2 | `bootstrap/` | `--plugins` directory selection and `MinecraftInternalPlugin` | Need a server |
| 4 | `core/SourbyCraftBootstrap.init()` | The real stage run, and since PR #62 that `AutoUpdateSettings.runBootGuard()` runs before the first stage (PM-095) | Needs a server; `SourbyCraftBootstrapTest` counts `stage("` calls in the source |
| 7 | `command/` | Booted-server paths (`ServerBuildInfo`, CPU identity) | Need a server |
| 8 | `dev.iyanz.aurora.engine` | Server-bound subcommand execution, `gameTargetTps` | Need a server |

## Open: tests PR #62 did not write (owner rule; owner or test wave)

### Auto-updater and plugin provisioning (`update/`, `bootstrap/`) — P1

- PM-034, PM-094: a preflight failure or a failed swap causes no restart and writes `auto_update/apply-failed.tag`,
  and the next scheduled check returns `NOTIFIED` without downloading the jar. `UpdateApplierSwapTest` and
  `UpdateApplierRestartPlanTest` were only adjusted to the changed signatures and plans.
- PM-081: a scheduled check and `/update` racing through a scripted transport: one runs, the other gets "already
  running", and only one temporary download file exists.
- PM-082: a key file with two PEM blocks: a release signed with either key stages, a tampered jar or manifest is
  refused, one corrupt block refuses every stage; `release_signing_key.py --add-pub` appends a block.
- PM-083: a rollback to a pre-Build-49 jar puts back the newest `*.migrated` copies, never over an existing file;
  the nested paperclip `versions.list` build-number read against a real slim jar.
- PM-109: a listing with `v26.3-r100` and `v26.2-r51` on a 26.2 Build 50 server in stage mode stages r51 and
  announces r100.
- PM-215: asset name `../x.jar` refused; a declared 600 MiB refused before any request; an `http` asset URL
  refused without the test override and accepted on loopback with it; the 15-minute download deadline.
- PM-229: a stop held during the first boot after an apply does not confirm it (phase stays `BOOTING`).
- PM-254: guard `OK` for the tag, an older running build and a launch jar whose digest differs offer the release
  again; `ROLLED_BACK` keeps suppressing it.
- PM-097, PM-216: a signed plugin jar whose `*.SF` digests do not match counts as non-matching in the provisioning
  scan; a flow-style YAML `plugins:` value skips provisioning for that boot.
- Test migration (wave-2 follow-up): the legacy-key `assertFalse` cases in `BootstrapPluginSettingsTest` (and
  `scripts/fixtures/PluginProvisioningProbe.java`) now reach the `SKIP` result instead of the parser path they
  were written for; move them to the `plugins.<group>` spelling.

### Aurora World Fabric (`awf/`) — P1

- PM-023: the eviction write race: `AwfWorld` with one resident chunk, N writer threads and a storage-lane loop of
  incremental saves; after reopening the store each key reads its last value and `dirtyCount()==0`.
- PM-070, PM-071, PM-206, PM-207, PM-208: a prune or object-delete failure past the commit point is retried by the
  next commit; a non-SHA-256 object name is refused before a path is resolved; an `.awf`/`AwfFile` index offset
  that overflows throws `CorruptAwfException`; a pending object whose temp file vanished is rewritten;
  `READ_ONLY`/`TEMPLATE` opens leave temp and uncommitted files alone and create no directory.
- PM-252, PM-258, PM-290: Slime plugin data round trip through `importWorld`; an unknown Slime chunk flag named in
  the dropped list; a registry, template or Slime spawn out of range dropped with a WARN.
- PM-293 (only a test was asked for; not done): the 17th concurrent `save()` is refused (`MAX_PENDING_SAVES`),
  `lastCommitFailed` is set and the flush barrier reports `FAILED`.
- PM-453: `oldestDirtyMillis` grows with time and returns to 0 after a save.
- PM-072, PM-073, PM-209, PM-210, PM-223, PM-225, PM-226: a missing `template.json` refused on every load path;
  `world`/`overworld` registry entries ignored; cleanup with a storage still open under the folder; the POI read
  fence (`AwfReadFailureTest`); the maintenance-thread WARN after 30 s; two threads opening one folder; a close
  that throws counts as `FAILED` once.
- PM-025: `NodeIdentity`: a duplicate host name, a directory copy with a foreign lock fingerprint, takeover by the
  previous incarnation.
- Redis commit (wave 3): a stub-level test that the AWF commit script and `WAITAOF` go out on one connection
  (the existing `RedisBackendTest` runs that path against a real Redis).

### MultiProfile (`awf/multiprofile/`) — P1

- PM-004: `ProfileEngineTest` and `FileProfileRepositoryTest` additions, a `validate()` refusal, an oversized or
  growing record.
- PM-029, PM-031, PM-032, PM-077, PM-090: a player going offline after the apply leaves a `RECOVERING` journal; an
  unreadable journal is quarantined for its player only; a fence-5 journal against a fresh `LocalProfileOwnership`
  and a same-incarnation stale fence refused; saves refused with `RECOVERY_REQUIRED` while a journal is pending; a
  teleport that never completes ends in `TIMEOUT`.
- PM-033: the switch freeze cancels item, XP and interaction events.
- PM-079, PM-092: an infinite (`-1`) potion effect round trip; the idle sweep with a fake world port.
- PM-037: `RedisProfileRepository` (fenced Lua commits, `FENCED`/`STALE` replies, staged snapshots,
  `migrate-repository`) has no automated test; only the owner scenarios MP-010 exist. The distributed mode stays
  EXPERIMENTAL and unqualified.

### Plugin bridge (`bridge/`) — P2

- PM-002: distinct positions give at most one WARN per (plugin, reason) window, and `LogWindows` stays bounded.
- PM-099, PM-100: a repeating `EntityTask` whose body removes its entity is released; a repeating async timer
  keeps at most one run queued and skips instead of cancelling (the console-only `legacy-test-plugin` probe
  `LimitProbes` was adjusted to skip-not-cancel).
- PM-231, PM-232, PM-235: a kick inside a player command frame schedules without an entity anchor; repeating
  async bodies publish no scheduling context; a retirement from another thread cancels an inferred task.
- PM-233: an unrelated `IllegalStateException` that mentions region threading does not count toward quarantine.
- PM-236: TickGuard LOG mode, 10 000 hits give one line (`GuardViolations.admitEngineLog`).

### Async pathfinding (`perf/`) — P2

- PM-039, PM-242, PM-272: `AsyncPathRecompute.offload` driven into a capacity decline asserts `stats().declined()`
  and the per-type `declined`; `offload` on a thread that does not own the mob returns false; each `Delivery`
  counter from `deliver()`; a `PerfAsyncView` rows test. `AsyncPathNavigationModel` (the test mirror) mirrors
  neither the owner guard nor the declined count.
- Wave 3: the four-argument `deliver` overload runs its retired callback exactly once on `REJECTED` and on
  retirement.

### Configuration, core, commands and telemetry — P2

- PM-087, PM-088, PM-116, PM-279, PM-282, PM-284, PM-285: a global file that cannot be opened still applies
  `aurora.yml`, the security limits and the updater settings; `/maxp` persists without overwriting other edits;
  one WARN for a `*.toml` left beside its `*.yml`; `RedisSettings.KEYS` pinned; AWF and AbyssLink keys listed as
  restart-pending; one WARN per unknown key; duplicate YAML keys refused through `CommentedFileConfig.load()`.
- PM-105, PM-280, PM-281, PM-286: `/sourbycraft reload` refused while stopping and its reply delivered through
  `SourbyReply`; `console.style` read before the configuration snapshot; a nether-style dimension resolves to the
  same `canvas-patch.yml` in the engine and the Spark report; `AuroraCpu` layering of `aurora.yml` over the global
  file.
- PM-040, PM-246: an assertion on the migration INFO in the CI bootstrap boot; a `test_concurrency_policy.py` case
  for the in-tree spark sources.
- PM-086, PM-245, PM-277, PM-308: `/version` JVM option redaction; Spark world statistics with a stuck region;
  `memory.swap.max=0` read as a zero swap total (a `ContainerMemoryCgroupTest` fixture); per-world telemetry maps
  dropping unloaded idle worlds.
- PM-118, PM-222, PM-248, PM-249, PM-250, PM-289, PM-290, PM-291, PM-292: AbyssLink with an unreadable settings
  file; `/speedtest` with a failing stream and the recorded binary hash; `/awf redis` diagnostics with a rejecting
  executor; the GeoIP LRU cap; `SourbyMod.onUnload` and file-name load order (`ModLoaderTest`); the
  `sourbycraft.command.awf.admin` node; `WorldProperties.Spawn` refusals; the GeoIP retry after one hour.
- PM-317 (not done): `GeoUtilCacheBoundTest` (needs a package-private seam) and a pearl-teleport retention test.
- PM-101, PM-238, PM-271: MC patches 0084, 0039 and 0087 were hand-edited without a new test; the owner verifies
  them after `applyAllPatches` and `rebuildMinecraftFeaturePatches`.

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

The line numbers are the 2026-10-20 ones; the six "convert" rows are closed (below).

## Closed

- 2026-10-20: the six "convert" rows of the timing table. `BridgeRuntimeTest`, `AsyncPathQueueTelemetryTest`, `LaneCpuSamplerTest` use injected clocks (`BridgeRuntime.nanoClock` now also times bodies, new `AsyncPathProcessor.nanoClock`); `AsyncPathShutdownTest` and `AwfLifecycleFenceTest` poll without sleeping (no event to latch on); `RedisBackendTest` uses a 1 s lease renewed every 100 ms through a package-private `RedisBackend` constructor (production keeps `lease-seconds`, minimum 5 s) — class time 13.1 s → 4.5 s. Real time remains only where Redis's own expiry clock is the thing measured.
- 2026-10-20: rows 1-9 of the original backlog, moved here on 2026-10-25 with their text. Their residual gaps are in the first open table. Two statements were corrected in place: row 1's "`apply_mode=auto` is the default" was true when the audit ran (`notify` is the default since 2026-10-20, `UtilityKeys.UPDATER_APPLY_MODE`), and row 7's "no direct test" for `UpdateCommand`, `SpeedtestCommand` and `AwfCommand` no longer holds (`UpdateCommandReplyTest`, `UpdateIntervalStatusTest`, `SpeedtestCommandTest`, `SpeedtestRetentionTest`, `AwfCommandTest`).

### P1 (closed)

| # | Package / class | Gap (evidence) | Test to write |
|---|---|---|---|
| 1 | `update/` — **Covered 2026-10-20**: `SemVerTest`, `BuildVersionTest`, `UpdateChannelTest`, `AutoUpdateSettingsTest`, `HttpTransportTest` (local HttpServer: redirects, timeouts, size bound, scheme refusal) on top of the existing updater tests; connect timeout untested (needs an unroutable host). Original: `UpdateApplier`, `UpdateRollbackGuard`, `ReleaseSignatureVerifier`, `HttpTransport`, `SemVer`, `UpdateChannel`, `BuildVersion`, `AutoUpdateSettings` | 12 main files, one test (`update/SourbyUpdaterLifecycleTest`). The updater swaps the running jar; `apply_mode=auto` was the default when the audit ran (AGENTS.md then; `notify` since 2026-10-20). Audit SEC-2/SEC-3/SEC-9/SEC-10 fixes were in flight under the `updater-hardening` owner, who added tests for the paths they changed | Apply + rollback round trip on a temp dir (`.prev` restore, same-MC gate); signature verification accept/reject/tampered; `SemVer` ordering and prerelease handling; channel resolution from the branch name; `HttpTransport` redirect/size/timeout limits with a local HTTP server |
| 2 | `bootstrap/` — **Covered 2026-10-20**: `Sha256VerifierTest`, `BootstrapPluginSettingsTest`, `PluginProvisionerTest`, `ProtocolLibProvisionerTest` (71 tests, stub transport, offline refusal, no overwrite of operator jars/config); `--plugins` selection and `MinecraftInternalPlugin` still need a server. Original: `PluginProvisioner`, `ProtocolLibProvisioner`, `Sha256Verifier`, `BootstrapManifest`, `BootstrapPluginSettings`, `CdsEnvironment`, `SourbyBootstrap` | 10 main files, one Java test (`bootstrap/LibDownloaderTest`). `scripts/test_plugin_provisioning.py` runs installation/configuration probes against this package from Python, so provisioning is not untested, but no Java unit test covers the verifier or the manifest parser | `Sha256Verifier` mismatch/short-read/empty file; `BootstrapManifest` parse of a malformed `META-INF/libraries` entry; `PluginProvisioner` idempotence (verified jar present → no download, user config untouched) with a stub transport; offline mode refuses downloads |
| 3 | `maxplayers/` — `MaxPlayersBypass`, `MaxPlayersConfig` | **Covered 2026-10-20** (`b5766689` + the config seam commit): `MaxPlayersBypassTest`, `MaxPlayersConfigTest` (persist, boot apply, unwritable path) through `SourbyCraftConfig.installForTesting`. Original gap: no test named either class (`rg -l 'MaxPlayers' src/test` → none). `MaxPlayersBypass` flips a `KICK_FULL` login to `ALLOWED` for permission holders (`MaxPlayersBypass.java:69`); `MaxPlayersConfig.applyAtBoot()` persists `/maxp` | `PlayerLoginEvent` with `KICK_FULL` + permission → `ALLOWED`; any other kick reason untouched; listener not registered when the key is `false` (default); boot apply reads the persisted value and rejects a non-numeric one |
| 4 | `core/SourbyCraftBootstrap` stage accounting | **Covered 2026-10-20**: `core/SourbyCraftBootstrapTest` (source count of `stage("` == `TOTAL_STAGES`; a throwing stage is counted, recorded unsuccessful in `bootStages()`, later stages still run, verdict DEGRADED). `init()` itself still needs a server. Original gap: `TOTAL_STAGES = 14` was a hand-maintained constant (19 on 2026-10-25) and the file had exactly 14 `stage("` calls then; nothing checked they stay equal, and the boot bar's denominator and the COMPLETE/DEGRADED/FAILED verdict depend on it (`SourbyCraftBootstrap.java:181`, `AuroraBoot.summary`) | A source-reading test (the pattern `architecture/UnboundedQueueAuditTest` already uses) asserting the count of `stage("` equals `TOTAL_STAGES`; a unit test that a throwing stage yields DEGRADED and `bootStages()` records it |

### P2 (closed)

| # | Package / class | Gap (evidence) | Test to write |
|---|---|---|---|
| 5 | `lang/` — `SourbyMessages`, `SourbyJoinLeaveListener` | **Covered 2026-10-20** (`b5766689` + the config seam commit): `SourbyMessagesTest`, `SourbyJoinLeaveListenerTest`, no reflection on config internals. Original gap: no test named either class. Messages come from the operator-editable configuration (`sourbycraft_config/sourbycraft_global_config.yml`) | Placeholder substitution, missing key fallback, colour/MiniMessage parsing of a malformed value (must not throw at join time); join/leave listener respects an empty message (suppress vs send) |
| 6 | `hud/HudBars` | **Covered 2026-10-20**: `HudBarsRenderTest` (`b5766689`) and `HudBarsSchedulingTest` (updater/viewer lifecycle through `HudBars.installForTesting`). Original gap: no test named it (only `perf/MetricsConsumerTest` is nearby and does not cover rendering) | Bar rendering at 0 %, 100 %, >100 % and NaN inputs; width clamp; unicode vs ASCII style selection |
| 7 | `command/` — **Covered 2026-10-20** for Tps/Mspt/Sys/Maxp/Ping/Ver/HudBar/PerfBar (`TpsMsptCommandTest`, `SysCommandTest`, `MaxpCommandTest`, `PingCommandTest`, `VerCommandTest`, `HudBarCommandsTest`); booted-server paths (`ServerBuildInfo`, CPU identity) untested. `UpdateCommand`, `SpeedtestCommand` and `AwfCommand` are covered too (`UpdateCommandReplyTest`, `UpdateIntervalStatusTest`, `SpeedtestCommandTest`, `SpeedtestRetentionTest`, `AwfCommandTest`). Original: 19 main files, 6 tests (`PerfAwfView`, `PerfLanesView`, `PerfStartupAndChunkHint`, `PluginsCommand`, `SourbyReply`, `SpecCommand`) | At the time: `TpsCommand`, `SysCommand`, `UpdateCommand`, `SpeedtestCommand`, `AwfCommand` had no direct test; OBS-8 (async replies through `SourbyReply.run`) was being fixed by the `obs-java` / `updater-hardening` owners with tests for the commands they touched | Permission denial before any side effect; command-block sender gets a reply, not an exception; `/sys` and `/tps` formatting of `NaN` runtime metrics |

### P3 (closed)

| # | Package / class | Gap (evidence) | Test to write |
|---|---|---|---|
| 8 | `dev.iyanz.aurora.engine` — **Covered 2026-10-20**: 18 engine classes (157 tests: `util/map`, `configuration` upgrade, `util/migration`, `world/chunk`, `world/block`, `world`, every `subcommands` entry) + 10 `spark/*` classes (53 tests); bugs fixed in `BalancedChunkSystem`, `RegionResourceBar` codec, `NodeDiff` (comment-only YAML), `FoliaSparkPlugin` version and datapack description; server-bound subcommand execution and `gameTargetTps` still need a server. Original: 91 main files, 8 tests, of which 4 are configuration *documentation* tests; the rest cover `threadedregions` world unload (2) and `util/ticket` (2). Untested subpackages: `util/map`, `util/migration`, `util/command`, `world/chunk`, `world/block`, `subcommands`, `spark/*` | The engine has been SourbyCraft-owned since 2026-10-05 (AGENTS.md); `scripts/concurrency_policy.py` now scans it, but policy scans are not behaviour tests | `util/map` concurrent map semantics under two writers; `util/migration` upgrade of an old `canvas-server.yml` key set (idempotent, unknown keys preserved); `world/chunk` helpers against a fixed fixture; one test per `subcommands` entry asserting permission and output shape |
| 9 | `sourbyapi` — **Covered 2026-10-20**: 58 tests for the former Canvas plugin API, `dev.iyanz.sourbycraft.api.{event,region,simd,world}` since Build 48 (events, handler lists, `callEvent`, `region` surface with return types, `simd`, `WorldUnloadResult`, `Unsupported`); the main-thread guard and region refusals live in the server implementation. Original: 2 tests (`MetricsApiSurfaceTest`, `WorldRequestTest`) | API default methods (`RuntimeMetrics` build-44 fallbacks returning `-1`/`NaN`) are pinned by `MetricsApiSurfaceTest`; the region-threading plugin API (`event`, `region`, `simd`) has none | Event construction/`callEvent` on the main thread guard; `region` API refusal paths |
