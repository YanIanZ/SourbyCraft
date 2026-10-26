# Testing legacy plugins through the Aurora Bridge

For operators who want to try a plugin that does not declare `folia-supported` on SourbyCraft
26.2 (checked against the Build 50 candidate, PR #62, on 2026-10-25; items marked *Build 50
candidate* are not in the published `v26.2-r49`/`v26.2-r50` jars). The bridge is **off by default**
and **not qualified**. Test on a copy of your server, never on production data.

## 1. Enable it

`sourbycraft_config/aurora.yml` (YAML; an `aurora.toml` beside it is never read, and the Build 50
candidate logs one WARN about it at boot and on every reload):

```yaml
aurora:
  bridge:
    mode: "safe"                          # RESTART_REQUIRED
    thread-checks: "relaxed"              # RESTART_REQUIRED; owner default, see section 2b; "enforced" restores the checks
    sync-route: "caller-region"           # LIVE; "global" sends every sync task to the global region
    quarantine-after: 3                   # LIVE
    max-pending-tasks-per-plugin: 0       # LIVE; 0 disables the optional task limit
    max-running-async-tasks-per-plugin: 0 # LIVE; 0 preserves existing async overlap
```

Restart. The log names every plugin the bridge admits:

```
Aurora Bridge admitted <Plugin> (no folia-supported/canvas-supported). ...
```

## 2. What the bridge does with a legacy plugin

| Plugin calls | Runs on |
| --- | --- |
| `runTask`, `runTaskLater`, `runTaskTimer`, `scheduleSync*`, `callSyncMethod`, `BukkitRunnable.runTask*` from a command, event or task running on a region | That region, anchored on a chunk it owned when the task was scheduled |
| `runTask`, `runTaskLater`, `runTaskTimer`, `scheduleSync*` scheduled while a **player's** event is handled or a player's command runs (also from the plugin's own async task started there, e.g. DeluxeMenus's async menu build, or an event the plugin fires from it) | The region owning that player when the task is due, following the player (2026-10-22). Player already gone when scheduled: the row below. Player leaves before the task runs: cancelled and counted rejected (not a violation); a repeating task stops |
| The same from `onEnable`, the console, the global region or async code with no player frame | The global region: server-wide state only. Block or entity access from there is refused by the engine with `thread-checks: "enforced"`; under the default `relaxed` it runs on the wrong thread and is only counted (section 2b) |
| The same with the callback wrapped in `EntityTask.of(entity, action)` (`sourbyapi`) | The region owning the entity when the task is due, following it across regions; if the entity is removed first the task is cancelled without running and counted under rejected operations in `/plugins` (not a violation) |
| The same with the callback wrapped in `RegionTask.at(location, action)` or `new RegionTask(world, chunkX, chunkZ, action)` (`sourbyapi`) | The region owning that chunk when the task is due (world and chunk captured when the task is created) |
| `runTaskAsynchronously`, `runTaskTimerAsynchronously` | Timed by Folia's async scheduler, executed on the bounded `SourbyCraft-BridgeIO-*` lane (`/perf governor`). A repeating task keeps at most one run waiting in that lane; a period that fires while the previous run has not started is skipped, not queued (*Build 50 candidate*) |
| `BukkitTask.cancel`, `cancelTask`, `cancelTasks`, `isQueued`, `isCurrentlyRunning`, `getPendingTasks` | Answered by the bridge |

