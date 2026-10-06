# Testing legacy plugins through the Aurora Bridge

For operators who want to try a plugin that does not declare `folia-supported` on SourbyCraft
26.2 Build 47. The bridge is **off by default** and **not qualified**. Test on a copy of your
server, never on production data.

## 1. Enable it

`sourbycraft_config/aurora.toml`:

```toml
[aurora.bridge]
mode = "safe"                  # RESTART_REQUIRED
sync-route = "caller-region"   # LIVE; "global" sends every sync task to the global region
quarantine-after = 3           # LIVE
max-pending-tasks-per-plugin = 0        # LIVE; 0 disables the optional task limit
max-running-async-tasks-per-plugin = 0  # LIVE; 0 preserves existing async overlap
```

Restart. The log names every plugin the bridge admits:

```
Aurora Bridge admitted <Plugin> (no folia-supported/canvas-supported). ...
```

## 2. What the bridge does with a legacy plugin

| Plugin calls | Runs on |
| --- | --- |
| `runTask`, `runTaskLater`, `runTaskTimer`, `scheduleSync*`, `callSyncMethod`, `BukkitRunnable.runTask*` from a command, event or task running on a region | That region, anchored on a chunk it owned when the task was scheduled |
| The same from `onEnable`, the console, the global region or async code | The global region: server-wide state only; any block or entity access is refused by the engine |
| The same with the callback wrapped in `EntityTask.of(entity, action)` (`sourbyapi`) | The region owning the entity when the task is due, following it across regions; if the entity is removed first the task is cancelled without running and counted under rejected operations in `/plugins` (not a violation) |
| `runTaskAsynchronously`, `runTaskTimerAsynchronously` | Timed by Folia's async scheduler, executed on the bounded `SourbyCraft-BridgeIO-*` lane (`/perf governor`) |
| `BukkitTask.cancel`, `cancelTask`, `cancelTasks`, `isQueued`, `isCurrentlyRunning`, `getPendingTasks` | Answered by the bridge |

Since patch 0008 `getActiveWorkers` lists running bridged async bodies too. Not covered: a task that
touches a player or block in a *different* region than the one it runs on is refused by the
engine (`IllegalStateException`). A long-running timer anchored on one region does not follow a
player who walks away.

## 3. Test it

1. Start the server with the plugin in `plugins/`.
2. Run `/plugins`. The plugin shows as **BRIDGED** (green) or **FAILED** (red); blue is **NATIVE**.
3. Exercise the plugin's features: commands as a player and from the console, its events, its
   timers.
4. Run `/plugins <name>`. Watch:
   - **Scheduler redirects**: tasks the bridge accepted.
   - **Owner handoffs**: tasks run on the caller's region.
   - **Rejected operations**: tasks refused (quarantined plugin, or a full Bridge I/O queue).
   - **Fatal violations**: region-ownership exceptions its tasks threw.
   - **Quarantined**: after `quarantine-after` violations its tasks are cancelled and new ones
     rejected. The plugin is not disabled.
   - **Last failure**: the most recent exception.
5. Run `/perf governor` for the Bridge I/O queue and rejections, and `/perf plugins` for every
   bridged plugin at once.
6. Check the log for `Bridged plugin <name> violated region ownership` and
   `generated an exception while executing task`.

## 4. Read the result honestly

- No violations, and the features work: the plugin *ran* here under this load. It is still not
  qualified for region threading.
- Violations: the plugin touches state from the wrong thread. `sync-route = "caller-region"` is
  the best the bridge can do without rewriting the plugin. Ask its author for Folia support.
- Quarantined: the plugin keeps loading, but its scheduled work stops. Remove it or run it
  elsewhere.

What **BRIDGED** means: the plugin loaded and enabled, the bridge accepted and routed its scheduled
tasks, and no fatal ownership violation has been counted *yet*. It does not mean the plugin is
region-aware, Folia-supported or qualified, and it says nothing about its cost: the counters in
`/plugins <name>` count tasks, they do not time them. The bridge chooses a thread for each task; it
cannot know which region owns the blocks, entities or players the task touches, so code paths you did
not exercise can still violate ownership later. The only route to full compatibility is a native
Folia port of the plugin, after which it shows as NATIVE and no longer goes through the bridge (see
[What the bridge is not](../architecture/aurora-plugin-bridge.md#what-the-bridge-is-not)).

Report results with the `/plugins <name>` output, the plugin version and the log lines above.

## 5. CI evidence

`legacy-test-plugin` (a synthetic fixture, not a real plugin) runs on every CI build with
`mode = "safe"`. It covers sync, async, timer cancel, `isQueued`, a region-context `runTask`
reading a block (`LEGACY_BRIDGE_REGION_SYNC_OK`), and a global-region world access that is
refused and counted.

## 6. Optional per-plugin limits (upgrade awaiting Claude testing)

The limits above apply separately to each bridged plugin. Pending counts unfinished
indexed tasks; running async counts actual callbacks, including overlaps of one
timer. Both keys accept `0..100000`. `/plugins <name>` shows the current counts and
limits. Select nonzero values from the plugin's workload rather than treating them
as universal performance settings.

A submission exceeding the pending limit is cancelled before it is scheduled.
An async callback exceeding the running limit cancels its task/timer; existing
bodies finish normally. The bridge records a capacity rejection and reason without
classifying it as a fatal ownership violation. Limits do not queue excess work or
promise fairness in the shared I/O lane. Lowering a limit is LIVE and affects new
admission; it does not interrupt bodies already running.

These additions and scheduler-worker patch 0008 are not covered by the historical
CI evidence above. Claude is responsible for their testing and acceptance; see
[the handoff cases](../development/LIGHTINGLUMINOL-BRIDGE-PORT.md).
