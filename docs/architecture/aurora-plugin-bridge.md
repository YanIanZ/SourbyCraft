# Aurora Compatibility Bridge

Aurora Bridge adapts supported classes of legacy Paper/Spigot plugin behavior to SourbyCraft's region-threaded runtime.

## States
- NATIVE — declares Folia or SourbyCraft support.
- BRIDGED — legacy plugin successfully enabled through supported mappings.
- FAILED — load/enable/fatal bridge failure.
- DISABLED — operator-disabled.

## `/plugins` color contract
- Native: `#4DA3FF`
- Bridged: `#57D68D`
- Failed: `#FF5C70`
- Disabled: `#8B949E`

Green means “running through Aurora Bridge”; it never means official Folia support.

## Safety
Default mode is OFF. Operators can enable SAFE on a test server; unknown/unsafe world mutation is rejected and diagnosed rather than run on an arbitrary thread.

## Routing
Entity-owned -> entity owner. Location-owned -> region owner. Global-safe -> global region. I/O -> plugin/I/O lane. Unknown unsafe -> reject.

| Request (bridged plugin, Bukkit scheduler) | Route | Caller |
| --- | --- | --- |
| Sync callback wrapped in `EntityTask` | Entity owner (Folia entity scheduler); follows the entity; entity removed first -> cancelled, counted rejected, not a violation | `EntityTask` (patch 0011), IMPLEMENTED, unit-tested; boot fixture not yet run in CI |
| Sync callback wrapped in `RegionTask`, or the fingerprinted SSB2 spawn callback | Region owning that chunk | patch 0010 |
| Other sync callback | Caller region (`sync-route = caller-region`) or global region | default |
| Explicit global request, async task | Global region / I/O lane; target metadata never consulted | 0007, 0003 |

Precedence: explicit entity > explicit region > caller-region/global fallback.

## Telemetry
Per-plugin compatibility state, scheduler redirects, owner handoffs, rejected operations, fatal violations, quarantine, startup duration/cache state and last failure.

- **Task body time** (IMPLEMENTED, LIVE, no setting): per plugin and lane, `/plugins <name>` shows "Sync body time (region / global)" and "Async body time" (count, mean, p50, p99, max; percentiles over the last 256 bodies) and `/perf plugins` shows sync body total / p99. This is the wall time of the task body on the thread that ran it, including bodies that threw; queue wait is not included.

## Implementation status (26.2 branch)

Code: `dev.iyanz.sourbycraft.bridge` plus bridge feature patches under
`sourbycraft-server/paper-patches/features` (0003, 0006, LightingLuminol port 0007
and worker/query follow-up 0008, native-disable fix 0009, explicit callback owners 0010,
entity callback owners 0011).
Feature patches are the source of truth for edits
to Paper classes. The 0007/0008 materialized sources are present; build/testing of
this increment remains assigned to Claude.

- **Mode.** `aurora.bridge.mode` in `aurora.toml` (or the unified file): `off` (default) keeps the
  base's refusal; `safe` admits. It is read straight from the TOML files on first use, because
  plugin providers are built during Paper bootstrap, before `SourbyCraftBootstrap`; it is
  RESTART_REQUIRED. A malformed value means `off`. `off` is the default rather than SAFE because
  correctness outranks compatibility in the safety order: admitting unqualified code is the
  operator's decision.
- **Admission.** `PaperPluginProviderFactory`, `SpigotPluginProviderFactory` and
  `CraftMagicNumbers#checkSupported` call `AuroraBridge.admitLegacy(name)` before throwing.
- **Routing.** `CraftScheduler#handle` hands a bridged plugin's task to the bridge instead of
  throwing. Sync callbacks naming a target through `RegionTask` use the region owning that chunk.
  A fingerprinted SuperiorSkyblock2 2026.3 spawn initialization callback also supplies a target;
  see [source scope and pending tests](../development/SUPERIORSKYBLOCK-BRIDGE.md).
  Otherwise sync tasks scheduled from a region use that caller region by default; tasks from
  startup, console, global or async contexts use the global region. Async tasks are
  timed by Folia's async scheduler and run on the Resource Governor's bounded `BRIDGE_IO` lane.
  `cancelTask`/`cancelTasks` cancel bridged tasks; disable cancels them too, because Folia no
  longer does for the Bukkit scheduler. Sync callbacks wrapped in `EntityTask` take the
  entity-owner route: `entity.getScheduler().run/runDelayed/runAtFixedRate` with a retired
  callback that cancels the bridged task, frees both task indexes and counts one rejected
  operation (last failure names the retirement; no violation, no quarantine count, no per-task
  log line). Entity routing only happens when the plugin author wraps the task; the bridge does
  not infer an entity from an opaque callback. Caller-region fallback does not establish
  ownership of other locations accessed by a task.
