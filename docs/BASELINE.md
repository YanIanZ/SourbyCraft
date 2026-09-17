# Performance baseline (PRD Phase 0)

The PRD forbids optimization before a measurable baseline exists, and blocks a merge
on an unexplained regression of roughly 3% in the directly affected benchmark. This
document describes the harness that produces those numbers, and — as important — what
it does not measure.

| Script | Role |
| --- | --- |
| `scripts/baseline_workloads.py` | The PRD section 9 workload catalog, as declared console load |
| `scripts/baseline_network.py` | Status-protocol client load for the network workload |
| `scripts/baseline_metrics.py` | Turns a JFR recording and OS samples into the metric set |
| `scripts/run_baseline.py` | Boots a server, applies a workload, writes `baseline.json` |
| `scripts/compare_baseline.py` | Section 85 table and the section 10 regression gate |
| `scripts/rank_hotspots.py` | Ranks CPU and allocation hot spots from a recording |

`scripts/profile_server.py` remains the single-run boot/idle smoke test. This harness
is the comparison instrument.

## Capturing a baseline

```sh
python3 scripts/run_baseline.py build/libs/SourbyCraft-slim.jar \
    --workload players-50 --output build/baselines/main-players-50 \
    --warmup 120 --duration 600 --heap-mib 6144
```

`--workload all` captures the whole set in sequence into one directory per workload,
plus an `index.json`. The output directory must not already exist. Each run writes:

```text
baseline.json     metrics, workload declaration and section 85 provenance
profile.jfr       the recording every metric is derived from
jfr-summary.txt   event inventory for the recording
gc.log            JVM collector log
server.log        full console output, including every workload command's response
threads.txt       thread dump taken at the end of the measurement window
heap.txt          heap summary taken at the end of the measurement window
```

The runner changes no performance-related setting. It writes only `eula.txt`, a
loopback-bound `server.properties`, and a utility TOML that disables auto-update and
ViaVersion provisioning so a run cannot reach the network for anything but its own
load. Heap size and collector are explicit arguments and are recorded in provenance.

## Comparing

```sh
python3 scripts/compare_baseline.py \
    build/baselines/main-players-50 build/baselines/candidate-players-50
```

Exits non-zero if either input is uncertified, when a gated metric regresses past `--threshold` (default `0.03`), and
also when the two runs differ in pinned provenance — Java version, platform, CPU
count, heap, JVM flags, JFR settings, warmup, duration, asserted client count. Pass
`--allow-provenance-drift` to allow descriptive comparison despite drift; the report
still labels the gate blocked. This option never permits uncertified inputs.
Narrow the gate to the metrics a change actually targets with repeated `--gate`.

Variance is real. Repeat a run before trusting a delta near the threshold; a developer
desktop running builds alongside a measurement is not a controlled performance lab.

## Workloads

All workloads run on generated terrain (`minecraft:normal`), never superflat. Superflat
has a convenient known ground height, but it is representative of nothing: it changes
chunk generation cost, block variety and therefore random-tick load, lighting,
heightmaps and collision shapes. Entities are placed per column with
`execute positioned <x> 0 <z> positioned over world_surface run summon ~ ~ ~`, so they
land on the surface rather than inside or above it.

Generated terrain has to be generated, and that cost must not land inside the
measurement. Two things keep it out: each plan declares a settle period after setup,
scaled to forceloaded chunk count for the player workloads, and `--world` copies a
pre-generated world in. Use `--world` for anything you intend to compare — it keeps the
terrain identical across runs, which the section 85 table reports as `World`, and it
removes generation from the window entirely.

```sh
python3 scripts/run_baseline.py build/libs/SourbyCraft-slim.jar \
    --workload players-50 --output build/baselines/players-50 \
    --world build/worlds/baseline-world --cache-from build/baselines/idle
```

| Workload | Exercises | Needs clients |
| --- | --- | --- |
| `idle` | Fixed cost of runtime, telemetry and region scheduler | no |
| `players-10/50/100` | Chunk residency and entity population of dispersed players | yes |
| `entity-stress` | Entity tick, collision, item merge and despawn | yes |
| `chunk-stress` | Generation, load, integration, unload and save | no |
| `network-stress` | Accept, decode, encode, flush, connection teardown | no |

