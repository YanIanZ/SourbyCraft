# LightingLuminol / Luminol bridge port

Status (2026-10-06): adaptation source and feature patches written for SourbyCraft
26.2. Paper's materialized HEAD already contains port 0007 (`425610c83`); Codex has
also applied follow-up patch 0008 to the materialized source. Codex has not run a
build or tests for this increment. Testing and acceptance are assigned to Claude
by the user. Materialization is not evidence of plugin qualification, improved
throughput, or release readiness.

## Status update 2026-10-07 (what is applied, tested and run)

Sources: [AGENT-COORDINATION.md](../../AGENT-COORDINATION.md), `sourbycraft-server/build/test-results`,
`.github/workflows/build.yml` and `git` in the materialized `paper-server` tree. The paragraph above is the
2026-10-06 state when the patches were written; it is superseded where it says no build or test was run.

- **Applied and committed.** 0007 is `425610c83`, 0008 is `4c61570c2` (committed as-is from the patch header by
  Claude, log "bridge review, 2026-10-06 21:15"). A real-boot regression found and fixed on top of 0007: native plugins
  logged `Unsupported in region threading` at every disable because `cancelTasks` fell through to the legacy cancel task;
  fixed by patch 0009 (`2e3cef719`, same log entry). Later bridge patches on the same files: 0010 `a31e11842` and 0011
  `441f8e56e`. The patch files 0007, 0008, 0010 and 0011 are still untracked in the root repository (`git status`
  2026-10-07). The adapter file set and the GPL-3 attribution below are unchanged.
