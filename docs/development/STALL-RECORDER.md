# Stall recorder

Evidence for a hung or killed server on a host where nobody has a shell to run `jcmd`
(Pterodactyl and similar panels). Added 2026-10-22 after the players-50 panel runs, where the global
tick stopped for 50-170 s and the process then vanished with no thread dump, no memory figure and no
log line ([panel-bench-from-vps-2026-10-21.md](panel-bench-from-vps-2026-10-21.md), "Hang analysis",
section 4D). It records evidence; it does not fix, restart or stop anything.

## What it does

`dev.iyanz.sourbycraft.diagnostics.StallRecorder` runs on its own daemon thread
(`SourbyCraft-StallRecorder`, never a tick thread) and once a second looks at the global tick and at
every active region. A **stall** is a source that has completed no tick for `after-seconds`
(default 10 s). Then:

1. Dump 1 is written at once to `logs/stalls/stall-<UTC yyyyMMdd-HHmmss>.txt` (relative to the
   server's working directory), and one WARN line names the file:
   `Server stall: the global tick has completed no tick for 10.4 s ... written to logs/stalls/stall-....txt`.
2. While at least one source stays stalled, a further dump is **appended to the same file** every
   `repeat-seconds` (default 10 s), up to `max-dumps-per-stall` (default 6).
3. When no source is stalled any more, one INFO line says the stall ended and how many dumps it wrote.
4. If the JVM shuts down (SIGTERM, `System.exit`, console `stop`) while a stall is open, a shutdown
   hook appends one final dump (`FINAL`) to the same file. `SIGKILL` and the kernel OOM killer run no
   hook: the periodic dumps already on disk are the evidence in that case, which is why the first one
   is written immediately and the file is flushed after every dump.
5. At most `keep-files` (default 20) `stall-*.txt` files are kept; creating a new stall file deletes
   the oldest. Nothing else in the directory is touched.

A dump contains, in this order:

| Section | Content |
| --- | --- |
| header | stall number, dump number, time (UTC), stall age, JVM uptime and pid, the stalled sources with "no tick for N s (a tick has been running for M s)" |
| the stalled thread(s) | the thread(s) whose stack contains the global tick's code, printed in full; and the other threads inside region tick/task code with their state, the lock they wait on and its owner. If the global tick is stalled but no thread has its code on the stack, the dump says so (the thread is outside tick code or gone) |
| memory | heap and non-heap used/committed/max, direct and mapped buffer pools, `/proc/self/status` `VmRSS`/`VmHWM`/`VmSize`/`VmSwap`/`Threads`, container limit and cgroup `memory.current` (`ContainerMemory`), swap, and the raw cgroup files `memory.max`, `memory.current`, `memory.high`, `memory.swap.current`, `memory.events` (**`oom_kill`** is the kernel's own count), `memory.pressure`, `memory.stat` (v2) or the v1 equivalents |
| garbage collection | cumulative count and time per collector, to compare between dumps |
| load | live/peak thread count, system load average, process and system CPU load |
| loaded chunks, entities, players | the per-region counters summed per world (`RegionPopulation`), read on a helper thread with a 2 s limit |
| deadlocks | `findDeadlockedThreads()` (monitors and ownable synchronizers) |
| all threads | `ThreadMXBean.dumpAllThreads(true, true)`, every frame (not the JDK's 8-frame `toString`), lock owner, the monitors held, per-thread CPU time so a spinning thread (CPU grows between dumps) is told apart from a blocked one |

Reading it: a global-tick thread `BLOCKED`/`WAITING` with a lock owner points at the owner's stack;
the same frame and a growing `cpu=` across dumps is a runaway loop; `memory.events` with
`oom_kill > 0`, or `memory.current` approaching `memory.max` across the dumps, points at the kernel
OOM killer; flat memory and no further dumps after a kill points at an external actor. These are
reading aids, not findings: no cause is established until a real dump exists.

## Settings (all RESTART_REQUIRED)

Keys under `[aurora.diagnostics.stall-recorder]` in `sourbycraft_config/aurora.toml`; see
[AURORA-CONFIG.md](../AURORA-CONFIG.md) and [config-examples/aurora.toml](../config-examples/aurora.toml).

| Key | Default | Range |
| --- | --- | --- |
| `enabled` | `true` | `false` starts no thread and registers no hook |
| `after-seconds` | `10` | 1-3600 |
| `repeat-seconds` | `10` | 1-3600 |
| `max-dumps-per-stall` | `6` | 1-100 |
| `keep-files` | `20` | 1-1000 |

**RESTART_REQUIRED, all five:** `SourbyCraftBootstrap` reads them
once in the `stall recorder` stage (right after `configuration`) and creates the thread and the shutdown
hook then; `/sourbycraft reload` republishes `AuroraConfig` but does not reach the running recorder, and
lists the key as restart-pending. An invalid value keeps that key's default and is reported (a typo
never turns the recorder off). The file is seeded into a **new** `aurora.toml` only; an existing file
without the keys runs on the defaults, which means **a server updated to this build records stalls
without any config change**.

## Cost and risk

- Tick path: one `volatile` store per completed tick (`RegionTickMetrics.lastCompletedEnd` became
  `volatile`; it is written inside the already `synchronized` `recordCompletedSample`). The recorder's
  reads are two volatile reads per source (`lastCompletedEndNanos()`, `activeTickStartNanos()`); it
  never takes a region's metrics monitor and never calls `refreshSnapshot`. The walk over the regions is
  `RegionMetricsRegistry.forEachActive`, a concurrent-map iteration. No cost was measured; this is the
  code's structure, not a benchmark.
- While idle: one daemon thread that wakes once a second.
- During a stall: `dumpAllThreads` is a JVM safepoint operation (milliseconds for ~100 threads) every
  `repeat-seconds` for at most `max-dumps-per-stall` dumps; a dump is about 100-300 KiB on a 100-thread
  server, cut at 4 M characters. Disk use is bounded by `keep-files` x `max-dumps-per-stall` x that.
- The recorder cannot be the thread that hangs: anything that touches live server state (the region
  counters) runs on a helper thread with a 2 s limit, and the WARN line is written after the file.

## Limits (what it does not catch)

- **A source that never completed a tick is only a stall while a tick is in progress.** A boot that hangs
  before the first global tick completes is not recorded (otherwise the gap between plugin enable and the
  first tick of a slow boot would be a false alarm). The `Done (` line is the end of that window.
- **While the server is stopping** (`Bukkit.isStopping()`) no new stall opens: ticks cease by design and
  a long final world save is not a hang. A stall that was already open stays open and keeps recording.
- It detects "no tick completed", not "ticks are slow": a server at 5 TPS never trips it. `/perf` and
  the incident detector cover slowness.
- A JVM-wide stop (a stop-the-world GC longer than the threshold, a frozen container) stalls the recorder
  too; the dump then happens as soon as the JVM runs again, with the GC counts to explain it.
- `SIGKILL`/OOM-kill run no hook; see item 4 above.
- Region labels carry the engine's telemetry ids (`region #7 (world id 1)`), not world names; the thread
  dump is the primary evidence.
- Verified: unit tests (state machine with a fake clock, rotation, report content, config, markers) and one
  forced stall on the agent test server (see the coordination log entry). Not verified: a real panel,
  a real OOM kill, behaviour under a multi-minute real hang.

## Forcing a stall (test servers only)

`/stallprobe <global|region> <sleep|lock|spin> <seconds>` in the test plugin (registered only with
`-Dsourby.testplugin.probes=true`, see [AGENT-TEST-SERVER.md](AGENT-TEST-SERVER.md)) blocks the global tick
or the spawn region on purpose: `sleep` shows `TIMED_WAITING` inside tick code, `lock` shows `BLOCKED` with the
owner `stallprobe-lock-owner` named, `spin` shows `RUNNABLE` with `cpu=` growing between dumps. Never run it on
a server with players.

## Where to find the files (operators)

Panel file manager: `logs/stalls/` next to `logs/latest.log`. Each `stall-*.txt` is plain text; send the
whole file, plus `logs/latest.log` and the panel console history, with the bug report. Nothing in it is
uploaded anywhere.