`players-N` is a spatial-distribution model, not a player simulation: N disjoint
forceloaded chunk clusters, each holding a survival entity mix. It reproduces what N
dispersed players would keep resident and ticking. It does not reproduce their network
traffic, their entity-tracking cost, or the chunk streaming they cause by moving.

Every plan states its own limits in `fidelity`, and those statements are copied into
`baseline.json` so a number is never separated from what produced it.

### The client gap

Entity activation range is computed around connected players. With zero players
connected, no entity is activated, so mob AI, goal selection and pathfinding do not
run — a run with no clients measures the *inactive* entity path.

Workloads that depend on that gate set `requires_connected_players`. The runner does
not fake a player and does not pretend the gap away: it marks such a run **not
certified** unless the operator asserts attached clients with `--connected-players N`,
and `compare_baseline.py` prints that on any report using it. An uncertified run keeps
all of its evidence; it is simply refused as a regression reference.

To capture a certified `players-N` or `entity-stress` baseline today, attach N real
clients to the loopback port before the warmup ends and pass `--connected-players N`.
Closing this properly means a headless client driver, which is the natural follow-up
to this harness.

### Version-sensitive commands

Workload load is built from vanilla commands, and their syntax moves between Minecraft
versions — 26.2 renamed gamerules to snake_case (`doMobSpawning` became `spawn_mobs`,
`doDaylightCycle` became `advance_time`, `doWeatherCycle` became `advance_weather`).
The runner scans the console output produced by its own setup and **fails the run** if
the server rejected any command, rather than recording a baseline whose declared
conditions were never applied. If that fires after a Minecraft update, fix the command
in `baseline_workloads.py`; `--allow-command-errors` exists for diagnosis, not for
producing a baseline.

## Metrics and their sources

Every metric names its source, and an unsupported metric is reported as
`{"available": false, "reason": ...}` rather than defaulting to zero.

| Metric | Source | Note |
| --- | --- | --- |
| TPS, MSPT | `dev.iyanz.sourbycraft.PerformanceSnapshot` | One sample per second, build 44 and later |
| CPU | `jdk.CPULoad` | Process and machine fractions |
| GC pauses | `jdk.GCPhasePause` | Stop-the-world only, not MXBean collection time |
| Allocation rate | `jdk.ThreadAllocationStatistics` | Cumulative per-thread counters; GC-independent, with per-thread attribution |
| Allocation (cross-check) | `jdk.GCHeapSummary` pairs | Heap used before a collection minus after the previous one |
| Heap after GC | `jdk.GCHeapSummary` | Retained size, not peak occupancy |
| RSS | `ps -o rss=` | Sampled from the OS once a second |
| Network throughput | the load generator | Client-observed round-trips and bytes |

`mspt` is the distribution of the worst region's average MSPT across the published
samples — it is not a per-tick histogram. The server's own in-window percentile
estimates are reported separately as `reported_estimated_p95_mspt` / `_p99_`, so the
two are never confused. Chunk request and generation latency are not yet directly
instrumented; read them from the recording.

Telemetry samples carry a `MetricState`. `AVAILABLE` and `WARMING` samples both hold
freshly collected values and both count: `WARMING` means only that some longer window
is not yet fully covered, and since the collector needs fifteen minutes to fill its
longest window, a run shorter than that is entirely `WARMING` and still valid — the
JFR event reads the five-second window. `samples_by_state` and `long_windows_covered`
record which regime a run was in. `STALE` samples republish the previous sample's
values and are excluded so a duplicate is not weighted twice.

Short workloads may collect no GC at all — a lightly loaded server at a large heap can
run a minute without a collection — in which case GC and allocation are correctly
reported unavailable rather than as zero. That is another reason the default
measurement window is ten minutes.

The profiler is part of what you are measuring. In the 600-second idle baseline the
single heaviest allocating thread was `spark-async-sampler-worker`, at 101 MiB of a
178 MiB total — 57% of all allocation on an otherwise idle server. `top_threads` makes
that visible; read a baseline with it in mind rather than attributing it to the server.

RSS is the noisiest metric here. Two runs of the same jar minutes apart have differed
by over 80% on a desktop with other work in flight. Gate on it only on a quiet machine,
and only with repeated runs.

Allocation is measured two ways, because the obvious way does not always work.