- **Unit tests (Gradle, `:sourbycraft-server:test`).** After 0007 was applied the full suites were Gradle 10152/10152 and
  Python 302 (log, "Claude update (after 0007 applied)"). The bridge package result set of 2026-10-06T18:26Z
  (`build/test-results`, XML timestamps) is 61 tests, 0 failures, 0 errors: `BridgeRuntimeTest` 29, `BridgeTargetRoutingTest` 9
  (3 V36 + 6 V39), `BridgeReviewTest` 7, `CompatibilityClassifierTest` 6, `ViolationClassifierTest` 5,
  `AuroraBridgeEarlyConfigTest` 3, `BridgeRouterTest` 2. An earlier run recorded bridge tests 48/48 (log, 2026-10-07 plugin-cost
  agent). `BridgeReviewTest` covers part of the follow-up limits: `pendingLimitAdmitsExactlyTheLimitAndCompletionFreesASlot`,
  `zeroLimitsAreUnlimited`, `runningAsyncLimitRejectsTheOverlapOnlyAndReleasesTheSlot`,
  `aCancelledAsyncBodyStaysVisibleUntilItFinishes`. `BridgeRuntimeTest` covers pending-index cleanup, `isQueued`/
  `isCurrentlyRunning`, overlapping invocations and per-plugin independence (method names in that file).
  **No unit test targets `FoliaSchedulerCompatibility` (the adapter's `ScheduledTask` state machine)**; its
  states are exercised only by the fixture below.
- **CI boot evidence: none yet for the new markers.** The `LEGACY_GLOBAL_*`, `LEGACY_REGION_TASK_OK`, `LEGACY_ENTITY_*` and
  `LEGACY_DISABLE_*` checks in `.github/workflows/build.yml`, and the fixture code that prints them, are uncommitted
  working-tree changes as of 2026-10-07 (`git diff HEAD`), so no CI run has executed them. The log says the newer markers
  are "not yet run in CI".
- **Acceptance cases run on Sourby Demo, 2026-10-06, through the legacy fixture markers `LEGACY_GLOBAL_*`:** all `_OK`,
  as reported by the coordinating Claude agent. The raw server log is not archived in this repository or under `~/Sourby`
  (searched 2026-10-07), so this document cannot link evidence beyond that report. Mapping of marker (fixture code in
  `LegacyBridgePlugin.globalScheduler`) to the first-list cases:
  case 1 (execute, run, delayed, fixed-rate, call from a region): `LEGACY_GLOBAL_EXECUTE`, `_RUN`, `_DELAYED` (>= 400 ms),
  `_FROM_REGION`, plus `_REPEATER_SELF_CANCEL` / `_REPEATER_STOPPED` for the fixed-rate timer;
  case 3 (idle cancellation, running one-shot, self-cancelling repeater, repeated cancellation, finished one-shot):
  `_IDLE_CANCEL`, `_ONESHOT_STATES`, `_REPEATER_SELF_CANCEL`, `_REPEATER_STOPPED`;
  case 4 partly (one plugin: `GlobalRegionScheduler#cancelTasks` removes global tasks and leaves an async Bukkit task):
  `_CANCEL_TASKS`.
- **Not run as of 2026-10-07:** case 2 (native plugins and mode OFF; only patch 0009's real-boot fix touches it); case 3 beyond
  the fixture states (handle-publication races, rejection, disable and quarantine); case 4 with several plugins; case 5
  (the `LEGACY_DISABLE_*` markers exist in the fixture and in CI, but no result is recorded); case 6 (entity retirement is
  covered by the `v39*` unit tests; an unsafe world access from a global callback has no recorded run); case 7 (packaged JAR
  notices, not inspected in the log); case 8 (representative real plugins, soak, AWF interaction). Follow-up cases 1-6 are
  covered only where the test names above say so; config bounds, malformed values, LIVE reload counts (follow-up 1) and
  ID-counter wrap (follow-up 5) have no recorded result. Compiling, booting and passing these tests do not qualify plugin
  compatibility or performance.

## Source and credits

Credit **Luminol**, **LightingLuminol**, **Bacteriawa
<A3167717663@hotmail.com>**, and their contributors/upstreams.

Repository: [LuminolCustomArchive/LightingLuminol](https://github.com/LuminolCustomArchive/LightingLuminol).
Pinned source: [`72d51c4d11b1ddfe70cde21f4736fdf660873cb3`](https://github.com/LuminolCustomArchive/LightingLuminol/tree/72d51c4d11b1ddfe70cde21f4736fdf660873cb3),
branch `ver/26.1.2`. Original patch date: 2026-06-06.

| Upstream file at the pinned revision | SourbyCraft treatment |
| --- | --- |
| [`FoliaSchedulerCompatibility.java`](https://github.com/LuminolCustomArchive/LightingLuminol/blob/72d51c4d11b1ddfe70cde21f4736fdf660873cb3/lightingluminol-server/src/main/java/meow/bacteriawa/lightingluminol/core/FoliaSchedulerCompatibility.java) | Adapt Bukkit-backed `ScheduledTask`, execution/cancellation states and plugin-enabled checks into `dev.iyanz.sourbycraft.bridge.FoliaSchedulerCompatibility` |
| [`paper-patches/features/0003-Folia-scheduler-compatibility.patch`](https://github.com/LuminolCustomArchive/LightingLuminol/blob/72d51c4d11b1ddfe70cde21f4736fdf660873cb3/lightingluminol-server/paper-patches/features/0003-Folia-scheduler-compatibility.patch) | Port global scheduler routing and Bukkit scheduler cleanup on disable into feature patch 0007; preserve original author/date |
| [`minecraft-patches/features/0002-Folia-scheduler-compatibility.patch`](https://github.com/LuminolCustomArchive/LightingLuminol/blob/72d51c4d11b1ddfe70cde21f4736fdf660873cb3/lightingluminol-server/minecraft-patches/features/0002-Folia-scheduler-compatibility.patch) | Inspect only; keep named region/world/chunk ownership rather than redirecting it to the global scheduler |
| [`luminol-patches/features/0004-Folia-scheduler-compatibility.patch`](https://github.com/LuminolCustomArchive/LightingLuminol/blob/72d51c4d11b1ddfe70cde21f4736fdf660873cb3/lightingluminol-server/luminol-patches/features/0004-Folia-scheduler-compatibility.patch) | Use existing Aurora bridge admission instead of importing Luminol config modules, enabled-by-default routing or force-scheduler lists |

Upstream's entity redirect is also omitted: the native entity scheduler retains
entity movement ownership, retirement callbacks and its nullable admission result.
Thread-guard bypasses and blanket `folia-supported` admission changes are outside
this port. Existing SAFE admission remains the only legacy admission path.

## Integration

Feature patch:
[`0007-SourbyCraft-adapt-LightingLuminol-global-scheduler-bridge.patch`](../../sourbycraft-server/paper-patches/features/0007-SourbyCraft-adapt-LightingLuminol-global-scheduler-bridge.patch).
It targets the current SourbyCraft Paper baseline plus bridge patches 0003/0006;
no generated source edits are the deliverable.

The intended request path is:

`GlobalRegionScheduler -> Bukkit-backed ScheduledTask -> CraftScheduler -> BridgeRuntime -> native global scheduler backend`.

The callback uses Bukkit's existing task-ID allocation and pending-task handle.
The bridge retains its per-plugin/global task indexes, bounded I/O budget and
exception classifier. No second task registry or executor is introduced.

`GlobalCallback` is a sealed internal marker: only the adapter can request explicit
global routing and cancellation notification. Its `global()` route prevents a
call made from a region from inheriting `caller-region`. The backend calls
`runDelayedNative` / `runAtFixedRateNative` so it does not enter the public bridge
hook again. Public `execute` and `run` reach the adapted `runDelayed` through their
existing delegation; fixed-rate requests use the matching hook.

`BridgeRuntime` records the selected route per task. Global scheduler cancellation
removes this plugin's global-backed tasks from both indexes before cancelling the
native handles; region and async entries remain. This also cleans up ordinary
Bukkit tasks backed by that same global scheduler, which its native cancellation
already covers. Bukkit `cancelTasks(plugin)` continues to cancel all that plugin's
bridged tasks. Ordinary cancellation leaves admission open.

Plugin manager disable closes bridge admission before `PluginDisableEvent` and
`onDisable`, so cleanup does not depend on the bootstrap listener being installed.
The existing listener is retained as an idempotent fallback. The upstream Bukkit
task cancellation hook also runs after disable, alongside native scheduler cleanup.
Running task bodies are not interrupted. A running one-shot finishes; cancelling
a running repeater prevents future invocations and reports `CANCELLED_RUNNING`
until the body ends. Bridge rejection, disable and quarantine notify the adapter
instead of leaving its reported state `IDLE`.

Configuration: `aurora.bridge.mode` stays `off` by default and RESTART_REQUIRED.
`sync-route` and `quarantine-after` remain LIVE. `sync-route` controls anonymous
Bukkit sync tasks; explicit global scheduler requests always use the global region.
The original 0007 port adds no configuration key. The follow-up below adds optional
LIVE capacity limits. A restart with rebuilt/patched sources is required to install
the code changes.

## SourbyCraft follow-up: capacity limits and Bukkit worker API

Follow-up patch:
[`0008-SourbyCraft-expose-bridged-async-workers-and-draining-tasks.patch`](../../sourbycraft-server/paper-patches/features/0008-SourbyCraft-expose-bridged-async-workers-and-draining-tasks.patch).
This is a SourbyCraft extension; the imported LightingLuminol file set and its
attribution remain as listed above.

`BridgeRuntime` adds opt-in pending-task and running-async limits for each plugin.
Both default to `0`, preserving prior admission/overlap behavior. Pending admission
and async slot acquisition share the plugin's existing monitor with disable and
index mutation. Scheduling, callbacks, logging and handle cancellation remain
outside that monitor. Over-capacity tasks are cancelled and diagnosed without
quarantine; running bodies drain and release their slots in `finally`.

Async worker snapshots contain the original plugin, task ID and actual execution
thread, and survive task-index removal until execution completes. No new thread,
executor or admission queue is created. `CraftScheduler.getActiveWorkers()` merges
those snapshots once, and `isCurrentlyRunning()` also consults callbacks still
draining after cancellation. The ID allocator excludes indexed/draining bridge IDs
at counter wrap. `/plugins <name>` shows pending/running counts and their limits.

Configuration in `[aurora.bridge]`:

```toml
max-pending-tasks-per-plugin = 0
max-running-async-tasks-per-plugin = 0
```

Both are LIVE integers in `0..100000`, parsed during early bootstrap with the same
file precedence as the existing bridge keys and read from loaded settings after
configuration load/reload. Lowering limits affects new admission rather than
interrupting or discarding admitted bodies. Running limits count actual async
callbacks, not queued I/O work; shared queue capacity/fair CPU scheduling are not
guaranteed by these per-plugin limits. A rejected repeater loses future invocations,
which is why limits are opt-in. See the architecture document for precise semantics.

## Licensing

Upstream's `LICENSE.md` states GNU GPL version 3 for authors outside its MIT
exemption list. The selected patch author Bacteriawa is outside that list. Derived
source and patch retain that upstream licensing and attribution; the root project
license does not replace it.

Verbatim upstream texts and an explicit mapping of their original relative paths
are retained in
[`META-INF/licenses/NOTICE_LIGHTINGLUMINOL`](../../sourbycraft-server/src/main/resources/META-INF/licenses/NOTICE_LIGHTINGLUMINOL),
`LICENSE_LIGHTINGLUMINOL`, `LICENSE_LIGHTINGLUMINOL_GPL`, and
`LICENSE_LIGHTINGLUMINOL_MIT`. The MIT text accompanies upstream's general notice;
it is not the license assigned to this adapter/patch. Existing Luminol/Folia notices
are retained.

## Handoff to Claude — not executed by Codex

Claude should materialize the feature patch, build with the branch's pinned private
toolchain and Temurin 25, and test these behaviors before closing the TODO item:

1. SAFE legacy plugin: global `execute`, `run`, delayed and fixed-rate callbacks,
   including calls originating on a region. Verify global ownership and no scheduler
   recursion; inspect redirect counters and pending-task cleanup.
2. Native plugins and mode OFF: native scheduler behavior and legacy admission
   remain consistent with the existing contract; no adapter is created for natives.
3. Cancellation states: idle cancellation, running one-shot, self-cancelling
   repeater, repeated cancellation and finished one-shot. Cover handle-publication
   races, rejection, disable and quarantine, including cancellation while a repeater
   body is running. Both indexes must drain and the returned state must agree.
4. Multiple plugins with global, caller-region and async tasks: global cancellation
   removes only the target plugin's global-backed entries; Bukkit cancellation and
   disable remove all its entries without affecting other plugins. Admission remains
   open after ordinary cancellation and closes before disable callbacks.
5. Disable before listener registration, and task submission attempted from
   `PluginDisableEvent`/`onDisable`: no orphaned task or late handle survives.
6. Region/entity schedulers: entity retirement, entity movement and named region
   ownership retain their native behavior. A global callback attempting an unsafe
   world access still reaches the thread guard and bridge violation classifier.
7. Packaged JAR retains the LightingLuminol notice and verbatim license texts.
8. Representative real-plugin gameplay and soak, including interaction with AWF,
   remain separate acceptance gates; compiling or booting alone does not qualify
   compatibility or performance.

Follow-up cases for Claude (no Codex executions/results):

1. Config limits: default `0`, constructor compatibility, integer bounds, malformed
   values, early TOML precedence, and LIVE reload counts. Existing operator files
   must retain their bytes. Native plugins must keep their scheduler behavior.
2. Pending capacity: exact boundary under concurrent submissions, independent plugin
   budgets, ordinary cancellation/completion freeing capacity, rejection before
   executor submission, global adapter state after rejection, disable/late-handle
   races. Reload lowering a limit must not cancel existing pending entries.
3. Running async capacity: independent plugin slots and overlapping callbacks of one
   timer, cancellation while bodies drain, release on callback exception, rejected
   repeating timer cleanup, LIVE lowering/raising, default-unlimited overlap. Fatal
   counters/quarantine must remain unchanged on capacity rejection.
4. Worker/query API: actual owner/ID/thread; queued and sync tasks absent; overlapping
   invocations visible; cancelled/disabled/quarantined bodies remain visible until
   their last invocation ends; snapshots/slots drain on every exit path. A stale
   executor callback must not cancel another task that reuses its ID.
5. `isCurrentlyRunning` remains true while a cancelled body is draining and turns
   false only after completion; `getActiveWorkers` merges each worker once. At ID
   counter wrap, both scheduled and draining bridge IDs must be excluded.
6. `/plugins` displays counts/limits without starting the bridge for native plugins.
   Rejection logs/last-failure text identify the limit; no region-thread overload
   fallback occurs. Preserve existing pending/global cancellation behavior.

Suggested existing commands for Claude: `./gradlew applyAllPatches`,
`./gradlew :sourbycraft-server:compileJava`, and the relevant server/test-plugin
tests plus patch-policy tests. Add focused adapter/routing regressions and record
actual commands/results here. When this was written (2026-10-06) no outcomes were recorded; the outcomes known on
2026-10-07 are in the status update at the top of this document.