- **Violations.** A task body's `UnsupportedOperationException`/`IllegalStateException` from the
  base's region checks counts as a fatal violation. Other exceptions are recorded as the last
  failure, as the plain server would log them. After `aurora.bridge.quarantine-after` (LIVE,
  default 3) violations, the plugin's bridged tasks are cancelled and new ones are rejected. The
  plugin is not disabled: disabling would run plugin code on whichever thread noticed the
  violation.
- **Telemetry.** Per plugin: scheduler redirects, owner handoffs (region- and entity-owner task routing),
  rejected operations, fatal violations, quarantine, startup cache state and last
  failure. `/plugins <name>` shows them.
- **Not intercepted.** A bridged plugin calling world API directly from a bridged task on the
  global region is not rewritten; the base's own thread checks decide what happens. Async tasks
  run on the governed lane; the worker follow-up below exposes those threads to Bukkit callers.
- **Sync route.** `aurora.bridge.sync-route` (LIVE), default `caller-region`: a legacy sync task
  scheduled while a region is ticking runs on that region, anchored on a chunk it owned
  (`execution/region/CurrentRegion`, implemented by the existing `FoliaRegionBackend` adapter; anchors cached per region id). From anywhere
  else it runs on the global region. `global` selects the fallback for unanchored tasks;
  an explicit target still takes precedence. Explicit global scheduler requests remain global.
  Tasks routed to
  a region count as owner handoffs.
- **Scheduler queries.** `isQueued` and `getPendingTasks` use the bridge task index.
  Follow-up patch 0008 makes `isCurrentlyRunning` consult invocation counts even after
  cancellation removes that index entry. `getActiveWorkers` merges actual bridge async
  worker snapshots once with Bukkit's own workers; sync and queued callbacks are excluded.
- **Operator guide:** [testing legacy plugins](../guides/testing-legacy-plugins.md).
- **CI evidence (run 440, `7c2bb0db`, 2026-09-27).** The Paper patches applied. A second CI boot
  with `aurora.bridge.mode = "safe"` loaded `legacy-test-plugin` (no `folia-supported`):
  - admission line logged; all 13 boot stages online;
  - `runTask` ran on `Folia Region Scheduler Thread #3` (the global region);
  - `runTaskAsynchronously` ran on `SourbyCraft-BridgeIO-1`;
  - a self-cancelling `runTaskTimer` ran 3 times and never after cancel;
  - a world access from a sync task was refused by the base (`IllegalStateException`) and
    counted as 1 fatal violation, below the quarantine threshold;
  - `/plugins LegacyBridgeTest` reported 4 scheduler redirects; `/perf governor` reported Bridge
    I/O 1 submitted, 1 completed, 0 rejected; clean exit.
- **CI evidence (run 445, `d919ea66`, 2026-09-27), caller-region route.** `runTask` called from a
  chunk-load callback ran on `Folia Region Scheduler Thread #0` and read the block there
  (`LEGACY_BRIDGE_REGION_SYNC_OK ... block=AIR`). The same kind of access from a task scheduled in
  `onEnable` ran on the global region and was refused (`WORLD_ACCESS_REFUSED`, 1 fatal
  violation). `isQueued` saw a bridged task (`QUEUED_OK`). `/plugins`: 6 scheduler redirects,
  1 owner handoff, not quarantined; 13 boot stages online.
- **CI run 450 (`52568fb0`).** `getPendingTasks` listed a bridged task (`PENDING_OK`). `/perf
  plugins` showed `bridged 1` and `LegacyBridgeTest redirects / handoffs / rejected /
  violations: 6 / 1 / 0 / 1`.
