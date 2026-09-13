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
| Allocation rate | `jdk.GCHeapSummary` pairs | Heap used before a collection minus after the previous one |
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

RSS is the noisiest metric here. Two runs of the same jar minutes apart have differed
by over 80% on a desktop with other work in flight. Gate on it only on a quiet machine,
and only with repeated runs.

The allocation-rate estimator was cross-checked against `jdk.ObjectAllocationSample`
weights on a synthetic allocation load and agreed within about 12%. It uses bounded
events on purpose: printing the sampled allocation event also serializes every stack
trace in the recording.

## Certification

`run_baseline.py` refuses to certify a run as a comparison reference when any of these
hold, and records the reason in `baseline.json`:

* the workload needs connected clients and none were asserted
* tick telemetry was unavailable in the recording
* the worktree was dirty, so the jar cannot be tied to a commit
* the server rejected a setup command
* the measurement window was shorter than 300 seconds

## Recording a result

Copy the `compare_baseline.py` report into the PR. It already carries the section 85
provenance table, the before/after/delta table, the certification state of both runs,
any provenance drift, and the gate verdict.

Also answer the section 84 questions in prose: what was slow, how it was measured, why
it was slow, what changed, what improved, and what the risks are.