`allocation` comes from `jdk.ThreadAllocationStatistics`: each thread's cumulative
allocated bytes, carrying no stack trace, so it is cheap to read and keeps working on a
server that never collects. It also attributes allocation per thread, which the heap
estimate cannot. It undercounts — a thread that starts and exits between two samples is
never observed — and it measures only the span the samples actually cover.

`allocation_from_gc` is the heap-occupancy estimate: bytes allocated between two
collections are the heap used before one minus the heap used after the previous. It was
cross-checked against `jdk.ObjectAllocationSample` weights on a synthetic load and agreed
within about 12%. It needs at least two collections, and **an idle server at a large heap
does not collect at all** — a 600-second idle run at 6 GiB produced zero `GCPhasePause`
and zero `GCHeapSummary` events, so this estimate, and every GC metric, was correctly
reported unavailable. Do not read that as "no allocation"; read `allocation` instead.

Both avoid `jdk.ObjectAllocationSample` as a primary source on purpose: printing it also
serializes every stack trace in the recording.

## Certification

`run_baseline.py` refuses to certify a run as a comparison reference when any of these
hold, and records the reason in `baseline.json`:

* the workload needs connected clients and none were asserted
* tick telemetry was unavailable in the recording
* the worktree was dirty, so the jar cannot be tied to a commit
* the server rejected a setup command
* the measurement window was shorter than 300 seconds
* another server was already running when the run started
* HEAD moved while the measurement was running
* the machine averaged more than 10% CPU on work other than this server

`run_baseline.py` also refuses to *start* on a dirty worktree, because such a run could
never be certified and a measurement window is ten minutes long. `--allow-dirty` starts
anyway. The tree and HEAD are re-read when the window closes as well: a commit landing
mid-measurement means the jar and the repository no longer describe the same thing.

The machine checks exist because a measurement that shares the machine is not a
measurement, and nothing else in this list can see that. `run_baseline.py` refuses to
start at all while another process is running a server jar — pass
`--allow-shared-machine` to measure anyway, and the run will not be certified.
Afterwards, `foreign_fraction` (the machine total minus this JVM's own share, from
`jdk.CPULoad`) shows what the box was doing that the server was not; a sustained
non-zero value means the numbers describe a contended machine. Both checks were added
after two baselines were found running against each other with every other rule
passing.

## Ranking hot spots

```sh
python3 scripts/rank_hotspots.py build/baselines/players-100/profile.jfr \
    --output build/baselines/players-100/hotspots.md
```

Produces the ranked evidence an optimization may be argued from: CPU by self frame,
by inclusive frame and by thread; allocation by type, by allocation site and by thread.
It picks up the workload's fidelity statements from the `baseline.json` beside the
recording and reprints them at the top, so a ranking is never read apart from the
conditions that produced it.

The report leads with how much of the tick budget the run used. Below 20% it says so
loudly, because a CPU ranking describes where time went and not whether any of it was a
problem. The first loaded profile used 1.1% of its budget — 0.53 ms of 50 — so its top
entry at 31.9% was the largest slice of nearly nothing: 0.111 of one core on an
eight-core machine. Nothing there can justify an optimization, and any improvement to it
would land below run-to-run noise.

The allocation table is cross-checked against the counters and says so. JFR's
allocation sampler favours large objects, so a site allocating big arrays dominates it
out of proportion to the bytes actually allocated. On the first loaded profile the
sampled total came to 6.5x what `jdk.ThreadAllocationStatistics` measured, and the site
at the top of the table — 83% of it — turned out not to be a meaningful allocator at
all: it never appeared in the CPU ranking, and the run collected four times in ten
minutes. Confirm any allocation target against the counter total and the CPU ranking
before acting on it.

Four things it is not. Execution sampling sees only Java frames on threads the JVM
sampled, so native work, GC and JIT compilation are unattributed. Sample counts are
proportional to time, not measured time. Allocation weights are extrapolated from
sampled allocations. And a high rank is a candidate to investigate, not a defect.

It also reports what share of self samples is the profiler and Sourby telemetry rather
than the server — on a lightly loaded recording that share is not small.

## Recording a result

Copy the `compare_baseline.py` report into the PR. It already carries the section 85
provenance table, the before/after/delta table, the certification state of both runs,
any provenance drift, and the gate verdict.

