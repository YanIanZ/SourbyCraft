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
