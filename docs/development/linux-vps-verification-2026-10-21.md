# Linux VPS verification: memory reporting (RSS/cgroup) and a long soak, 2026-10-21

Status: **MEASURED / functional evidence only.** Shared VPS, no quiet-machine certification, no Minecraft
clients, console-driven load. Nothing here is a performance claim, a qualification, or a release-wide
statement (DEVELOPMENT.md truthfulness policy sections 2, 3, 5). Worker: sonnet-vps. Board tasks:
`task_56b78dd40acb5e62` (TODO.md:337 "Verifikasi RSS/cgroup di Linux/container") and `task_633142eca8b94d67`
(heap/thread/queue recovery after load + long soak).

**Follow-up status (working tree, uncommitted, 2026-10-21):** F1 is fixed in code (`ContainerMemory` resolves the
process's own cgroup through `/proc/self/cgroup`, v2 and v1, walks up to the smallest finite ancestor limit,
keeps the fixed root paths as the container-view fallback) and unit-tested against temp-dir fixtures
(`ContainerMemoryCgroupTest`); it has NOT been re-run on Linux with a new jar, so the host-view result above
still describes r47. F2: `/sys` now prints `Process RSS: <VmRSS>` and `Cgroup memory (incl. page cache): x%`
instead of `RSS: x%`. F3: `/sys` `RAM:` is now `Heap used:` and the HUD `RAM` bar text is `Heap` (command names
`/rambar`, `/perf` HUD keys and toggles keep their names). F4 to F6 unchanged.

**Status 2026-10-25 (PR #62):** F1 to F3 were committed in `530ebc9` (2026-10-21) and are in every release tag
from `v26.2-r48` on; they were still not re-run on Linux with a new jar. PR #62 (`e7a27c8`; code written and
statically reviewed; not built; not tested by the owner) addresses F4 (`ContainerMemory.fmt` prints two decimals
above 1 GiB), F5 (the `/perf cpu` label says "live Java threads"; the OS thread count is not shown) and the swap
observation in the Task 1 verdict (`memory.swap.max` = `0` now gives a swap total of 0, and the HUD hides the
swap segment). F6 is an observation, not a defect. The findings and numbers below are unchanged.

## 1. Environment