Also answer the section 84 questions in prose: what was slow, how it was measured, why
it was slow, what changed, what improved, and what the risks are.

## Soaks

A soak asks one question a five-minute window cannot: does anything grow and not come
back. `run_baseline.py` answers it with `metrics.drift`, which compares the first
quarter of the window against the last for resident memory, heap after GC, and tick
duration.

Budget for the shutdown. Regions are saved one at a time on a single
`RegionShutdownThread`, so a run holding forty-six regions needs roughly seven minutes
to stop. A shutdown that stalls no longer throws away the run: `profile.jfr` is flushed
before the stop command, so the metrics are still collected and `certify()` refuses the
run as a comparison reference instead.

### First soak — 50 players, two hours, 6 GiB (NOT CERTIFIED)

Three failures with one cause.

| | first quarter | last quarter |
|---|---|---|
| tick | 4.0 ms | 2519 ms (peak 14.1 s) |
| heap after GC | 6.12 GiB | 6.42 GiB |

The live set after each Full GC is the clearer trace: 3.48 GiB at 3.7 min, ~6.0 GiB by
16 min, then flat between 5.65 and 6.13 GiB for the remaining 110 minutes — pinned
against a 6.0 GiB ceiling. The server spent **40.7 of the 120 minutes in GC pause**
across 2,205 Full GCs, and the last compactions recovered 142 MB of 6144 MB in 3.4 s.
That is heap exhaustion, and everything else follows from it: 33 of 50 clients timed
out (all 50 were still connected at the 44-minute check), 862 of 1312 telemetry
snapshots went `STALE` because regions were not ticking often enough to publish, and
shutdown could not finish its serial region walk.

It does **not** yet show a leak. Five-minute 50-player runs already sit at 5.33 GiB p50
with 50 regions, so 6 GiB left ~13% headroom before the soak started, on a 16 GB
8-core machine that also hosted the client swarm at 89% machine CPU. Once a heap is
full, a leak and an undersized heap are indistinguishable, because growth is clamped by
the ceiling.

The discriminator is the same 6 GiB ceiling at a load the machine can sustain: ten
players for two hours, whose five-minute steady state is ~3.0 GiB. A live set still
near 3 GiB after two hours means residency tracks the players and there is no leak; a
live set that climbs to the ceiling anyway means there is one.

### What the soak says about capacity

Terrain generation dominated the soak: 61.6% of CPU samples and 86.9% of allocation
sat on the two `Paper Common Worker` threads, because fifty clients flying randomly for
two hours never stop entering ungenerated terrain. The top sites are
`DensityFunctions$Ap2.fillArray`, `LinearPalette.<init>` and
`PalettedContainer.reencodeContents` — chunk generation, not gameplay.

Raising the worker count is the obvious lever and it does not apply here. The certified
A/B at ten players shows six workers cost +22.3% mean and +24.6% p95 MSPT to buy −38.6%
p99 and −36.6% max: on eight cores the extra workers are taken from the region threads,
trading typical tick for tail. During the soak the machine was already at 89% CPU with
the client swarm on the same box, so there was nothing left to give.

The soak therefore measured this machine's ceiling, not SourbyCraft's. Capacity numbers
for fifty players need either more cores and memory than 8/16 GB, or a reduction in the
cost of generating a chunk.

### Second soak — 10 players, two hours, 6 GiB (CERTIFIED)

The first certified multi-hour soak, and the answer to the question the first one left
open. Ten clients, 300 entities that this time actually existed, 10/10 clients still in
play after two hours, clean shutdown in 8.1 s.

| | first quarter | last quarter | change |
|---|---|---|---|
| resident memory | 4.93 GB | 4.64 GB | **−5.9%** |
| heap after GC | 3.88 GB | 3.83 GB | **−1.3%** |
| tick | 5.312 ms | 5.278 ms | **−0.6%** |

Nothing grows. Across 518 collections the heap-after-GC mean is flat, resident memory
falls, and tick is unchanged over two hours. TPS held 19.85 mean and 19.98 p50; GC took
46.6 s of the 7200 s window (0.65%, against 34% in the fifty-player run).

**There is no memory leak.** The first soak exhausted its heap because fifty players and
fifty regions need more than 6 GiB, not because anything retains. Five Full-GC live-set
points during the run suggested a +310 MB/hour climb; the full 518-sample series shows
that was noise in an unevenly spaced sample, which is why the drift comparison uses the
whole window rather than GC events.

