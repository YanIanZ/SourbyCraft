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
| `runTaskAsynchronously`, `runTaskTimerAsynchronously` | Timed by Folia's async scheduler, executed on the bounded `SourbyCraft-BridgeIO-*` lane (`/perf governor`) |
| `BukkitTask.cancel`, `cancelTask`, `cancelTasks`, `isQueued`, `isCurrentlyRunning` | Answered by the bridge |

Not covered: `getPendingTasks` and `getActiveWorkers` do not list bridged tasks. A task that
touches a player or block in a *different* region than the one it runs on is refused by the
engine (`IllegalStateException`). A long-running timer anchored on one region does not follow a
player who walks away.

## 3. Test it

1. Start the server with the plugin in `plugins/`.
2. Run `/plugins`. The plugin shows as **BRIDGED** (blue) or **FAILED** (red).
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
5. Run `/perf governor` for the Bridge I/O queue and rejections.
6. Check the log for `Bridged plugin <name> violated region ownership` and
   `generated an exception while executing task`.

## 4. Read the result honestly

- No violations, and the features work: the plugin *ran* here under this load. It is still not
  qualified for region threading.
- Violations: the plugin touches state from the wrong thread. `sync-route = "caller-region"` is
  the best the bridge can do without rewriting the plugin. Ask its author for Folia support.
- Quarantined: the plugin keeps loading, but its scheduled work stops. Remove it or run it
  elsewhere.

Report results with the `/plugins <name>` output, the plugin version and the log lines above.

## 5. CI evidence

`legacy-test-plugin` (a synthetic fixture, not a real plugin) runs on every CI build with
`mode = "safe"`. It covers sync, async, timer cancel, `isQueued`, a region-context `runTask`
reading a block (`LEGACY_BRIDGE_REGION_SYNC_OK`), and a global-region world access that is
refused and counted.