| Item | Value |
| --- | --- |
| Host | Ubuntu 24.04.5 LTS, kernel 6.8.0-147-generic, cgroup v2 (`cgroup2fs`, `nsdelegate,memory_recursiveprot`), 6 vCPU AMD EPYC (2.0 GHz base, `lscpu`), 11.7 GiB RAM, 4 GiB swap, ~80 GiB free |
| Neighbours | Production Docker (dengarin-web, postgres, redis, caddy) running throughout; not touched. Same 4 containers `Up` at the start and the end. Host load was not recorded per sample (idle load average about 0.25 before the first run). |
| JDK | `/opt/sourby-agent/jdk`, Temurin 25.0.4.1+1 (`openjdk 25.0.4.1 2026-08-18 LTS`) |
| Jar | official release `v26.2-r47` asset `SourbyCraft-26.2-REL.jar`, SHA-256 `411eadb035cfde40ddb2264bd38f7c3cc603878b410ba1079e5a6dbb60af938d` (matches the `.sha256` asset, downloaded with `gh release download`, re-verified with `sha256sum` on the VPS). Release target commit `2c8660b75ee1ac66cd7a89fa83a170ada719dffa` (the worker's tree was not used; this is the CI-built artifact, not a dirty build). `/ver`-style banner in the log: `SourbyCraft Build 47 ... for Minecraft 26.2` |
| Server | `online-mode=false`, `server-ip=127.0.0.1` (never exposed, no firewall change), ports 25601/25602/25603, `view-distance=6`, `simulation-distance=6`, `level-type=minecraft:normal`, seed 440044, `eula=true`, one server at a time |
| JVM flags | `-Xms1G -Xmx4G -XX:+UseG1GC -XX:NativeMemoryTracking=summary -Xlog:gc*:file=gc.log` (NMT summary adds a small overhead; named here so nobody reads RSS as "default flags"). No other tuning |
| Limits | every Java process under `systemd-run --scope -p CPUQuota=400% -p MemoryMax=8G -p MemorySwapMax=0 nice -n 10 ...` (`MemorySwapMax=0` is an extra restriction of mine so the test JVM can never use the swap the production services share). The JVM saw 4 processors and an 8.0G limit |
| Plugins | The jar auto-provisions ViaVersion, ViaBackwards and ProtocolLib into `plugins/`; 2 to 3 of them were loaded per run (`/sys` reported `Enabled / loaded: 2 / 2` in run A and `3 / 3` in run B). Online access: the first boot downloaded Mojang jar and libraries (cold boot 105 to 171 s on this shared, niced host) |

Two views of the cgroup filesystem were used, because they behave differently for the code under test:

* **Host view (run A):** a plain `systemd-run --scope`. `/proc/<pid>/cgroup` is `/system.slice/<unit>.scope`;
  `/sys/fs/cgroup/memory.max` and `/sys/fs/cgroup/memory.current` **do not exist** (the root cgroup has no
  such files). This is what a SourbyCraft started as a systemd service with `MemoryMax=` sees.
* **Container view (runs B and C):** the same scope started inside `unshare --cgroup --mount --propagation
  private`, with a fresh cgroup2 mount bind-mounted over `/sys/fs/cgroup` inside that private mount namespace
  only. The namespace root is the scope, so `/sys/fs/cgroup/memory.max` = 8589934592 and `memory.swap.max` = 0,
  which is what a Docker or Pterodactyl container on a cgroup v2 host with a private cgroup namespace shows.
  This is an **emulation**, not a real container (Docker was deliberately not used). The mount never left the
  private namespace (`mount | grep -c cgroup2` on the host stayed 1 afterwards).

## 2. Method (exact, secrets omitted)

SSH used the credentials env file and askpass helper from the worker protocol (not reproduced here). Layout
under `/opt/sourby-agent/runs/`: `jar/`, `tools/` (the scripts below), one directory per run.

```sh
# start (view = host | cgroupns); stdin of the JVM is <run>/cmd.fifo
tools/start_server.sh <rundir> <rundir>/SourbyCraft-26.2-REL.jar <host|cgroupns> 4G
#   -> systemd-run --scope --unit sc-agent-<run> -p CPUQuota=400% -p MemoryMax=8G -p MemorySwapMax=0 \
#        [unshare --cgroup --mount --propagation private ... bind-mount cgroup2 over /sys/fs/cgroup ...] \
#        bash inner.sh      # exec 3<>cmd.fifo; exec nice -n 10 java -Xms1G -Xmx4G -XX:+UseG1GC \
#                           #   -XX:NativeMemoryTracking=summary -Xlog:gc*:file=gc.log -jar <jar> --nogui <&3
# drive + sample (python3, Ubuntu system python)
python3 tools/runner.py <rundir> --idle 180 --load 4800 --release 900 --interval 60 --sites 4 --scale 6 --churn 300 --gcrun
python3 tools/extra.py  <rundir>      # secondary sampler: `perf storage` + `perf lanes` at wall-second :40 each minute
```

Every sample (about every 60 s) records, from the OS: `/proc/<pid>/status` (VmRSS, RssAnon, RssFile, VmSwap,
VmHWM, Threads), `/proc/<pid>/smaps_rollup` (Rss, Pss, Anonymous), the scope's `memory.current`, `memory.max`,
`memory.peak`, `memory.stat` (anon, file, kernel, slab, ...), `pids.current`, what the JVM's own mount namespace
shows at `/proc/<pid>/root/sys/fs/cgroup/`, per-thread-name counts from `/proc/<pid>/task/*/comm`,
`MemAvailable`; from the JVM: `jcmd GC.heap_info` and `jcmd VM.native_memory summary`; from the server (console
commands through the FIFO, raw text kept): `perf memory`, `tps`, `mspt`, `perf async`, `perf chunks`,
`perf governor`, `perf entities`, `ram`, `spec`, `sys`. The error scan counts `ERROR|Exception|watchdog|SEVERE|
OutOfMemory|deadlock` in the whole console log at each sample (the one permanent hit is the boot line
`[Aurora] COMPLETE | 14/14 stages completed without error`, which matches "error"; it is not an error).

Workload (console only, no players, so mob AI mostly runs on the inactive path; this is a memory/chunk/entity
churn load, not a gameplay simulation): 4 sites 128 chunks apart, each a 15x15 chunk `forceload add` square
(900 chunks); wait until `execute ... if loaded` reports the far corners loaded (the first attempt without that
wait lost most summons to "That position is not loaded"; it is kept as run A0 and not used); summon per site
48 zombies, 24 skeletons, 12 cows, 12 sheep, 12 villagers and 72 cobblestone item entities, so about 800 to
1140 entities in total; `random_tick_speed 3`, `spawn_mobs false`. Every ~9 minutes (5 min timer plus the
load-wait) a churn wave forceloads the next, shifted generation of sites on fresh terrain, populates it, then
removes the previous generation's forceloads and kills the entities in its boxes. Release:
`forceload remove all` plus `kill @e[type=...]` for the workload types. Run B: idle 3 min, load 80 min
(9 waves), release 15 min, then `jcmd GC.run` and one more sample. 101 samples, median spacing 60 s, max 75 s.

## 3. Task 1: reported memory values against the OS (task_56b78dd40acb5e62)

Code that produces each number: `RuntimeSampler.sample` (perf/RuntimeSampler.java:13-42) takes heap and non-heap
from `MemoryMXBean`, RSS from `/proc/self/status` (`readRss` :49-55, `parseRss` :57-70), the container percent
from `ContainerMemory.usagePercentOrNaN` (util/ContainerMemory.java:43-48), the thread count from
`ThreadMXBean.getThreadCount` (:39). `/perf memory` renders them (command/PerfMemoryView.java:23-28), `/spec`
(command/SpecCommand.java:99-114) and `/sys` (command/SysCommand.java:110-120, 277-282) render subsets.

Values are printed as the console showed them (G above 1 GiB with one decimal, M below: `ContainerMemory.fmt`
:137-141), OS values in GiB from the same sample.

### Run A: host view (plain scope), jar r47

| Point | Reported heap used/committed/max | jcmd heap used/committed | Reported non-heap | NMT Class+Metaspace+Code | Reported Process RSS | OS VmRSS (Pss) | `Container memory usage` | `/spec` Container in use | `/sys` `RSS:` | cgroup memory.current (anon+file) | `/spec` limit / memory.max |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 after boot (22:20) | 392M / 1.0G / 4.0G | 0.39 / 1.00 | 218M | 221M | 1.4G | 1.44 (1.43) | **unavailable** | **absent** | **absent** | 1.48 (1.40+0.07) = 18.5% | 8.0G / 8.00G |
| 2 loaded, 927 entities, 1444 chunks (22:35) | 1.4G / 2.8G / 4.0G | 1.41 / 2.79 | 236M | 256M | 3.5G | 3.50 (3.49) | **unavailable** | **absent** | **absent** | 3.58 (3.46+0.11) = 44.8% | 8.0G / 8.00G |
| 3 released, 5 min later (22:40) | 1.2G / 3.1G / 4.0G | 1.20 / 3.14 | 234M | 254M | 3.7G | 3.69 (3.69) | **unavailable** | **absent** | **absent** | 3.82 (3.66+0.14) = 47.7% | 8.0G / 8.00G |
| 4 after `GC.run` (22:41) | 327M / 1.1G / 4.0G | 0.32 / 1.07 | 233M | 253M | 1.8G | 1.77 (1.77) | **unavailable** | **absent** | **absent** | 1.89 (1.74+0.14) = 23.6% | 8.0G / 8.00G |

### Run B: container view (cgroup namespace emulation), jar r47

| Point | Reported heap used/committed/max | jcmd heap used/committed | Reported non-heap | NMT Class+Metaspace+Code | Reported Process RSS | OS VmRSS (Pss) | `Container memory usage` | `/spec` Container in use | `/sys` `RSS:` | cgroup memory.current (anon+file) | `/spec` limit / memory.max |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 after boot (22:49) | 494M / 1.0G / 4.0G | 0.49 / 1.00 | 225M | 230M | 1.4G | 1.40 (1.40) | 18.0% | 1.4G | 18.0% | 1.44 (1.37+0.06) = 18.0% | 8.0G / 8.00G |
| 2 loaded, 1143 entities max, 2888 chunks max (00:10) | 1.6G / 3.6G / 4.0G | 1.60 / 3.55 | 244M | 276M | 4.4G | 4.44 (4.44) | 57.9% | 4.6G | 57.9% | 4.63 (4.41+0.20) = 57.9% | 8.0G / 8.00G |
| 3 released, 15 min later (00:25) | 1.2G / 3.8G / 4.0G | 1.19 / 3.77 | 244M | 276M | 4.7G | 4.67 (4.66) | 58.2% | 4.7G | 58.2% | 4.66 (4.63+0.00) = 58.2% | 8.0G / 8.00G |
| 4 after `GC.run` (00:25:59) | 356M / 1.2G / 4.0G | 0.35 / 1.16 | 244M | 276M | 2.0G | 2.02 (2.01) | 25.0% | 2.0G | 25.0% | 2.00 (1.98+0.00) = 25.0% | 8.0G / 8.00G |

Run C (container view, idle, 40 s after ready): `perf cpu` "live platform threads" 40, JVM
`java.threads.live` 41 (`jcmd PerfCounter.print`), `/proc/<pid>/status` Threads 58 and `Thread.print` 58 lines;
`perf memory` container usage 18.5%, `memory.current` 1592086528 B = 1.48 GiB of 8 GiB = 18.5%.

### Verdict per value

| Value | Verdict |
| --- | --- |
| Heap used / committed / max | MATCH jcmd within sampling lag (collector samples once per second; differences up to a few percent are timing) |
| Non-heap | MATCH within ~13% of NMT Class+Metaspace+Code (different accounting: `MemoryMXBean` non-heap is metaspace + code cache + compressed class space) |
| Process RSS | MATCH `VmRSS` / `smaps_rollup` in all 8 points, within the 0.1 GiB print resolution (largest gap 0.04 GiB). `fmt` shows 0.1 GiB steps above 1 GiB (util/ContainerMemory.java:137-141), so RSS growth below ~100 MB is invisible in `/perf memory` |
| Container limit (`/spec`) | MATCH 8.0G = `memory.max` in both views. In the host view it is right only because the fallback `OperatingSystemMXBean.getTotalMemorySize()` is cgroup-aware in JDK 25 (util/ContainerMemory.java:61); the file read at :52 found no file |
| Container memory usage, container view | MATCH `memory.current` / `memory.max` (57.9% vs 57.9%, 18.0% vs 18.0%, 25.0% vs 25.0%) |
| Container memory usage, host view | **MISMATCH (F1)** |
| `/sys` `RSS:` percent | **MISMATCH of meaning (F2)** |
| `/sys` `RAM:` and the HUD `RAM` bar | **MISMATCH of meaning (F3)** |
| Threads | MATCH by definition, but see F5 |

### Findings (code file:line)

* **F1: no container memory usage unless /sys/fs/cgroup is the namespace root.** `ContainerMemory.currentBytes`
  reads the fixed paths `/sys/fs/cgroup/memory.current` (util/ContainerMemory.java:33) and the v1
  `/sys/fs/cgroup/memory/memory.usage_in_bytes` (:35); `detect` reads `/sys/fs/cgroup/memory.max` (:52). On the
  host cgroup tree (a systemd service or scope with `MemoryMax=`, the normal bare-metal/VPS deployment, or a
  container runtime without a private cgroup namespace) these files are absent, so `usagePercentOrNaN` (:43-48)
  is NaN. Observed in run A at all 25 samples: `/perf memory` shows `Container memory usage: unavailable`
  (command/PerfMemoryView.java:28), `/spec` omits `Container in use` (command/SpecCommand.java:107-110), `/sys`
  omits the `RSS:` line (command/SysCommand.java:277-282). The limit is still right (MXBean fallback). The
  correct source is `/proc/self/cgroup` -> `/sys/fs/cgroup/<path>/memory.{current,max}`, walking up to the
  smallest ancestor limit. Container view (what Docker/Pterodactyl on cgroup v2 normally give) works. cgroup v1
  was not tested.
* **F2: `/sys` labels container memory as RSS.** `RSS: 57.9%` (command/SysCommand.java:279-280, from
  `runtime.rssPercent()`, set in perf/RuntimeSampler.java:34) is `memory.current / memory.max`, which includes
  page cache and kernel memory (`memory.stat` file 0.20 GiB at the peak in run B), not process RSS. At the run B
  peak: label says 57.9%, true `VmRSS / limit` = 4.44 / 8 = 55.5%. `/perf memory` says "Container memory usage"
  (correct) and a separate "Process RSS" row.
* **F3: `RAM` is heap.** `/sys` `RAM: [bar] 43% 1771 / 4096 MB` (command/SysCommand.java:110-117) and the HUD
  `RAM used/max` bar (hud/HudBars.java:560-566) are `heapUsed / heapMax`. In run B at the peak they showed about
  1.6 / 4.0 GiB while the process RSS was 4.44 GiB and the cgroup 4.63 GiB. Not a wrong number, a misleading
  name for operators sizing a container.
* **F4: RSS print resolution.** `ContainerMemory.fmt` (:137-141) rounds to 0.1 GiB above 1 GiB, used by
  `PerfRows.bytes` (command/PerfRows.java:18).
* **F5: "live platform threads" is Java threads only.** `ThreadMXBean.getThreadCount` (perf/RuntimeSampler.java:39,
  rendered at command/PerfCpuView.java:22-23) was 40 against `/proc` Threads 58 in run C; the 17 to 18 difference
  is JVM-native threads (G1, VM, compiler, signal). Fine as a label, wrong as a proxy for `pids.current`.
* **F6 (observation, not a SourbyCraft defect): RSS follows committed heap and does not come back without a full
  GC.** After the load was released, heap used fell to 1.2 GiB but G1 kept 3.86 GiB committed and RSS stayed at
  4.77 GiB for the whole 15 minutes (`cgroup anon` 4.74 GiB); a `jcmd GC.run` (Full GC, 1228M->352M, 1024 ms)
  cut RSS to 2.06 GiB and committed to 1.19 GiB. Peak RSS was about 0.9 GiB above heap committed (4.95 GiB RSS at
  4.04 GiB committed; NMT total committed 4.52 GiB, so about 0.43 GiB of RSS is outside NMT: libraries, malloc
  arenas). For container sizing this means RSS tracks `-Xmx` plus roughly 1 GiB here, not heap used. SourbyCraft
  sets no periodic GC or uncommit (not checked in the launcher; the flags above were mine).

Also seen once (cosmetic): `perf memory` output was missing from one sample taken while a `forceload` burst was
being processed (run B sample 5, 22:51:50); the console answered, the slice window missed it.

**Task 1 verdict: PARTIAL.** Heap, non-heap, RSS, limit and (container view) usage verified against `/proc`,
`smaps_rollup`, cgroup v2 files and NMT at three points (boot, loaded, released) plus after a forced GC, in two
cgroup views, with three mismatches reported (F1 real, F2/F3 labels). Not verified: a real Docker/Pterodactyl
container, cgroup v1, memory.high or OOM-kill behaviour near the limit, swap rows (`ContainerMemory.swapTotalBytes`
:86 treats a cgroup `memory.swap.max` of `0` as "unknown" because of `v > 0`; the scopes had swap max 0 but the
HUD swap row cannot be read from the console), non-Linux, `MaxRAMPercentage` without `-Xmx`.

## 4. Task 2: soak and recovery (task_633142eca8b94d67)

Run B: JVM uptime 110 min, 9 churn waves, 101 samples. All numbers below are from this one run on this shared
4-CPU-quota host. They say what happened here, not what the engine can do.

| Phase (wall) | Samples | VmRSS GiB (min / med / max) | cgroup memory.current GiB | heap used MiB | heap committed MiB | /proc Threads | entities | loaded chunks | worst-region TPS (`sys`) | GC time % (`sys`) |
|---|---|---|---|---|---|---|---|---|---|---|
| idle 22:47-22:49 | 3 | 1.39 / 1.40 / 1.40 | 1.43-1.44 | 412-497 | 1024 | 60-65 | 0 | 0 | n/a | 0-0.5 |
| load 22:50-00:10 | 81 | 1.69 / 4.08 / 4.84 | 1.73 / 4.20 / 5.00 | 497 / 1657 / 3406 | 1474 / 3328 / 4044 | 60-66 (med 62) | up to 1143 | 0 / 1444 / 2888 | 1.2 / 13.9 / 18.9 | med 1.4, max 13.3 |
| release 00:10-00:25 | 16 | 4.44 / 4.66 / 4.67 | 4.64 / 4.66 / 4.89 | 1194 / 1439 / 1584 | 3640 / 3860 / 3860 | 62-65 (63 at the end) | 0 | 0 | n/a (no active region) | 0-4.2 |
| after `GC.run` 00:25:59 | 1 | 2.02 | 2.00 | 359 | 1188 | 63 | 0 | 0 | n/a | 1.7 |

Load-phase cost of the workload on this host: `sys` MSPT p95 estimate median 154 ms, worst-region 993 ms at the
maximum, CPU starvation notice (`1307 to 8376 ms/tick owed but not scheduled`) in many samples, per
`perf governor` 17 configured platform threads in 8 pools for 4 available processors (chunk workers 1, chunk
I/O 1, region tick 4). The load was not calibrated to the quota; it overloaded it. Idle (no entities, no
forceloads) global TPS also read 13.3 to 20.0 with MSPT under 9 ms, so the host's noise is visible even there.

### Recovery

* **Heap:** `gc.log` post-GC heap by 10 minutes of JVM uptime (median MiB): 465 (boot), 1176, 1354, 1364, 1345,
  1185, 1359, 1208, 1430 (release start), 1201 (release). No monotonic climb across the 9 waves; the old-gen
  floor sits at 1.2 to 1.4 GiB while loaded and stays there after release because no concurrent cycle started
  (occupancy under the initiating threshold). Natural recovery of heap used within 15 minutes was therefore only
  to about 1.2 GiB (young-GC floor), against 0.41 to 0.50 GiB before the load. After `jcmd GC.run` it was 352 MiB
  (1228M->352M), below the pre-load reading, which is the evidence against a retained-object leak in this
  workload. 624 pauses logged; young pauses reached 2.5 s in three load windows and 0.7 s in the last, so GC
  pause length is the main thing to look at before anyone calls the load "healthy".
* **RSS and committed heap:** did not recover without a full GC (see F6): 4.77 GiB flat for 15 min, 2.06 GiB
  after `GC.run`.
* **Threads:** /proc Threads 60 at idle, 66 at the load maximum, 63 at the end of release and after `GC.run`
  (bounded, no growth). The only group that differs from idle at the end is `Folia Async Scheduler` 2 -> 5
  (the other groups and counts are identical); the extra three threads were still alive 15 min after the load,
  not shown to be a leak, not shown to be released either.
* **Queues:** `perf storage` pending I/O (chunk / poi / entity) was 0 in 73 of the 90 per-minute samples that returned the view (9 minutes missed the output); nonzero only
  inside churn waves (peak 1671 chunk, 704 entity; sampled once a minute so peaks may be missed) and 0 in every
  sample from 00:06 on, including the whole release phase. `perf lanes` showed 0.01 to 0.7 cores used of 4
  during release. Chunk-system loaded chunks and `perf entities` returned to 0 / 0 right after the release
  command. `perf async` printed "Async pathfinding: disabled" (default), so no async path queue was exercised;
  `perf governor` printed "no governed lane has been used yet"; `perf region` prints "Region coordinates /
  queues: not instrumented". Region scheduler task queues are therefore **not** observed by this run.
* **Errors:** 0 `ERROR`/`Exception`/watchdog/`OutOfMemory`/`SEVERE` lines in the console log of runs A and B
  (only boot-time WARNs: root user, offline mode, Unsafe deprecation, SIMD off, ProtocolLib untested version,
  `[spark] Timed out waiting for world statistics` once at 22:47:18). 0 "Can't keep up"/overloaded lines in the
  log. Shutdown (`stop` on stdin) exited cleanly in all runs: scope inactive, no java, runner or sampler left.
* **Host:** minimum `MemAvailable` 6.3 GiB during the soak, cgroup peak 5.0 GiB of the 8 GiB cap; the four
  Docker containers stayed `Up` (health `healthy`) before, during and after.

**Task 2 verdict: PARTIAL (functional).** For a 80-minute forceload + entity + chunk-churn load plus 15 minutes
of release on a 4-CPU-quota shared host: no errors, no thread growth beyond 6 over idle, pending I/O queues
drain to 0, heap used does not climb across waves and falls to 0.35 GiB after a full GC. Not shown: that heap used,
committed heap and RSS recover on their own (they do not within 15 minutes; only a full GC does), that scheduler
queues recover (not instrumented), anything with real players, plugins beyond the 2 to 3 auto-provisioned ones, or
a longer-than-110-minute uptime.

## 5. Not verified

* A real Docker/Pterodactyl container (cgroup namespace emulated), cgroup v1, `memory.high`/OOM-killer behaviour,
  `-XX:MaxRAMPercentage` sizing without `-Xmx`, swap rows.
* Region scheduler task queues and async pathfinding queues (disabled or not instrumented, see above).
* Player-driven load, plugin-driven load, the Sourby Demo panel, any quiet-machine or certified baseline.
* A natural (no `GC.run`) heap/RSS return after load; whether `G1PeriodicGCInterval` or similar changes F6.
* Whether pause times of 1 to 2.5 s are caused by SourbyCraft, the quota, noisy neighbours (nice 10, shared
  host) or G1 settings: only the log was kept, no CPU-bound reference run.
* Run A's load was shorter (12 min load, 1 churn wave) and was a smoke run for the three-point table, not a soak.
  Run A0 (first attempt, 112 entities of about 480 planned because summons raced chunk generation) was aborted
  and its data is not used.

## 6. Artifacts

* VPS, `/opt/sourby-agent/runs/`: `jar/`, `tools/`, and per run (`runA-host`, `runA0-aborted-host`, `runB-soak`,
  `runC-threads`) only `console.log`, `samples.jsonl`, `extra.jsonl`, `events.log`, `gc.log`, `server.properties`;
  worlds, libraries and jars were deleted. Nothing is running; no unit `sc-agent-*` is left.
* Local copies and the scripts: `/Volumes/Ext/DevCache/agent-tmp/sourbycraft/vps-evidence-2026-10-21/`
  (`runner.py`, `extra.py`, `start_server.sh`, `analyze*.py`, `points.py`, `run*-samples.jsonl`, `runB-gc.log`).
  Nothing from the credentials file is in them.