Two things this run exposed, neither affecting the above:

* **Telemetry never leaves `WARMING`** — fixed. All 6302 usable samples reported state
  `WARMING` after two hours, and anything gating on `AVAILABLE` saw nothing. Readiness
  was derived from the longest-lived region generation's coverage, but regions split,
  merge and die as players move, so no generation ever spanned fifteen minutes and the
  window was never "covered" however long the server ran. It now asks what
  `MetricState.WARMING` documents: whether the collector has been watching long enough.
  A twenty-minute run afterwards reports 361 `AVAILABLE` samples, with the freshness
  diagnostic climbing `FIFTEEN_MINUTES: observed 581s of 900s` to `891s of 900s` before
  it flips. Two earlier attempts fixed real defects that were not this one; the
  diagnostic string exists because an empty one is what let that happen.
* **The workload delivers fewer regions than it declares.** `players-10` designs ten
  sites 128 chunks apart, one region each, and `expected_min_regions` is 10 — but
  `active_regions` ran 3 to 10, mean 5.73. Both certified A/B runs show the same (5.88
  and 5.60), so it is systematic, not incidental. The plan's own fidelity note says to
  check this; certification does not yet, and should not start gating on it before the
  cause is understood.

### The client swarm's movement is partly rejected

A twenty-minute ten-client run logged 4354 `moved wrongly` warnings against 31674 moves:
the server rejected **13.7%** of client movement and teleported those players back, and
the swarm reports 4790 position resyncs. Two of the ten clients account for 2757 of the
rejections, and five of the ten are fliers.

So `baseline_client.py` is still proposing positions the server will not accept, most
likely moving faster than the movement checks allow. The workloads remain usable —
clients stay connected and chunks load around them — but a run's player motion is not
what the driver intends, and the server is doing correction work that a real client
would not cause. Fix before any workload claims to model player movement cost.

### Chunk workers, re-measured on a workload that has entities in it

The first chunk-worker A/B ran before the silent-summon fix, so both sides had an empty
region lane — it measured a trade against nothing. Re-run with 300 entities present and
lane sampling attached, it does not merely shift; it reverses.

| Metric | 2 workers | 6 workers | first A/B |
| --- | ---: | ---: | --- |
| MSPT avg | 3.858 ms | 5.602 ms (+45.2%) | +22.3% |
| MSPT p50 | 3.706 ms | 5.790 ms (+56.2%) | +24.3% |
| MSPT p95 | 5.810 ms | 9.035 ms (+55.5%) | +24.6% |
| MSPT p99 | 10.018 ms | 14.351 ms (+43.3%) | **−38.6%** |
| MSPT max | 10.476 ms | 14.606 ms (+39.4%) | **−36.6%** |

The first run's headline — that more workers buy tail latency at the cost of the mean —
was an artifact. With a loaded region lane, six workers are worse at **every** percentile.

The lane split says why, and it is not core starvation:

| Lane | 2 workers | 6 workers |
| --- | --- | --- |
| `CHUNK_WORKER` | 2 threads, 1.06 cores | 6 threads, 1.27 cores |
| `REGION_TICK` | 4 threads, 0.21 cores | 4 threads, 0.27 cores |
| Total | 1.36 of 8 cores | 1.64 of 8 cores |

The region lane consumes 38% *more* CPU while ticking 45% slower, with six cores idle
throughout. The region threads are not short of CPU; they are doing more work. Extra chunk
parallelism manufactures region-thread work — chunk callbacks, entities loading into
regions — rather than relieving it, and allocation rate rises 5.0% with it.

**Leave the worker count alone.** More threads is not the lever on this hardware, and the
constraint is not the core count.

The lane table above is the corrected one. It first read 4 and 12 threads, because the
classifier folded `Worker-Main` — `Util.backgroundExecutor()`, a deprioritised ForkJoinPool
sized from the core count — in with `Paper Common Worker`, Moonrise's `WORKER_POOL` sized by
`Paper.WorkerThreadCount`. Separated, the counts match the setting exactly, which is what a
lane measurement has to do before anything is tuned from it.

