# Stop requested during startup (`Bukkit.shutdown()` in `onEnable`), 2026-10-22

Status: **fixed in worktree `sc-worktrees/opus-startup-stop`** (base `6176d517`, not applied, not committed). MC feature
patch **0076** (`0076-SourbyCraft-hold-a-stop-requested-during-startup-until-startup-ends.patch`, author YanIanZ),
owned logic `dev.iyanz.sourbycraft.startup.StartupStopGate`, test `StartupStopGateTest`.
Found by opus-gate48-ssb2 (`docs/development/ssb2-real-jar-2026-10-22.md`, finding 2).
Functional runs on the shared agent laptop; no performance claim.

**Status 2026-10-25 (PR #62):** the fix landed. MC feature 0076 was committed in `efb64fb` (2026-10-22) and is in
every release tag from `v26.2-r48` on (`StartupStopGate`, `StartupStopGateTest`). The residual-risk item about the
startup-timeline line was addressed in PR #62 (`e7c8c57`): a startup during which a stop is held logs no Ready
summary and does not overwrite `sourbycraft_config/cache/startup-profile.properties`, and the auto-updater does not
confirm a just-applied update on such a startup (code written and statically reviewed; not built; not tested by the
owner). A stop from a non-server thread during startup is still not addressed. The runs below are unchanged.

## Root cause

`CraftServer#shutdown` → `MinecraftServer#halt(false)` → `running = false` → `stopServer()`. In the region-threading
stop path `stopServer` does not stop on the caller's thread. It halts the tick scheduler and starts the
**Region shutdown thread**, then returns. That thread disables plugins, halts every world's chunk system, closes the
region-file storage (`ChunkHolderManager.close`), saves, and calls `System.exit` in `stopPart2` → `onServerExit`.

When the caller is a plugin's `onEnable`, it is on the **server thread** inside `DedicatedServer#initServer`
(STARTUP plugins before the worlds, POSTWORLD plugins in `initPostWorld`). The server thread then keeps running the
rest of startup **concurrently** with the shutdown thread:

- remaining plugins are enabled while the shutdown thread disables plugins;
- `saveEverything(false, true, true)` at the end of `initServer` flushes region files the shutdown thread has
  already closed: `[ChunkHolderManager] Exception when flushing regions in world ...: ClosedChannelException`
  (overworld, nether in the SSB2 run);
- `runServer` calls `RegionizedServer.init()` (starts the region scheduler after the stop) and logs `Done (` after
  `Stopping server`;
- or, the other order: the shutdown thread reaches `System.exit` while the server thread is still in startup, so
  the server thread is cut off mid-startup (possibly mid-save).

Which order happens depends on timing. Both were seen on 2026-10-22: the SSB2 runs (ClosedChannelException +
late `Done (`) and `opus-startup-stop-before-2` below (exit while the server thread was still starting spark).
Data loss: not shown. In every run the shutdown thread reported its own saves complete ("Saved all worlds"), but
the concurrent server-thread save is unordered against storage close and `System.exit`, and the concurrent
plugin enable/disable is an ordering bug of its own.

Paper (no region threading) has no race. There, `halt` only clears `running`, startup finishes on the server thread
(including `Done (`), and the stop runs after the empty tick loop on the same thread.

## Fix (MC 0076, +13 −0, patch_surface class `hook`)

| Site | Change |
| --- | --- |
| `MinecraftServer#stopServer`, first statement | `if (startupStopGate.holdIfStarting(currentThread == serverThread)) return;`. A stop from the server thread before startup has ended is only marked. |
| `DedicatedServer#initServer`, after worlds + POSTWORLD plugins + gamerule override, before query/rcon | `if (startupStopGate.stopHeld()) return true;`. Skips the query/rcon listeners, watchdog/JMX, the startup `saveEverything` and the "server started" notification. |
| `MinecraftServer#runServer`, right after `initServer()` | `if (startupStopGate.finishStartup()) { LOGGER.info(ABANDONED_MESSAGE); stopServer(); return; }`. No tick schedule, no `RegionizedServer.init()`, no `Done (`. The normal stop starts now. |
| `MinecraftServer#runServer` catch (startup threw) | `if (startupStopGate.finishStartup()) stopServer();`. A held stop still runs. |

Result: startup proceeds as on Paper (other plugins enable and the worlds stay loaded), then the server thread
leaves startup, and only after that does the region shutdown thread disable plugins and save **once**, with the
storage still open.

Not changed:
- A normal boot. The gate is never closed and `finishStartup()` returns false; `Done (` is logged as before
  (`opus-startup-stop-after-2`).
- A stop from **any other thread** during startup (console, SIGTERM/shutdown hook `ServerShutdownThread`, async plugin
  task). It keeps the old immediate path, because that caller must not wait on a server thread that may be blocked.
  Residual risk below.
- A stop after startup.

### What a stopped-during-startup boot logs

No `Done (`. Instead, one INFO line from the server thread (`StartupStopGate.ABANDONED_MESSAGE`, prefix
`Startup stopped:`), then the normal stop lines (`Stopping server` … `Saved all worlds` … exit 0):

```
[Server thread/INFO]: Startup stopped: the server was asked to stop while it was starting (Bukkit.shutdown() on the server thread); not opening the server, saving the worlds once and stopping
```

Harnesses that wait for `Done (` see the JVM exit instead (agent_testserver: status `exited`, "jvm exited by
itself"). `StartupStopGateTest` pins that the message does not contain `Done (`.

## Tests

- `StartupStopGateTest` (8). Gate behaviour: a normal startup holds nothing; a server-thread stop is held and handed
  out exactly once; other-thread stops are never held; 500 rounds of concurrent request vs finish, where held ==
  handed out. Patch shape: the gate is the first statement of `stopServer` before the scheduler halt; the held stop
  runs right after `initServer` (log, then `stopServer`, then `return`) and after `onServerCrash`; `stopHeld()` comes
  before `enableQuery`; no removed lines.
- `:sourbycraft-server:test --tests dev.iyanz.SourbyCraftTestSuite --tests 'dev.iyanz.sourbycraft.startup.*'` (the
  package filter does not narrow the suite, so the whole SourbyCraft/Aurora suite ran): BUILD SUCCESSFUL (56 m 50 s,
  shared machine). 2305 test cases in the XML reports, 6 skipped, 0 failures, 0 errors.
- Python `scripts/test_*.py`: 613 OK. The first run failed only on the inherited-guards ledger summary (feature-patch
  count 101 → 102). The summary was regenerated in `docs/architecture/inherited-guards.md`.
- Policies: patch_policy OK (103), baseline_policy OK (722), inherited_guard_policy OK (118), independence,
  concurrency and toolchain exit 0, `patch_surface.py --check` passed (patch file `git add -N` in the worktree).
- No mutation run: the full suite takes about 57 min here. The shape assertions fail on hook removal by construction.

## Test-server reproduction (agent harness, slot 1, functional only)

Scratch plugin `StopOnEnable` (`folia-supported: true`, `load: POSTWORLD`). It logs `STOP_ON_ENABLE`, calls
`Bukkit.shutdown()` in `onEnable` and logs that the call returned. Built in the scratchpad, not committed. It is
installed with `--config-file plugins/StopOnEnable.jar=...`.

| Run | Jar (sha256) | Result |
| --- | --- | --- |
| `opus-startup-stop-before-1` | `8ec1739a…` (6176d517, no fix) | Invalid: the plugin had no `folia-supported`, so it was refused (bridge off). Normal boot, stopped. |
| `opus-startup-stop-before-2` | `8ec1739a…` | Race reproduced. `Bukkit.shutdown()` returned on Server thread at 06:34:47. The **Region shutdown thread** ran `Stopping server`, disabled the plugin, halted the chunk systems and saved all worlds at the same time as the server thread, which then logged `[spark] Starting background profiler...` (still in `initPostWorld`). The JVM exited (0) while the server thread was mid-startup: no `Done (`, and the startup `saveEverything` never visibly completed. The SSB2 runs show the other order (ClosedChannelException ×2, `Done (` after `Stopping server`). |
| `opus-startup-stop-after-1` | `1c113a52…` (with 0076) | `STOP_ON_ENABLE` call returned; the server thread finished `initPostWorld` (spark, startup timeline) and logged `Startup stopped: …`. **Only after that** did the Region shutdown thread log `Stopping server`, disable the plugin and save 529 block chunks per world. `Saved all worlds`, `All RegionFile I/O tasks to complete`, exit 0. No `Done (`, no ERROR, no `ClosedChannelException`, no Exception. |
| `opus-startup-stop-after-2` | `1c113a52…`, `--world-from opus-startup-stop-after-1`, no plugin | Normal boot over the stopped boot's world: `Done preparing level "world" (0.246s)`, `Done (4.844s)`. `save-all flush` worked. Stop exit 0 ("requested"). No ERROR/Exception/corrupt/"Failed to" lines. |

Raw logs: `/Volumes/Ext/Sourby/agent-testservers/opus-startup-stop-{before-1,before-2,after-1,after-2}/logs/latest.log`.
A second unpatched run (`before-3`) was refused by the harness (2.73 GiB free RAM < 3 GiB), so the ClosedChannelException
order was not reproduced locally. It comes from the SSB2 document.

## Residual risk

- A stop from a **non-server thread** during startup (Ctrl+C/SIGTERM during world load, an async plugin task) still
  starts the shutdown thread concurrently with startup. Same race, not addressed: deferring it would make that caller
  wait on a possibly stuck server thread. It needs its own design (for example a bounded wait).
- While a stop is held, the rest of startup runs with `running == false`, the same as Paper. Code that checks
  `isRunning()` during startup behaves as it does on Paper.
- In a stopped boot the `ServerLoadEvent` listeners still run (as on Paper). That includes the startup-timeline line
  `Aurora startup: N ms to ready …`, whose wording says "ready" for a boot that never opened. Wording only, left to
  the log owner (fable-logdesign).
- A `halt(true)` (wait) from the server thread during startup would join itself. Not changed (Paper has the same
  issue). `Bukkit.shutdown()` uses `halt(false)`.