- **Panel staging (2026-09-29, `9a15f51`).** With `mode = "safe"`, real Vault
  `1.7.3-b131` and EssentialsX `2.22.1-dev+25-cfb6f12` enabled through the bridge.
  EconomyShopGUI-Premium `6.4.1` loaded and hooked into Vault/EssentialsX Economy. `/plugins`
  reported 6/6 active, 2 bridged, 0 failed; at the post-boot sample, Essentials had 5 scheduler
  redirects and Vault 2, with 0 rejected operations or fatal region violations for both.
  This is startup and economy-hook evidence on the demo panel, not gameplay or soak qualification.

## Qualification
A plugin cannot be presented as BRIDGED until load, enable and bridge initialization succeed and no fatal compatibility violation is present.

## What the bridge is not

A design limit, stated in [compat-vs-performance.md](compat-vs-performance.md) §3: the bridge
**admits, routes, counts and quarantines**. It cannot make a plugin region-aware. Routing picks a
thread for a task; it cannot supply the knowledge of which region owns the state the task touches
when the plugin's author never wrote it down. `RegionTask` and `EntityTask` are how an author
writes it down; an unwrapped callback still gets only the caller-region/global fallback.

The two fixes produced on 2026-10-06 for SuperiorSkyblock2's `SpawnIsland` violation show the two
possible endpoints:

- **Adapter inside the bridge:** a hash-pinned owner for one class of one plugin version
  (`bridge/SuperiorSpawnTaskOwner`, patch 0010;
  [SUPERIORSKYBLOCK-BRIDGE.md](../development/SUPERIORSKYBLOCK-BRIDGE.md)). It covers that one
  callback in the 2026.3 jar and nothing else.
- **Native port of the plugin:** SuperiorSkyblock2 rescheduled onto Folia's global, region, entity
  and async schedulers (local branch `folia` in `~/Sourby/ssb/SSB2`, outside this repository;
  `FOLIA.md` there). Its `plugin.yml` declares `folia-supported: true`, so after the port the plugin
  is NATIVE and **no longer passes through the bridge at all**; the adapter would never fire for
  the ported jar.

Full compatibility therefore means a native port; the bridge is the safety and diagnosis layer for
plugins that have not had one. BRIDGED is not a compatibility verdict (see *Qualification*).

Bridge telemetry so far has been counts with no time dimension: it records how many tasks a plugin
had redirected, handed off, rejected or failed, never how long they ran, so it cannot attribute tick
time to a plugin. Per-plugin execution timing of bridged task bodies is being added on 2026-10-07
by another agent (in progress, untested); its description belongs in *Telemetry* above once it
lands.

## Multi-plugin task lifecycle (2026-10-06)

Each admitted plugin has its own task index. `pending(plugin)` reads that index;
`cancelTasks(plugin)` scans only that plugin's tasks. The global task-ID index remains for
Bukkit's task lookup and all-plugin pending-task listing. Per-plugin monitors cover admission,
index mutation and running-slot bookkeeping; scheduling, cancellation handles and task bodies
run outside them.
Completion, explicit cancellation and scheduler rejection remove both indexes. Running-state
queries count overlapping async invocations until the last active body finishes, including
bodies still draining after cancellation.

Plugin disable closes task admission before draining the plugin's index. A submission already
waiting for a scheduler handle is cancelled when that handle arrives, and subsequent submissions
are rejected. Ordinary `cancelTasks` keeps admission open. Fatal ownership quarantine remains
per plugin; one plugin's cancellation/quarantine does not drain another plugin's index.

If scheduling throws, the task is cancelled, removed and diagnosed instead of left pending.
When the existing bounded `BRIDGE_IO` lane rejects a timed async invocation, the task (including
its repeating timer, if any) is cancelled and the plugin receives a warning, a last-failure
message and a rejected-operation count. Saturation does not count as a fatal region violation.
Work is never run on a region thread as an overload fallback. This is fail-closed overload
handling, not a guarantee of fair capacity distribution between plugins.

SAFE remains opt-in and RESTART_REQUIRED; existing route and quarantine settings retain their
LIVE behavior. No extra executors or worker budgets are introduced. These changes improve task
bookkeeping and failure isolation; they do not infer an entity/world owner from an anonymous
Bukkit task or make arbitrary NMS/world access region-safe. Real-plugin gameplay and soak
qualification remains required. AWF integration: [plugin API guide](../guides/developing-awf.md).

