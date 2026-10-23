# LightingLuminol / Luminol bridge port

Status (2026-10-06): adaptation source and feature patches written for SourbyCraft
26.2. Paper's materialized HEAD already contains port 0007 (`425610c83`); Codex has
also applied follow-up patch 0008 to the materialized source. Codex has not run a
build or tests for this increment. Testing and acceptance are assigned to Claude
by the user. Materialization is not evidence of plugin qualification, improved
throughput, or release readiness.

## Second port 2026-10-22c: relaxed thread checks (agent `fable-lightingluminol-compat`)

Owner decision 2026-10-22, verbatim after the risks were stated: **"Tiru penuh LightingLuminol"**. The bridge
now relaxes the region-ownership checks the way LightingLuminol's "Base Dirty Patches" do, as a setting
(`aurora.bridge.thread-checks`, default `relaxed`, effective only with `mode = safe`), with the supporting pieces
upstream needs ported and gated. Design, scope, honesty and what was deliberately not ported:
[aurora-plugin-bridge.md, *Thread checks*](../architecture/aurora-plugin-bridge.md#thread-checks-owner-decision-2026-10-22).

| Upstream file at the pinned revision | SourbyCraft treatment |
| --- | --- |
| [`paper-patches/features/0002-Base-Dirty-Patches.patch`](https://github.com/LuminolCustomArchive/LightingLuminol/blob/72d51c4d11b1ddfe70cde21f4736fdf660873cb3/lightingluminol-server/paper-patches/features/0002-Base-Dirty-Patches.patch) | `TickThread.ensureTickThread` bodies ask `guards/ThreadChecks.refuse` instead of being commented out; `ReferenceList`/`IteratorSafeOrderedReferenceSet` lock only while relaxed; `CraftServer#getCurrentTick` fallback moved into `RegionizedServer.getCurrentTick`; `CraftPlayer#teleport0` join became `OwnedTeleport.joinAsync` (bounded, skipped where it could deadlock); `CraftWorld#generateTree` / `CraftBlock#applyBoneMeal` use `WorldDataAccess` leases; the `CraftWorld` `ensureGlobalTickThread` removals are covered by the `RegionizedServer` check itself; `CraftScheduler#handle` and `CraftScoreboard` not ported |
| [`minecraft-patches/features/0001-Base-Dirty-Patches.patch`](https://github.com/LuminolCustomArchive/LightingLuminol/blob/72d51c4d11b1ddfe70cde21f4736fdf660873cb3/lightingluminol-server/minecraft-patches/features/0001-Base-Dirty-Patches.patch) | Three SourbyCraft Minecraft feature patches: relaxed region-ownership checks (RegionizedServer, RegionizedWorldData, TickRegions, NearbyPlayers, EntityLookup, ServerChunkCache, ChunkMap, MinecraftServer), cross-region world data (`ServerLevel.sourbycraft$worldDataPool` + `WorldDataAccess` leases at the upstream call sites, plus `ScheduledTickAccess`), legacy teleport events (`Entity#teleportAsync`, portal waiter). `RegionizedServer#globalTick` `mainThreadHeartbeat` and the `Projectile` Luminol-config hunk not ported |
| [`core/ReferenceCountedRegionizedWorldDataPool.java`](https://github.com/LuminolCustomArchive/LightingLuminol/blob/72d51c4d11b1ddfe70cde21f4736fdf660873cb3/lightingluminol-server/src/main/java/meow/bacteriawa/lightingluminol/core/ReferenceCountedRegionizedWorldDataPool.java) | Ported as `dev.iyanz.sourbycraft.compat.luminol.RegionizedWorldDataPool` (own ticket type, never returns `null`, `Lease` instead of `Tuple`) |

Evidence for this increment is in the coordination entry `fable-lightingluminol-compat` and the PR; unit tests
`ThreadChecksTest`, `AsyncTeleportEventsTest`, `TeleportRouteTest` (relaxed cases), `AuroraBridgeConfigTest`
(thread-checks), `BridgeRuntimeTest` (relaxed accounting). Nothing here qualifies a plugin or the server.

## Continuation 2026-10-23: lifecycle and teleport regressions

The interrupted working tree was preserved before integration. Review found these faults in the pending
port; none is an accepted consequence of relaxed ownership, and the fixes apply in enforced mode too:

- Early settings used the runtime class monitor while `ThreadChecks` initialization read those settings.
  Runtime initialization invokes `ThreadChecks` under that monitor: two first callers could deadlock.
  Invariant: early settings can be read while another thread holds the runtime monitor. A separate settings
  monitor removes the cycle; `AuroraBridgeEarlySettingsConcurrencyTest` timed out before the fix.
- Teleport listeners can redirect across regions or worlds after the initial classification. Placement now
  recomputes ownership against the final destination, rather than entering the original same-region path.
- Detached portal players already name the original destination. Cancellation or redirection now retargets
  every detached passenger's level before transforming and placing it (including the player's game-mode level).
  If the portal loading screen already announced the old destination, the client receives the corrected dimension too.
  The patch captures that setting before detaching and passes it to the helper, preserving the audited engine boundary.
- Portal event locations now use resolved absolute movement, including relative rotation. The listener's
  returned rotation is honored; the transition's velocity, flags, cause and post-transition callback are retained.
- Waiting for an owned async fallback now uses the same motion-aware route as the non-waiting fallback,
  retaining the synchronous caller's velocity flags. Retirement and refused scheduling complete the wait as false.

`AsyncTeleportIntegrationTest` checks the compiled patch call order without initializing Minecraft's registries;
all four integration regressions failed before the fixes. `AsyncTeleportEventsTest` additionally checks transition
rotation and metadata. These checks are structural/unit evidence, not player portal gameplay or soak evidence.
Final integrated build/boot evidence is recorded separately; no publication or plugin qualification is implied here.

## Status update 2026-10-22b (remaining local cases, agent `claude-luminol2`)

Local functional evidence only, in worktree `sc-worktrees/claude-luminol2` (base `cc5d5ef1`, PR #18; not applied, nothing
committed). Not qualified: no real players or plugins, no soak, no panel/VPS, no benchmark. Every boot was its own agent
test-server run (`minecraft:flat`, 2048 MiB, localhost), raw logs and `run.json` under
`/Volumes/Ext/DevCache/agent-tmp/sourbycraft/luminol-2026-10-22b/`; all stops exited 0. Final jar SHA-256 `cb8c068c...a4e0`.

| Case | Result | Evidence (file in the evidence directory) |
| --- | --- | --- |
| 1 SAFE global `execute`/`run`/delayed/fixed-rate, from a region | PASS (local) | `safe-final-server.log`: all 20 CI `LEGACY_*` markers `_OK` in both generations |
| 2 Native plugins with `mode = off`, legacy plugin present | PASS (local) after fix A | `case2-off-3-server.log`: test-plugin `SOURBY_METRICS_*_OK`, `SOURBY_STRESS_COMMANDS_OK`, `SOURBY_UNLOAD_FIXTURE_OK` (and `/unloadprobe` again); legacy plugin not loaded with one WARN (below), `/plugins` Failed 1 with the reason, `/perf plugins` failed 1, bridge mode off, 0 exceptions, 0 `Unsupported in region threading`. Before the fix: `case2-off-1-baseline-server.log` |
| 3 Cancellation states, races, rejection, disable, quarantine | PASS (unit + local) | unchanged from the 2026-10-22 update; `safe-final-server.log` |
| 4 Several plugins, global vs Bukkit cancellation | PASS (unit only) | `FoliaSchedulerCompatibilityTest` (2026-10-22); no second legacy plugin on a server |
| 5 Disable before listener / submit from `onDisable` | PASS (local) | `LEGACY_DISABLE_GLOBAL_SUBMIT_OK`, `LEGACY_DISABLE_BUKKIT_SUBMIT_OK` in `reload-1-server.log`, `safe-final-server.log` |
| 6 Region/entity schedulers, unsafe world access from a global callback | PASS (local) | `LEGACY_REGION_TASK_OK`, `LEGACY_ENTITY_*_OK`, `LEGACY_BRIDGE_WORLD_ACCESS_REFUSED` (counted, not quarantined) |
| 6b World unload with bridged region tasks, incl. tasks submitted during the unload | PASS (local) after fix C | `safe-final-server.log`: `LEGACY_UNLOAD_RELEASED_OK`, `LEGACY_UNLOAD_LATE_REFUSED_OK racing=1941 racingStillQueued=0`; before the fix on the same fixture: `unload-baseline-prefix-server.log` `LEGACY_UNLOAD_LATE_REFUSED_WRONG ... racingStillQueued=446`, `/plugins` pending 447 |
| 7 Packaged notices | PASS (local) | 2026-10-22 update |
| 8 Real plugins, players, soak, AWF interaction | owner-run | Build 49 runbook G07 |
| F1 Config limits: default 0, LIVE reload, early TOML precedence | PASS (local + unit) | `limits-1-server.log`, `reload-1-server.log`: `/sourbycraft reload` "Aurora LIVE: 2 change(s) applied" (limits) / "4 change(s)" (limits, sync-route, quarantine-after) and `/plugins` shows the new limits at once; defaults, bounds (`0..100000`), malformed values and file precedence of both keys unit-tested (`AuroraBridgeEarlyConfigTest` +2) |
| F2 Pending limit | PASS (local) | limit 10: `LEGACY_LIMIT_PENDING_OK accepted=10 rejected=3 ... replacement=true overflowRejected=true`, `LEGACY_LIMIT_PENDING_RELEASED_OK ran=10 duplicates=0 ... probeAdmitted=true`; limit 0: `LEGACY_LIMIT_PENDING_UNLIMITED_OK accepted=300 ran=300` |
| F3 Running-async limit | PASS (local) | limit 2 (6 BRIDGE_IO threads): `LEGACY_LIMIT_ASYNC_OK started=2 rejected=3 peak=2`, `..._RELEASED_OK finished=2 probeRuns=1`, overlapping timer `..._TIMER_OK cancelled=true`; limit 0: `..._UNLIMITED_OK started=6 peak=6`; fatal count unchanged (1 before and after, the deliberate world access), not quarantined; rejected count = refusals |
| F4/F5 Worker API, `isCurrentlyRunning`, ID wrap | PASS (unit only) | `BridgeReviewTest`, `BridgeRuntimeTest` (2026-10-07); no live probe |
| F6 `/plugins` counts/limits without starting the bridge for natives | PASS (local) | `case2-off-3`: native plugin "Bridge telemetry: not available"; `limits-1`: "Pending tasks / limit: 0 / 10" |
| R1 Reload while tasks pending/running | PASS (local) | `reload-1-server.log`: 20 held one-shots (600 ticks) and 3 running async bodies across a reload that set limits 5/1, `sync-route = global`, `quarantine-after = 5`, `mode = off`: report "RESTART_REQUIRED (changes since boot): aurora.bridge.mode", plugin still Bridged; `LEGACY_HELD queued=20 cancelled=0` after the reload (lowering does not cancel), `LEGACY_BUSY_DONE completed=3 interrupted=0`, new work refused while 20 >= 5 (`LEGACY_LIMIT_PENDING_OK accepted=0 rejected=3`), later `LEGACY_HELD ran=20 duplicates=0 queued=0`; `LEGACY_ROUTE global=true` after the reload, `global=false` after restoring `caller-region` |
| R2 Disable -> enable after a reload | PASS (local) | `reload-1-server.log`: `LEGACY_REENABLE_OK gen=2 staleRuns=0`, all 20 markers again in generation 2, 0 `_WRONG` |
| S Final SAFE run after the above | PASS (local) | `safe-final-server.log` (fixed jar): 20/20 markers in both generations, `LEGACY_REENABLE_OK`, unload probes `_OK`, 0 `_WRONG`, 0 `Unsupported in region threading`/`while cancelling Bukkit scheduler tasks`; only the deliberate exceptions |

There is no bridge-specific reload command: `/sourbycraft reload` is the only one, and `aurora.bridge.mode` stays
RESTART_REQUIRED (reported, not applied).

**Defects found and fixed (each with regression tests and a rerun of the case):**

- **A. Mode OFF refusal was an ERROR with a stack trace and invisible in `/plugins`.** The provider factory threw, Paper
  logged `Error loading plugin` with 15 stack lines, the explanation followed 3 s later in the future tense ("will be
  refused"), and `/plugins` neither counted nor found the plugin (the load-failure capture is installed after Paper builds
  providers). Fix: Paper feature patch 0024 (`FileProviderSource`) skips such a jar with one WARN
  `Not loading <name> v<ver> (<jar>): it does not declare folia-supported or canvas-supported and aurora.bridge.mode = off.
  Set aurora.bridge.mode = "safe" and restart ...` (`AuroraBridge.refused`) and records it through
  `PluginLoadDiagnostics.record`, so `/plugins` shows it FAILED with that reason and `/perf plugins` counts it; the
  startup-index line now says "were not loaded". The factories keep their refusal as backstop. Admission/refusal lines
  go to Log4j directly: through JUL they reached the console unformatted (`WARNING:` after a JUL date line) before the
  JUL bridge exists. Tests: `AuroraBridgeRefusalTest` (2), `PluginLoadDiagnosticsTest` (+1).
- **B. Limit refusals flooded the log.** Each refused task logged a WARN with a `RejectedExecutionException` stack trace
  (~35 lines; 4 refusals = 140 lines in `limits-1-server.log`), not rate-limited. Now: every refusal is counted and is the
  last failure; the WARN has no stack trace, names the key and is logged once per (plugin, limit) per 60 s with a
  suppressed count (`reload-1-server.log`: one line). Test: `BridgeCapacityLogTest` (2).
- **C. (PR #18 review) World unload release was a one-time sweep.** A region-owner task anchored in a world submitted
  after the sweep (an async thread naming a location there), or any task when a chunk save threw before
  `WorldUnloadDrain`, stayed queued for good, counted against the pending limit and kept the unloaded `CraftWorld`
  reachable. Fix: `BridgeRuntime` refuses a region-owner task whose world is unloading (its unload ticket is set at the
  start of the unload and never cleared; `AuroraBridge.worldUnloading`), checked before and again after indexing, rejected
  without a violation or log line; `WorldUnloadCompletion.run(ticket, unload, release)` runs the bridge release before the
  callback on success and on failure (MC feature patch 0033 passes it). Javadoc reference "Paper feature patch 0030" in
  `WorldUnloadDrain` corrected to 0021. Tests: `BridgeWorldUnloadTest` (+4), `WorldUnloadCompletionTest` (+2).

**Fixture additions** (`legacy-test-plugin`, console only, CI unchanged): `LimitProbes` (`legacybridge pending-limit <n>`,
`async-limit <n>`, `hold <n> <ticks>`, `held`, `release`, `busy <n> <s>`, `route`, `pending`) and an unload race inside
`legacybridge unload` (`LEGACY_UNLOAD_LATE_REFUSED`). None runs at boot, so the CI marker list is unchanged.

**Gradle / Python (worktree).** `-PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:test`:
sourbyapi 585/0 (2 skipped), sourbycraft-server 11934/0 (28 skipped), test-plugin 14/0 (`gradle-full-suites.log`). Python
`scripts/test_*.py` 613 OK; patch_policy (102 patches), baseline_policy, inherited_guard_policy (summary in
`docs/architecture/inherited-guards.md` regenerated: 102 feature patches), independence, concurrency, toolchain policies and
`patch_surface.py --check` pass.

## Status update 2026-10-22 (verify + harden, agent `claude-luminol`)

Functional, local evidence only. Not qualified: no real players, no real plugins, no soak, no panel/VPS, no
benchmark. Real-server acceptance (Sourby Demo, G07 of the Build 49 runbook) stays the owner's. Everything below was
done in worktree `sc-worktrees/claude-luminol` (base `c7523d2e`); nothing is committed until the PM applies it.

**What is in the tree (2026-10-22).** Paper feature patches, current numbers: 0007 port (`GlobalRegionScheduler`
`runDelayed`/`runAtFixedRate` -> `FoliaSchedulerCompatibility`, `runDelayedNative`/`runAtFixedRateNative`,
`cancelTasks` -> `AuroraBridge.cancelGlobal`, bridge admission closed before disable callbacks, Bukkit `cancelTasks` on
disable, `CraftScheduler` `global()`/`bridgeCancelled()` wiring), 0008 (async workers, draining tasks, limits), 0009
(native plugin `cancelTask`/`cancelTasks` is a no-op instead of the refused legacy cancel task), 0010/0011 (explicit
region/entity owner routing), 0012 (re-open admission when the same instance is enabled again). All are committed
in the root repository now (the 2026-10-07 note that 0007/0008/0010/0011 were untracked is superseded). Adapter:
`bridge/FoliaSchedulerCompatibility.java` with the GPL-3 header; packaged notices `META-INF/licenses/NOTICE_LIGHTINGLUMINOL`,
`LICENSE_LIGHTINGLUMINOL`, `LICENSE_LIGHTINGLUMINOL_GPL`, `LICENSE_LIGHTINGLUMINOL_MIT` (and `LICENSE_LUMINOL`) are
present in the patched server jar a booted run produced (`versions/26.2/sourbycraft-26.2.jar`, checked with `unzip -l`):
handoff case 7 is now verified locally. README credits the port.

**Safety review of the adapter and the bridged scheduler paths** (state machine, cancellation, disable/re-enable,
exceptions, guard/quarantine, world unload):

| # | Finding | Where | Severity | Outcome |
| --- | --- | --- | --- | --- |
| 1 | Region-owner bridged tasks were never released on world unload. `WorldUnloadDrain` cancels the world's region scheduler tasks without running them and without telling the bridge, so a legacy sync task anchored there (`RegionTask`, SSB2 spawn adapter, or `caller-region`) stayed in both bridge indexes for good: `isQueued` true, `isCancelled` false, listed by `getPendingTasks`, counted against `max-pending-tasks-per-plugin`. Reproduced on a baseline jar: `LEGACY_UNLOAD_RELEASED_WRONG ... queued=true,true cancelled=false,false`, `/plugins` pending `2` | `BridgeRuntime` (no unload path), `WorldUnloadDrain.drain` | Medium (silent task loss in the plugin's view, bounded index leak) | FIXED: `BridgeRuntime.worldUnloaded(world)` releases `REGION_OWNER` entries anchored in that world (rejected, last failure "the world it was anchored in unloaded", never a violation, no log line per task); `AuroraBridge.worldUnloaded`; `WorldUnloadDrain` calls it after the drain (also when a drain step failed) and logs `Released N bridged legacy task(s) anchored in <world> for unload`. Tests: `BridgeWorldUnloadTest` (2) |
| 2 | Adapter state machine (IDLE/RUNNING/CANCELLED_RUNNING/FINISHED/CANCELLED): every transition is either a synchronized CAS or a write that no other transition can race (one-shot FINISHED; repeater CANCELLED only after its CAS failed). `bridgeCancelled()` covers rejection, disable, quarantine, global/Bukkit cancellation and the pending limit; `setBukkitTask` cancels a handle published after a cancel/rejection. No defect found | `FoliaSchedulerCompatibility` `BukkitBackedScheduledTask` | - | pinned by 23 tests (below) |
| 3 | Exceptions in a global body: `BridgeRuntime.runGuarded` catches them; a region violation counts toward quarantine, anything else is a failure. A repeater whose body threw stays scheduled (Folia keeps it). A one-shot that threw ends FINISHED | `BridgeRuntime.runGuarded`/`onFailure` | - | tests + `LEGACY_GLOBAL_EXCEPTION_OK` |
| 4 | Disable: admission closes before `onDisable` (0007), `cancelTasks` + `GlobalRegionScheduler#cancelTasks` follow; a native plugin's disable no longer reaches the refused legacy cancel (0009). Submissions from `onDisable` are refused (`IllegalPluginAccessException`), a running one-shot finishes, a running repeater ends CANCELLED. Same-instance re-enable (0012 `reopen`): new tasks run, no task from before the disable runs | 0007/0009/0012, `BridgeRuntime.disable`/`reopen` | - | tests + `LEGACY_REENABLE_OK staleRuns=0` |
| 5 | Fatal-violation count and quarantine survive a disable/re-enable of the same instance (by design: cleared only by a restart). A plugin with a recurring deliberate violation is quarantined after `quarantine-after` re-enables | `BridgeTelemetry` | Info | documented here; the fixture reaches 2/3 after one re-enable |
| 6 | Re-enabling the same instance logs Paper's `Enabled plugin with unregistered ConfiguredPluginClassLoader` WARN (upstream Paper behavior for plugman-style re-enable, not the bridge) | Paper plugin manager | Info | left as is |
| 7 | Global callbacks calling `Entity#teleport`: the owned teleport route (Paper 0017) returns `false` and schedules `teleportAsync` from a foreign thread instead of blocking the global tick; no deadlock path through the adapter | Paper 0017 | - | reviewed only |

**Unit tests added** (`sourbycraft-server/src/test/java/dev/iyanz/sourbycraft/bridge/`). `FoliaSchedulerCompatibilityTest`
(23) drives the adapter through a real `BridgeRuntime` with the same wiring as `CraftScheduler` (patches 0003/0007/0009):
entry-point validation; no runtime started when nothing is bridged; explicit global routing even with `caller-region`;
one-shot run/finish, idle cancel (`CANCELLED_BY_CALLER` then `CANCELLED_ALREADY`, Bukkit task cancelled once), cancel while
running (`RUNNING`, then FINISHED), exception in a one-shot; repeater IDLE between runs, self-cancel
(`NEXT_RUNS_CANCELLED`, `CANCELLED_RUNNING`, `NEXT_RUNS_CANCELLED_ALREADY`, then CANCELLED and no further run), exception in a
repeater (keeps running, not fatal), cancel from another thread while the body runs (index entry removed at once,
`isCurrentlyRunning` true until the body drains); rejection inside submit before the handle exists, pending-limit
rejection, cancel before the handle is published; a guard hit in a global body counted toward quarantine, quarantine
cancelling the running repeater and idle work, new submissions refused; a thrown region violation counted as fatal; plugin
disabled before a body is due, disabled during a repeater's body; disable with idle and running work then same-instance
re-enable; a one-shot running during disable; `cancelGlobal` removing only this plugin's global-backed entries (async,
region and other plugins untouched; admission open) and `cancelAll` the rest; 500 one-shot run-vs-cancel and 300
repeater run-vs-cancel-vs-bridge-cancel races (results and final states always agree, no index leak). Package-private seam:
`FoliaSchedulerCompatibility.schedule(plugin, task, period, BukkitSubmit)`; the public entry points delegate to it.
Mutations: removing the cancel-on-publish in `setBukkitTask` fails 2 tests; making `bridgeCancelled()` a no-op fails 5.

**Gradle / Python (worktree, 2026-10-22).** Bridge package: 110 tests, 0 failures (`SuperiorSpawnRealJarTest` 4 skipped,
env-gated). Full suites (`-PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:test`): sourbyapi 585/0 (2 skipped), sourbycraft-server 11880/0 (28 skipped), test-plugin 14/0. Python `scripts/test_*.py` 613 OK; patch_policy, baseline_policy, inherited_guard_policy, independence, concurrency, toolchain policies and `patch_surface.py --check` pass.

**Local functional run (agent test server, not qualified).** Jar: worktree `slimServerJar` (SHA-256 `23165cef...2f22`),
`aurora.bridge.mode = "safe"`, `minecraft:flat`, legacy fixture `legacy-test-plugin-1.0.0.jar` from this worktree.
Run `claude-luminol-legacy-2` (2026-10-22 02:38, slot 2, boot 11.4 s, stop exit 0): every `LEGACY_*` marker CI waits for
printed `_OK` (plus the new `LEGACY_GLOBAL_EXCEPTION_OK`) in both generations; console `legacybridge reenable` ->
`LEGACY_DISABLE_GLOBAL_SUBMIT_OK`, `LEGACY_DISABLE_BUKKIT_SUBMIT_OK`, second onEnable repeats all markers,
`LEGACY_REENABLE_OK gen=2 staleRuns=0 freshGlobal=3 freshAsync=1` (global repeater, global one-shot, Bukkit sync timer,
Bukkit async timer and Bukkit one-shot left pending into the disable never ran afterwards); `legacybridge unload` ->
`LEGACY_UNLOAD_RELEASED_OK ... queued=false,false cancelled=true,true bodyRuns=0`. 0 `_WRONG`, 0 `Unsupported in region
threading`, 0 `while cancelling Bukkit scheduler tasks`; the only exceptions are the deliberate ones (2x `LEGACY_DELIBERATE`
body exception, 2x `LEGACY_BRIDGE_WORLD_ACCESS_REFUSED` with its TickThread error and bridge violation line). `/plugins
LegacyBridgeTest` at the end: Bridged, 71 redirects / 10 handoffs, 4 rejected / 2 fatal, not quarantined, pending 0.
Baseline run `claude-luminol-legacy-3-baseline` (same tree without the `WorldUnloadDrain` call): `LEGACY_UNLOAD_RELEASED_WRONG`,
pending 2. Run 1 (`claude-luminol-legacy-1`) exposed a counter bug in the new fixture check (fixed), not in the server.
Raw logs: `/Volumes/Ext/DevCache/agent-tmp/sourbycraft/luminol-2026-10-22/` (`run2-latest.log`, `run3-baseline-no-unload-release-latest.log`,
`run1-fixture-bug-latest.log`, `gradle-full-suites.log`).

**Fixture additions** (`legacy-test-plugin`, CI unaffected except one new required marker): a global repeater whose first
body throws (`LEGACY_GLOBAL_EXCEPTION`, now in the CI settled list), console command `legacybridge reenable` and
`legacybridge unload` (manual/agent runs only; CI does not send them).

**Handoff cases after this update.** Case 1, case 3 (including handle-publication, rejection, disable, quarantine and
cancel-during-run), case 4 (one runtime, several plugins, unit level), case 5 and case 7: covered as above (unit + local
functional). Case 2: native-plugin path unchanged (`shouldUseBukkitScheduler` false without a bridged admission; 0009
no-op); no native-plugin boot with mode OFF was run here beyond the fixture server's own native plugins (none installed).
Case 6: entity retirement on unload is Paper 0021 (ENG-3), entity movement and named region ownership are not changed by
this port; unsafe world access from a global callback reaching the guard/classifier is unit-tested here and seen live
(`WORLD_ACCESS_REFUSED`). Case 8 (real plugins, players, soak, AWF interaction) and the follow-up config-bound/LIVE-reload
cases remain open and belong to the owner's real-server acceptance. (Superseded for case 2 and the config-limit/reload cases by
the 2026-10-22b update above; case 8 stays owner-run.)

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
  states are exercised only by the fixture below. (Superseded 2026-10-22: `FoliaSchedulerCompatibilityTest`.)
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
Blanket `folia-supported` admission changes are outside this port; existing SAFE
admission remains the only legacy admission path. The thread-guard bypasses were
outside the first port and became the second port (2026-10-22c above) by owner
decision, as a setting rather than a blanket removal.

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
