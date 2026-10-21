# Region watchdog switch

Current patch file: `features/0025-SourbyCraft-disable-region-watchdog-by-default.patch`
(0026 until the 2026-10-13 renumbering; dated entries below keep the number of their date).

2026-10-06: user requested disabling the Folia diagnostic watchdog. Source change
is written in Minecraft feature patch
`0026-SourbyCraft-disable-region-watchdog-by-default.patch` and applied to the two
materialized Java files. No Codex build, test, boot or deployment was performed.

- [-] Acceptance: Claude must test the final built artifact; source alone is not boot evidence.

## Status update 2026-10-07 (what the log and the tree show now)

Sources: [AGENT-COORDINATION.md](../../AGENT-COORDINATION.md) (entries "patch directory incident",
"OWNER DECISION 2026-10-07", "compat-vs-performance follow-up") and the materialized tree. The
2026-10-06 paragraph above is the state when the patch was written; this section supersedes its
"no build, test or boot" wording.

- **Applied and committed (verified 2026-10-07).** 0026 is materialized commit `952f1e1` in
  `sourbycraft-server/src/minecraft/java` (log: "Codex has now committed ONLY the two watchdog files
  (952f1e1)"). The tree reads `Boolean.getBoolean("sourbycraft.region-watchdog.enabled")` in
  `FoliaWatchdogThread` and guards `start`, `addTick` and `removeTick`; `TickRegionScheduler` starts the
  thread only when enabled and creates `RunningTick` only when enabled at both call sites (patch
  `features/0026-...`, inspected 2026-10-07; `runningTick` is used nowhere else in that file). The patch
  file is still untracked in the root repository (`git status` 2026-10-07).
- **Built and booted, not verified for this flag.** 0026 sits under 0027 (`0569a90`) and 0028 (`ade3b24`) in the
  materialized history, so the demo jars built 2026-10-07 00:00 and 00:36 (log, owner-decision entry) were
  compiled with it, and Sourby Demo ran on them, including the `plugins-10` bench. The 2026-10-06 22:40 demo
  build most likely contains it too (the incident entry, ~22:35, says 0026/0027 were already materialized
  commits); the log does not record inspecting that jar. Those runs used the default (watchdog off): the demo
  panel cannot pass `-D` flags (log, "new-terrain chunk delivery on Sourby Demo"), so the enabled path was not
  run there.
- **Policy suite.** `scripts/test_independence_policy.py` flagged the 0026/0028 pair on 2026-10-07 (308 run, 2
  failures); with 0028 parked the Python suite is 327/327 (log), and the log attributes both failures to 0028,
  so 0026 passes the independence policy as it stands.
- **No unit test exists for the watchdog switch**, and no run of the existing test suites is attributed to this
  patch alone.

Acceptance cases below, item by item (2026-10-07): **1** partly: a jar containing 0026 booted and ticked with the
flag off, but no thread dump or other check that no `Folia Watchdog Thread` exists is recorded, nor are world
saves or clean stop for the flag-off case specifically. **2** not done (no `-Dsourbycraft.region-watchdog.enabled=true`
run, no controlled stall). **3** not done (reading the patch shows both disabled paths return early, which is not
a test). **4** not done (no controlled wrong-owner access recorded). **5** done: 0026 is applied and was rebuilt into
the demo jars; both `RunningTick` call sites are in the patch (inspected 2026-10-07).

Still unverified: that the flag-off JVM has no watchdog thread, that the flag-on path still prints the
diagnostics, and that disabling the diagnostic leaves guard, shutdown and bridge-quarantine behaviour unchanged.
`docs/TODO.md` and `SPEC.md` §158 are owned by another agent and are not edited here.

## Behavior and lifecycle

`sourbycraft.region-watchdog.enabled` is a JVM system property, default **false**.
It is **RESTART_REQUIRED**, read when `FoliaWatchdogThread` initializes before the
region scheduler starts. It is independent of Aurora TOML, Canvas engine YAML,
and bridge SAFE/OFF mode. A config reload cannot change it for this JVM.

With the default, the scheduler does not start the watchdog thread, allocate its
per-tick RunningTick records, or retain active ticks in the watchdog set. Direct
manual starts exit immediately. No region/global stall dumps come from this
watchdog while disabled. The public watchdog object remains available for existing
engine callers; its add/remove methods return without retaining data when disabled.

The inspected watchdog only prints stall diagnostics; it has no server-stop or
restart action. Thread ownership checks, TickThread exceptions, bridge quarantine,
scheduler `regionFailed` behavior, shutdown and persistence paths remain active.
The Paper/Spigot watchdog is a separate implementation and is not changed here.
The user's SuperiorSkyblock `Cannot read world asynchronously` log comes from
TickThread ownership checks; disabling this diagnostic thread does not suppress it.

After building these sources, ordinary startup leaves the region watchdog off.
To explicitly disable or re-enable it, put the property **before `-jar`**:

```bash
# Off (also the new default)
java -Dsourbycraft.region-watchdog.enabled=false -jar server.jar --nogui

# Enable stall diagnostics again
java -Dsourbycraft.region-watchdog.enabled=true -jar server.jar --nogui
```

An existing released JAR does not gain this switch from the documentation; use a
build containing patch 0025. The value uses Java Boolean property semantics: only
case-insensitive `true` enables it; unset or other values are false.

## Claude acceptance — partly done (status per item in the 2026-10-07 section above)

1. New JVM without the property and with explicit false: no live `Folia Watchdog
   Thread`, no RunningTick registrations or diagnostic dumps. Confirm normal region
   and intermediate-task ticking, metrics, world saves and clean stop still work.
2. New JVM with true: watchdog thread starts; a controlled stalled tick produces
   the existing diagnostic behavior. Do not attribute any separate watchdog's stop
   behavior to this implementation.
3. Disabled add/remove calls and direct/manual start retain no tick records and
   produce no diagnostic loop. Repeated ticks cannot accumulate references in the set.
4. A controlled wrong-owner world access still raises the existing ownership error;
   scheduler failure handling and bridge violation/quarantine behavior remain active.
5. Reapply feature patches and rebuild the server; retain unrelated materialized
   edits and confirm both RunningTick call sites are covered by patch 0025.

These are proposed acceptance cases; only items 1 (partly) and 5 have recorded evidence as of 2026-10-07 (see
above). No performance,
deadlock-prevention, plugin-compatibility or release qualification is claimed.

## A stalled tick without the watchdog: `/perf tick` "Tick in progress" (2026-10-22)

With the watchdog off (the default), nothing logs a region tick that never returns. The one place it shows
is the running-tick marker `RegionTickMetrics` keeps for every region and for the global tick, which
`PerformanceCollector` turns into the "Recent maximum" of `/perf tick` and `/mspt` (regions) and into the
`global tick slow` incident (global tick). This section records what that marker did wrong, what changed
and what is still not known. It is code, not a setting: nothing to configure, effective after a restart on
the new jar.

**Before.** A running tick contributed `min(window, now - start)` to the maximum, so a tick running for
minutes read exactly the window length (5000.0 ms in the five-second window). The panel bench of
2026-10-21 showed that value in every sample of two jars ([panel-bench-from-vps-2026-10-21.md](panel-bench-from-vps-2026-10-21.md)).
The marker was also fragile: `tickStarted` only set it from "none", and `tickCompleted` cleared it only when
the completed tick's start equalled it, so one start whose completion never arrived stayed for good.

**What the panel numbers do and do not say.** In those samples the worst average MSPT stayed at 2.6-3.4 ms.
A region that keeps completing ticks while carrying a stuck marker would have added the clamped 5000 ms to
its own average (about 50 ms for 100 ticks), so that is not what was observed. The numbers fit a region that
completed no tick in the window at all (its average is "unavailable" and drops out of the worst-average
figure, while the running tick still fed the maximum). Whether that was a tick that really never returned
(hung inside a plugin or the engine) or a region with a live telemetry generation and no ticks was not
established. The lost-completion weakness above is real and is fixed, but it is not shown to be the cause of
the panel value.

**Changed.**

- `RegionTickMetrics.tickStarted`: the newest start wins; `tickCompleted` clears the marker of its own tick or
  any older one and keeps a newer one; readers drop a marker that a completed tick already covers
  (`lastCompletedStart`). A lost completion therefore ends at the next completed tick of that owner. Merge
  rotation, inactive/active retirement and seal are unchanged (a randomised lifecycle model test,
  `RegionTickMarkerLifecycleModelTest`, 40 seeds x 4000 steps, covers them and fails on the old pairing).
- `PerformanceCollector`: a running tick is reported with its real elapsed time (no clamp). Only the
  utilisation ratio is still capped at 1.0. New `WindowMetrics.longestInProgressTickMspt()` and
  `ticksInProgress()` (additive default methods in `sourbyapi`) separate it from completed ticks;
  `maximumMspt()` still includes it, as documented.
- `/perf tick` and `/mspt` name a tick that has run longer than one tick budget: `Recent maximum: 12000.0ms
  (includes a tick still running for 12.0 s)` plus a `Tick in progress` row. Shorter running ticks are the
  normal state of a busy region at the sampling instant and are not mentioned. `/perf scheduler` shows which
  world has ticking regions and the worst running age.
- `global tick slow` fires on the global tick's real elapsed time; a marker that completed ticks have covered
  cannot hold it open.

**Still true.** A tick that really does not return is reported as "in progress" with a growing age; it is not
cleared by anything, and the tick thread's stack is only printed with
`-Dsourbycraft.region-watchdog.enabled=true` (the Sourby Demo panel cannot pass `-D` flags). Use `/perf tick`
and `/perf scheduler` first. The region and world a long-running tick belongs to is not part of the public
snapshot; `/perf scheduler` gives per-world counts and the worst age only.

**Evidence (functional, shared machine, not a measurement).** Test server, worktree jar, a plugin that sleeps in a
region task: `Recent maximum` read 2132 ms, 5113.5 ms, 8107.6 ms and 11107.2 ms with "includes a tick still
running" while a 12 s region stall ran, then 12044.7 ms as a completed value and 22.0 ms / 12.4 ms afterwards;
a 7 s global stall produced `global tick slow ... peak 7006.1 ms`. Four minutes with 8 headless clients,
region churn from teleports and forceloaded chunks: 27 samples, `Recent maximum` 5.9-97 ms. A local run of the
pre-fix jar (8 clients for about 6 minutes, teleports across and between regions, forceload churn) did not
reproduce a pinned value either, so the panel value remains unreproduced here.