Since patch 0008 `getActiveWorkers` lists running bridged async bodies too. Not covered: a task that
touches a player or block in a *different* region than the one it runs on is refused by the
engine (`IllegalStateException`) with `thread-checks: "enforced"`; under the default `relaxed` the
access proceeds on a thread that does not own that state (unsafe, counted, not refused). A timer anchored on a region (not on a player) does not follow a
player who walks away. A task that sweeps every entity or chunk of a world from the global region
(ClearLag's clear task) has no single owner and cannot be routed; its violation line says so, and the
plugin needs a Folia-supported replacement
([not bridgeable](../architecture/aurora-plugin-bridge.md#not-bridgeable-2026-10-22)).

### Legacy synchronous events and `ServerTickManager` (2026-10-22, unit-tested; see the coordination entry for the boot check)

These work for every plugin, bridged or native; nothing to enable. Each costs nothing unless a
plugin listens to it, and the first time a plugin's listener is used the log says once
`Plugin <name> listens to the deprecated synchronous <event>; SourbyCraft fires it ...`.

| Event / API | Where it runs | Waits | If it cannot run in time |
| --- | --- | --- | --- |
| `PlayerPreLoginEvent` | Global tick, after `AsyncPlayerPreLoginEvent` (pre-set from its result; the legacy event's final result decides, as on Paper: anything but `ALLOWED` refuses, also without a kick message, then with "You are not allowed to join this server.") | The login's authentication thread, up to 10 s to start + 10 s to finish | Login refused ("could not finish its login checks in time"), WARN |
| `PlayerChatEvent` | The region that owns the sender: inline when the caller already owns the player; from the chat thread handed to the player's region; from a tick thread that does not own the player (a plugin's `Player#chat` on the global scheduler or another region) the event and the rest of the message run on the player's next tick | The chat thread only, up to 5 s + 5 s; a tick thread never waits | Message dropped and never sent later, WARN (at most one per 10 s) |
| Paper's sync `ChatEvent` | Same; the message is sent after the event ran (from the waiting chat thread, or on the owner) | Same | Same |
| `Bukkit.getServerTickManager()` | Any thread; changes take effect at the next global tick | — | — |

Order for one chat message, as on Paper: `AsyncPlayerChatEvent` (chat thread) → `PlayerChatEvent`
(sender's region) → `AsyncChatEvent` (chat thread; on the player's region for a `Player#chat` from
a non-owning tick thread, see below) → `ChatEvent` (sender's region) → delivery.
A `Player#chat` from a non-owning tick thread (the global region scheduler, another region)
returns before the message is processed. When a plugin listens to `PlayerChatEvent` or `ChatEvent`,
everything after that event then runs on the **player's region thread**, after the caller
returned, with the event's `isAsynchronous()` still `false` (Paper's flag for `Player#chat`): that
includes `AsyncChatEvent`. A listener that blocks in `AsyncChatEvent` (database or HTTP lookup,
waiting on a future) stalls that region's tick for every player and chunk in it; check
`event.isAsynchronous()` or hand the work off. If the event or the delivery fails on this path,
there is no caller left to throw to: the log shows one ERROR line naming the player and the plugins
listening to the legacy chat events, and the message is not sent (on the other paths the exception
reaches the caller, as Paper's `Exception processing chat` from the chat thread).
Cancel, message, format and recipients changes are honoured.

`ServerTickManager` (freeze, step, sprint, tick rate) applies to every region and the global tick.
The state is vanilla's own `ServerTickRateManager`, advanced once per global tick, so vanilla
readers (datapack `#tick` functions, the watchdog) agree with the API. Each player is told its own
region's state: frozen while the server is frozen **or** its region is paused through
`getRegionThreadingTickRateManager()`, so unfreezing the server or changing the tick rate does not
unfreeze the client of a player in a paused region. The read methods never block and never throw
(before the server exists they answer not frozen, not stepping, tick rate 20); changing the state
before the server exists is refused with `IllegalStateException`. `isFrozen(Entity)` includes
the region's own pause only when called on the entity's owner thread.
Each region picks a change up at the start of its own next tick, so regions are not frozen at one
shared tick boundary; step and sprint count global ticks (a region that is behind runs fewer). The
tick rate is the one server-wide rate (1–10 000). A region's own pause from
`getRegionThreadingTickRateManager()` still applies on top. The vanilla `/tick` command stays
unregistered: `/tick query` reads main-loop tick times that do not exist under region threading.

## 2b. Thread checks: relaxed by default (owner decision 2026-10-22)

With `mode: "safe"` the region-ownership checks are **relaxed** unless `aurora.bridge.thread-checks` says
`"enforced"` (or `"bridged"`: relaxed only inside bridged task bodies). Relaxed means a legacy plugin's
cross-region access runs on the wrong thread and is only counted (`/perf plugins`: "Thread checks" and per-plugin
"relaxed ownership hits"; `/plugins <name>`: "Relaxed ownership hits"): it never throws, never counts as a fatal
violation and never quarantines the plugin. The log names it at most once per plugin and reason per 60 s; in
the Build 50 candidate the reason is keyed without its per-hit thread and position, so a sweep across many
positions logs once. Test both: the thing you are checking under `relaxed` is "does the
plugin's flow complete", under `enforced` "what does it still refuse". Neither result qualifies the plugin, and a
relaxed server must never be presented as stable (boot WARN, `/ver` "Thread checks" row). The setting is
RESTART_REQUIRED; for a one-off run, `-Daurora.bridge.thread-checks=enforced` overrides the file. Which mode a
release qualifies is owner decision PM-012. Details: [aurora-plugin-bridge.md, Thread checks](../architecture/aurora-plugin-bridge.md#thread-checks-owner-decision-2026-10-22).

## 3. Test it

1. Start the server with the plugin in `plugins/`.
2. Run `/plugins`. The plugin shows as **BRIDGED** (green) or **FAILED** (red); blue is **NATIVE**.
3. Exercise the plugin's features: commands as a player and from the console, its events, its
   timers.
4. Run `/plugins <name>`. Watch:
   - **Scheduler redirects**: tasks the bridge accepted.
   - **Owner handoffs**: tasks run on the caller's region.
   - **Rejected operations**: tasks refused (quarantined plugin, a full Bridge I/O queue, a configured
     limit, or an anchor that went away before the task ran).
   - **Fatal violations**: region-ownership exceptions its tasks threw. Ownership exceptions exist only with
     `thread-checks: "enforced"`; under `relaxed` this stays at the count of other fatal exceptions.
   - **Relaxed ownership hits (thread-checks)**: shown while checks are relaxed; wrong-thread accesses that
     were allowed.
   - **Quarantined**: after `quarantine-after` fatal violations its tasks are cancelled and new ones
     rejected. The plugin is not disabled. Relaxed hits never quarantine.
   - **Pending tasks / limit**, **Running async / limit**: current counts against the optional limits.
   - **Sync body time (region / global)**, **Async body time**: wall time of the task bodies on the thread
     that ran them, without queue wait (count, mean, p50, p99, max).
   - **Last failure**: the most recent exception (*Build 50 candidate*: a relaxed hit fills it only when
     nothing else was recorded).
5. Run `/perf governor` for the Bridge I/O queue and rejections, and `/perf plugins` for every
   bridged plugin at once.
6. Check the log for `Bridged plugin <name> violated region ownership` (enforced),
   `Bridged plugin <name> accessed state owned by another thread … and was allowed to` (relaxed) and
   `generated an exception while executing task`.

## 4. Read the result honestly

- No violations, and the features work: the plugin *ran* here under this load. It is still not
  qualified for region threading. Under `relaxed` "no violations" says nothing: read the relaxed
  ownership hits instead, and repeat with `enforced` to see what the plugin would be refused.
- Violations (enforced) or relaxed hits: the plugin touches state from the wrong thread.
  `sync-route: "caller-region"` is the best the bridge can do without rewriting the plugin. Ask its
  author for Folia support.
- Quarantined: the plugin keeps loading, but its scheduled work stops. Remove it or run it
  elsewhere.

What **BRIDGED** means: the plugin loaded and enabled, the bridge accepted and routed its scheduled
tasks, and no fatal ownership violation has been counted *yet* (under `relaxed`, none can be). It does
not mean the plugin is region-aware, Folia-supported or qualified. The body-time rows of
`/plugins <name>` measure the wall time of its task bodies on the running thread; they are observations of
this workload, not a cost certification. The bridge chooses a thread for each task; it
cannot know which region owns the blocks, entities or players the task touches, so code paths you did
not exercise can still violate ownership later. The only route to full compatibility is a native
Folia port of the plugin, after which it shows as NATIVE and no longer goes through the bridge (see
[What the bridge is not](../architecture/aurora-plugin-bridge.md#what-the-bridge-is-not)).

Report results with the `/plugins <name>` output, the plugin version and the log lines above.

## 5. CI evidence

`legacy-test-plugin` (a synthetic fixture, not a real plugin) runs on every CI build of a push
(pull requests run no Java build) with `mode: "safe"` and the default `relaxed` thread checks. It
covers sync, async, timer cancel, `isQueued`, `getPendingTasks`, a region-context `runTask` reading a
block (`LEGACY_BRIDGE_REGION_SYNC_OK`), `RegionTask` and `EntityTask` routing (`LEGACY_REGION_TASK_OK`,
`LEGACY_ENTITY_TASK_OK`, `LEGACY_ENTITY_RETIRED_OK`), the global-scheduler port markers and a
global-region world access, for which CI accepts either `LEGACY_BRIDGE_WORLD_ACCESS_ALLOWED` or
`_REFUSED`. Because the checks are relaxed, CI requires the boot WARN `region-ownership checks are
RELAXED`, `LEGACY_RELAXED_MODE effective=RELAXED`, every `LEGACY_RELAXED_*_OK` probe (sweep, block write,
global chunk, teleport join) and **no** quarantine of the fixture: relaxed checks must count, not
quarantine. CI does not run an `enforced` boot.

## 6. Optional per-plugin limits (locally tested 2026-10-22, not qualified)

The limits above apply separately to each bridged plugin. Pending counts unfinished
indexed tasks; running async counts actual callbacks, including overlaps of one
timer. Both keys accept `0..100000`. `/plugins <name>` shows the current counts and
limits. Select nonzero values from the plugin's workload rather than treating them
as universal performance settings.

A submission exceeding the pending limit is cancelled before it is scheduled.
An async callback exceeding the running limit cancels a one-shot task; a repeating task skips that
run and stays scheduled (*Build 50 candidate*; logged as `Aurora Bridge skipped a run of repeating
async task N for <plugin>: … the task stays scheduled`). Existing bodies finish normally. The bridge
records a capacity rejection and reason without classifying it as a fatal ownership violation. Every
refusal is counted in `/plugins <name>` (Rejected, Last failure); the log shows at most one
`Aurora Bridge cancelled task N for <plugin>: pending task limit N (aurora.bridge.max-pending-tasks-per-plugin) ...`
line (or the running-async one, or the skipped-run one) per plugin and limit per 60 s, without a stack trace. Limits do not queue excess work or
promise fairness in the shared I/O lane. Lowering a limit is LIVE and affects new
admission; it does not interrupt bodies already running.

CI does not exercise the limits. They were run on a local agent test server on
2026-10-22 with the fixture's console probes (`legacybridge pending-limit <n>`,
`async-limit <n>`, `hold`, `held`, `busy`, `route`), including a `/sourbycraft reload`
while tasks were pending and running: functional evidence only, see the per-case table
in [the port doc](../development/LIGHTINGLUMINOL-BRIDGE-PORT.md). Real plugins under
load remain the owner's acceptance (Build 49 runbook G07).

## 7. With the bridge off

With `mode: "off"` (the default) a plugin without `folia-supported`/`canvas-supported`
is not loaded. The log has one line, no stack trace:

```
Not loading <Plugin> v<version> (<jar>): it does not declare folia-supported or canvas-supported and aurora.bridge.mode = off. ...
```

and `/plugins` lists it as **FAILED** ("did not load") with that reason. Native plugins
are unaffected.