The numbers survive an independent check. `LaneCpuSampler` derives them from `ThreadMXBean`
deltas; JFR's own `jdk.ThreadCPULoad` is a different mechanism entirely, and re-deriving the
split from it gives 1.06 and 0.21 cores at two workers against the sampler's 1.06 and 0.21,
and a 1.64-core total at six against the sampler's 1.64.

One caveat stands. Neither side certified: the 2-worker run measured 10.4% foreign CPU
against a 10% limit, because the Python client swarm shares the box and the harness counts
its own apparatus as competing load. Both sides ran under identical conditions, so the
comparison holds even though neither is usable as a reference baseline.

The earlier certified `ab-workers-2` and `ab-workers-6` baselines are superseded. They are
certified against a workload that silently contained no entities.

### Every baseline so far measured world generation

`run_baseline.py` has always had `--world`, and the `players-N` fidelity note has always said to
use it: *"seed a pre-generated world with --world so terrain generation does not land inside the
measurement window."* No run ever did. Every baseline, every A/B and the certified soak generated
their world from scratch while being measured.

The same workload, same settings, seeded against a pre-generated world instead:

| | fresh world | seeded | change |
| --- | ---: | ---: | --- |
| MSPT mean | 3.858 ms | 2.268 ms | −41% |
| MSPT p95 | 5.810 ms | 3.100 ms | −47% |
| MSPT p99 | 10.018 ms | 4.117 ms | −59% |
| `CHUNK_WORKER` | 1.06 cores (85%) | 0.39 cores (58%) | −63% |
| `REGION_TICK` | 0.21 cores (13%) | 0.26 cores (38%) | +24% |
| total CPU | 1.36 of 8 | 0.68 of 8 | −50% |
| active regions | 5.73–7.83 | 9.65 | ≈ the declared 10 |

Half the CPU and most of the tail latency were terrain generation — an artifact of how the
harness builds its world, not a property of the server.

This also explains the second fault the soak logged. `players-10` declares ten regions and was
delivering five to eight; seeded, it delivers 9.65. Chunk generation was delaying region
formation, so the workload under-delivered the region parallelism it exists to exercise. That was
never a region-system defect.

Chunk work is still the largest lane at 58%, but the residual is loading chunks from disk plus
generation where clients roam past the seed's coverage, which is not the same cost as building
terrain from noise.

The seed is a previous run's world with `dimensions/minecraft/overworld/entities/*.mca` removed.
Entities have to go: the workload summons its own three hundred on every run, and a seed that
keeps the last run's would stack them.

### The chunk-worker A/B, seeded

Re-run on the seeded world, six workers against two:

| Metric | fresh world | seeded |
| --- | ---: | ---: |
| MSPT avg | +45.2% | +8.4% |
| MSPT p50 | +56.2% | +9.6% |
| MSPT p95 | +55.5% | +6.4% |
| MSPT p99 | +43.3% | +5.3% |
| MSPT max | +39.4% | −36.3% |

Lane split, same mechanism and much milder: `CHUNK_WORKER` 0.34 to 0.47 cores, `REGION_TICK`
0.30 to 0.34.

The direction survives — six workers buy nothing — but the fresh-world run overstated the
penalty about fivefold. Most of that +45% was the extra threads contending with terrain
generation, not a cost of the threads themselves. The recommendation is unchanged and its
justification is weaker: leave the worker count alone because nothing is gained, not because
raising it is catastrophic.

**The seeded deltas are inside the measurement noise.** Both runs measured 24.9% and 28.1%
foreign CPU against a 10% limit, and a three-point difference in background load between two
runs is the same order as a 5-9% delta. The honest claim is "no benefit, some cost", not the
specific percentages.

Foreign CPU is the binding constraint on this kind of work, and it is not the harness's fault.
Measured while the runs were being written up, the machine was carrying RobloxPlayer at 98.6%
of a core, contactsd at 56.2%, two Discord processes at 43.6% together and WindowServer at
18.7% — about two of eight cores, which is the 24.9% to 28.1% those runs reported almost
exactly. The client swarm was blamed for this first, without being measured; it is not the
largest contributor.

So the guard is working as designed and there is nothing to re-engineer. What it needs is a
quiet desktop: close the browser, the game and the chat client, then measure. A run that
reports foreign CPU near zero is worth more than three that do not, and no amount of accounting
cleverness recovers a measurement taken beside a game engine.