Functional verification (2026-10-06, Temurin 25.0.4.1): BridgeRuntimeTest 24/24 passes,
including 100 simulated plugin task indexes, scheduler rejection, I/O saturation, disable racing
admission, duplicate IDs and overlapping async invocation state. The isolated full API/server
suites also pass; command/log/report provenance and skipped counts are in SPEC §155. This
is bookkeeping/failure-isolation evidence, not qualification of 100 real legacy plugins.

## LightingLuminol / Luminol port (source materialized; Claude testing pending)

Feature patch 0007 and `FoliaSchedulerCompatibility` adapt Bacteriawa's upstream
global scheduler compatibility and Bukkit task cleanup on disable. Explicit global
requests use the existing per-plugin bridge lifecycle while retaining global
ownership regardless of `sync-route`. The global backend has separate native entry
points to avoid recursion. Cancellation/rejection notifies the returned
`ScheduledTask` adapter; global cancellation drains only global-backed entries of
the target plugin, and the plugin manager closes admission before disable callbacks.

The upstream redirects of named region/entity requests are omitted so their native
ownership and entity retirement contract remain in place. SAFE stays opt-in and
RESTART_REQUIRED; no executor or configuration surface is added. Direct world API
access remains subject to the runtime's thread guards.

Credit: **Luminol**, **LightingLuminol**, **Bacteriawa**, and contributors. The derived
adapter and patch retain upstream GPL v3 licensing. Exact source revision, imported
files, adaptations, packaged notices and Claude's pending acceptance cases are in
[the port handoff](../development/LIGHTINGLUMINOL-BRIDGE-PORT.md). Patch 0007 is present
in the materialized Paper source at commit `425610c83`; no new build/test result is
claimed here. The earlier functional evidence above does not cover this increment.

## Per-plugin limits and async worker follow-up (Claude testing pending)

Two optional LIVE settings in `[aurora.bridge]` default to `0` (disabled):

| Setting | Admission point | On reaching the limit |
| --- | --- | --- |
| `max-pending-tasks-per-plugin` | Submission, under that plugin's index monitor | Cancel/reject the new task before scheduling or inserting either index |
| `max-running-async-tasks-per-plugin` | Async callback start, under that plugin's admission monitor | Cancel the rejected task and its timer; already-running bodies drain |

Values are integers in `0..100000`. Invalid values fall back to `0` and are diagnosed
as invalid config. Limits are independent per plugin. Pending means indexed tasks,
including running tasks that have not been cancelled/completed; one repeating timer
occupies one entry. Async running counts callback invocations, so overlaps of the
same task each consume a slot. Cancellation/disable removes the pending entries but
does not release running slots until each body actually finishes.

Capacity rejections update rejected operations, last failure and the warning log;
they do not increment fatal region violations or quarantine the plugin. Ordinary
completion/cancellation frees capacity for subsequent submissions. Lowering a LIVE
limit does not cancel existing pending work or interrupt running bodies; it changes
admission of new submissions/async callbacks. Sync work does not consume async slots.

These settings bound indexed tasks and running async callbacks, respectively. They
do not reserve the shared I/O queue or guarantee fair CPU time between plugins.
The Resource Governor's existing queue and worker budget still apply. There is no
extra executor, waiting admission queue, task coalescing or silent inline fallback.

`/plugins <name>` exposes pending/running async counts and their active limits.
Worker snapshots retain the actual plugin, Bukkit task ID and governed-lane thread
until the body drains, including after cancellation/quarantine/disable. They are
best-effort snapshots rather than an atomic view across every query. Task-ID
allocation excludes indexed and still-running bridge IDs when its counter wraps.

Source and patch 0008 are written/materialized. Unit/boundary tests ran on 2026-10-07 (see
*Review 2026-10-07* below); no boot, real-plugin or performance result is claimed. Acceptance
cases are in the linked port handoff.

## Review 2026-10-07 (uncommitted bridge work: 0007/0008/0010, limits, body timing)

Paper patch 0010's materialized change is now committed in `paper-server` (`a31e11842`, author and
date from the patch header); `patch_surface.py --check-rebuild` no longer predicts deleting
Paper 0010.

Defects found and fixed (unit-tested only; no boot or real-plugin run):

- **Disable closed admission permanently** (SPEC B86). `disable` set a per-plugin flag that
  nothing cleared, so a bridged plugin loaded again under the same name had every task rejected.
  `admit` now reopens it; the loader calls `admit` only for a (re)loaded instance.
  `BridgeReviewTest.aPluginLoadedAgainAfterDisableCanScheduleAgain`. The same instance enabled
  again after `disablePlugin`, without a reload, also stayed closed; fixed 2026-10-07 by Paper
  feature patch 0012 (`PaperPluginInstanceManager#enablePlugin` calls
  `AuroraBridge.onPluginEnabled` before `onEnable`, which runs `BridgeRuntime.reopen`). `reopen`
  only clears the disable flag of a plugin the bridge already admitted: a native or never-admitted
  plugin is not admitted by it, and quarantine is not lifted.
  `BridgeReviewTest.theSameInstanceEnabledAgainAfterDisableCanScheduleAgain`,
  `reopenNeverAdmitsAPluginTheBridgeDidNotAdmit`, `reopenDoesNotLiftQuarantine`,
  `theEnableHookIsANoOpWithoutABridgeOrPlugin` (unit tests; no boot or real-plugin re-enable run).
- **SuperiorSkyblock2 resolver NPE on a missing plugin meta** (SPEC B87). Now refused with the
  adapter's own `IllegalArgumentException` before admission, never counted as a violation.
  `BridgeReviewTest.superiorCallbackWithoutMetaIsRefusedBeforeAdmissionWithoutViolation`.

Checked and judged correct, with regression/boundary tests added in `BridgeReviewTest`:
pending limit admits exactly N and rejects N+1 before the executor, per plugin, freed by
completion, not fatal; `0` is unlimited; running-async limit rejects only the overlapping
invocation (cancelling the repeater, as documented) and releases its slot in `finally`; a cancelled
async body stays visible to `running`/`activeWorkers`/`runningAsync` until it finishes;
`RegionTask.at` floors negative coordinates and snapshots them. Also reviewed without new tests:
`FoliaSchedulerCompatibility` state machine against `bridgeCancelled` and `cancel()` (including
cancellation before `setBukkitTask`), quarantine classification (capacity and resolver failures
never reach `onFailure`), and body-time recording under concurrent completion.

Correct but fragile:

- An entry leaves the indexes only when its body runs, it is cancelled through the bridge, or
  the plugin is disabled/quarantined. Work the backend drops without running it (Folia
  `AsyncScheduler#cancelTasks` called directly by the plugin, a region task for a world that
  unloads) stays indexed and counts toward `max-pending-tasks-per-plugin` until disable.
- `GlobalRegionScheduler#cancelTasks` also drains ordinary Bukkit sync tasks that fell back to the
  global region, because the native cancellation already cancels them.
- Body-time count/total and the percentile ring are each consistent, not one atomic snapshot.
- `ViolationClassifier` matches on exception type plus message/stack shape; an unrelated
  `IllegalStateException` whose message contains "region threading" would count.

Entity-owner routing (added after this review, unit-tested in `BridgeTargetRoutingTest` `v39*`,
SPEC V39): entity bodies are timed in the existing region lane, so `/plugins <name>` rows are
unchanged. Fragile: a repeating `EntityTask` whose own body removes its entity is dropped by
Folia's entity scheduler without a retired call, so it stays indexed until disable (same class
as the dropped-work case above). Fixture markers `LEGACY_ENTITY_TASK_OK` and
`LEGACY_ENTITY_RETIRED_OK` are required by CI; the plugin cannot read bridge counters, so the
retired marker means "body did not run within 3 s, task cancelled and not queued". Not yet run
in CI.

The fixture now logs `LEGACY_REGION_TASK_OK|_WRONG` for `RegionTask.at` scheduled from
`onEnable`; CI requires `_OK`. Its first CI run has not happened yet. A pending-limit fixture
case was skipped: the limit is read only from `sourbycraft_config` TOML, not the plugin's config.
