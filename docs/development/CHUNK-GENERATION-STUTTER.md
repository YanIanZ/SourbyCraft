# New-terrain generation stutter

Status 2026-10-06: user reports stutter while entering **new terrain**. Initially no trace,
hardware description or reproduction was supplied. A later user Spark capture is analyzed
in [AURORA-EDF-UPGRADE.md](AURORA-EDF-UPGRADE.md): it selects only the eight region
threads and records an AWF spawn-generation wait on global tick; generation workers are
absent. The bottleneck while exploring new terrain still requires a reproduction. Source inspection identifies
generation scheduling paths; it does not establish the stutter's cause.
Codex has not run tests, built a JAR, booted a server or measured performance.
Build/testing/profiling are assigned to Claude by the user.

Status update 2026-10-07 ([AGENT-COORDINATION.md](../../AGENT-COORDINATION.md), the tree and `build/`
outputs): patch 0027 is applied as materialized commit `0569a90` (on top of 0026 `952f1e1`; the patch file is
still untracked in the root repository) and was compiled into the demo jars (2026-10-07 00:00 and 00:36 at the
latest, because 0028 `ade3b24` was stacked on it; the 22:40 build on 2026-10-06 likely too, not inspected). The
metrics were never enabled on the demo, so `/perf chunks` stage samples do not exist. Nothing here is
measured by 0027. A TOML switch (`aurora.diagnostics.chunk-generation-metrics` in `sourbycraft_config/aurora.toml`)
is **in progress** by another agent (log, owner instruction 2026-10-07 ~02:00, agent "codex-items"); the working
tree already references the key in `SourbyCraftConfig`, `AuroraConfig` and `PerfCommand`, but no test or boot of it
is recorded. The "Enable" section below still describes only the JVM property, which is the 0027 contract until
that work lands and is tested. The handoff list below carries a per-item status.

## Written increment

Minecraft feature patch `0027-SourbyCraft-measure-generic-chunk-generation-stages.patch`
adds optional timing around `ChunkUpgradeGenericStatusTask`. It is exported from
materialized commit `0569a90` in `sourbycraft-server/src/minecraft/java`; generated
sources are not committed to the root repository. The owned recorder is
`perf/ChunkGenerationMetrics`; `/perf chunks` displays its queue/run p95 and p99.

Enable (either way; both are **RESTART_REQUIRED**, default false):

1. In `sourbycraft_config/aurora.toml` (works on hosted panels that cannot pass JVM flags):

   ```toml
   [aurora.diagnostics]
   chunk-generation-metrics = true
   ```

2. Or as a JVM property, before `-jar`:

   ```text
   -Dsourbycraft.chunk-generation-metrics.enabled=true
   ```

Timing is on when either is true. `ChunkGenerationMetrics.ENABLED` is read once at class
initialization, which the chunk system triggers before configuration loads, so the TOML key
is read directly from `aurora.toml` over `sourbycraft_global_config.toml` (the same early read
`AuroraBridge` uses); a missing, unparseable or non-boolean value means off. `/sourbycraft reload`
reports a change as restart-required, never as applied. Tests:
`ChunkGenerationMetricsEarlyConfigTest`. The current running server is not changed.
No extra executor, worker count, generation algorithm, scheduler priority, ticket,
region check, view distance or gameplay setting is changed.

Queue time starts just before scheduling and ends when the worker enters `run()`.
Run time ends when `run()` exits, including future waiting and completion callbacks;
it is wall time, not CPU time or end-to-end chunk latency. A callback can trigger
downstream work, so inclusive run samples must not be summed across stages.
Record only paths that actually invoke a non-empty generic generation step;
saved-chunk loading, empty shortcuts and tasks cancelled before execution do not record.
Executed runs that fail are included and are not separately classified as successful.

Samples are process-wide across worlds, with fixed vanilla stage keys and the last
1024 queue/run samples per key. The cumulative run count is separate from this recent
sample window. No world, chunk, task or plugin reference is retained. Individual ring
snapshots are synchronized; their pair and the cumulative count are not one atomic
snapshot. Recording uses short synchronized ring stores; its overhead is unmeasured.

Dedicated lighting and full integration tasks, packet sending, ticket/dependency wait
before scheduling, synchronous region-thread chunk waits, and currently unfinished
tasks are outside coverage. Zero samples means no covered run completed, not no
generation work or no stutter. These measurements do not qualify a release or prove
a bottleneck by themselves.

## Claude acceptance handoff — partly done (status per item, 2026-10-07)

1. Materialize feature patch 0027 alongside existing patches. Run
   `ChunkGenerationMetricsTest` and the relevant command/server suites, compile, and boot.
   Two regression methods are written but unexecuted: canonical registry names preserve
   separate queue/run samples; rings stay bounded and refuse arbitrary labels.
   Status 2026-10-07: materialize done (`0569a90`); compile done (the test class file is in
   `sourbycraft-server/build/classes/java/test`); boot done (demo jars, flag off). **Not done / not
   recorded:** no execution result for `ChunkGenerationMetricsTest` appears in the log or in
   `sourbycraft-server/build/test-results` (the result set there, XML timestamps 2026-10-06T18:26Z, is the bridge
   package only), and the log's full-suite figures are not attributed to it.
2. Verify default-off rendering and enabled rendering before any samples. Generate new
   vanilla terrain with a fixed seed and confirm covered stage counts and recent samples
   increase. Load already generated terrain separately; confirm loading paths add no
   generation samples. Exercise empty shortcuts, cancellation and failure paths.
   Status 2026-10-07: **not done.** The flag could not be set on the demo (panel passes no `-D`), and no local
   run with it is recorded. The t100 delivery runs below ran with metrics off and say nothing about the covered
   stages.
3. Inspect profiling evidence alongside `/perf chunks`, `/perf lanes`, `/perf cpu`,
   `/perf gc`, `/perf region` and `/perf storage`. Distinguish worker queue wait, generation
   CPU, future waits, completion callbacks, region integration, GC and chunk delivery.
   A large run sample alone cannot distinguish those costs.
   Status 2026-10-07: **partly done.** One wall-clock spark (workers=6, <https://spark.lucko.me/C2D7rXIJGY>,
   log entry 2026-10-06) attributes busy worker time to vanilla stages (below). `/perf chunks`, `/perf lanes`,
   `/perf cpu`, `/perf gc`, `/perf region` and `/perf storage` were not recorded alongside it, so queue wait,
   future waits, callbacks, integration, GC and delivery are not separated.
4. Reproduce using the user's generator/plugins and backend; record build/patch set,
   hardware and CPU quota, JVM/heap/GC, engine worker settings, view/simulation distance,
   player movement and plugin list. For every new-terrain comparison use equivalent fresh
   world state, seed and route; reusing a generated route changes the workload.
   Status 2026-10-07: **partly done, not for the user's setup.** A fresh-terrain bot run on Sourby Demo (8 cores,
   view distance 10, 1 player, 3 spots per side, uncertified) is recorded below. The user's generator, plugins,
   backend, JVM/heap/GC and CPU quota were not reproduced. (The user's own spark RyP3FlAYME reports 8 available
   processors on an Intel Xeon E3-1245 v5; the log does not say the demo host is the same machine.)
5. Select an optimization only after the trace identifies its runtime path. Keep total
   CPU capacity shared with region, network and storage work. Compare the same statistic
   and workload with certification/noise state recorded and the same diagnostic flag on
   both sides, including mean tick and tail
   tick latency; accept only after ownership, save/restart and shutdown checks.
   Status 2026-10-07: **not done.** No optimization was selected. The only change is the demo's
   `chunk-system.worker-threads: 6` in `config/paper-global.yml` (operator decision with the user's approval,
   RESTART_REQUIRED, log 2026-10-06); that is a setting choice on one workload, not a code change, and no
   same-flag comparison with tick-tail latency exists.

The historical [worker A/B](../architecture/chunk-workers.md) showed a mean/tail tradeoff.
It is not evidence that increasing generation workers fixes this user's server.
No throughput improvement or stutter resolution is claimed for this increment.

## Measured on Sourby Demo (Claude, 2026-10-06)

Source: [AGENT-COORDINATION.md](../../AGENT-COORDINATION.md), *new-terrain chunk delivery on
Sourby Demo*. **Uncertified**: 3 spots per side at different fresh coordinates, noise not
measured. This is an observation that guides investigation, not a reference result.

- Workload: one real-protocol client (`scripts/baseline_client.py`), view distance 10
  (361 chunks, Chebyshev ≤ 9), teleported to fresh terrain per spot; 1 player; 8-core host;
  demo build 22:40. Metric: time until the full view was delivered (t100).
- workers=2 (Paper's default from 8 cores): 22.7 / 17.8 / 18.2 s. Worker threads at
  1.92–2.00 cores during generation, ~5.9 cores idle.
- workers=6: 17.0 / 11.2 / 15.6 s; the profiled run 12.4 / 11.5 s. Worker threads at
  4.5–5.5 cores.
- spark <https://spark.lucko.me/C2D7rXIJGY> (wall clock, workers=6): busy worker time is
  vanilla world generation — noise 23.2%, biomes 8.5%, surface 8.5%, features 5.1%,
  structure_starts 2.3%, carvers 1.5%, light 0.7%; ~45% parked (idle between teleports).
  No AWF or lock cost visible.

What this does and does not show. On this single-player workload two workers left most
cores idle while the player waited 18–23 s, and six workers shortened that wait. It measures
delivery time, not tick time or client frame stutter, so it does not establish the cause of
the reported stutter, does not contradict the swarm MSPT results in
[chunk-workers.md](../architecture/chunk-workers.md) (*Three workloads, three answers*), and
does not resolve the user's report. No stutter resolution is claimed.

- The demo now runs `chunk-system.worker-threads: 6` in `config/paper-global.yml` by operator
  decision (user approval, RESTART_REQUIRED, applied with a restart). The code default is
  unchanged.
- The panel cannot pass `-D` JVM flags (its startup variables are version/jar/build only), so
  `-Dsourbycraft.chunk-generation-metrics.enabled=true` from patch 0027 cannot be enabled
  there. Since 2026-10-07 the same switch is the TOML key
  `aurora.diagnostics.chunk-generation-metrics` (see *Enable* above), which the panel can set;
  no build carrying it has run on the demo yet, so handoff step 3 is still open for that server.
- **Open observation, not a conclusion:** Paper/Moonrise sent no `chunk_batch_finished` to the
  test client during these runs (batches = 0). The client's optional `ack_chunk_batches` is off
  by default so swarm workloads are unchanged. Why no batch boundary was observed has not been
  investigated.

## Other source/document drift found in this continuation

Paper is the only upstream (`paperRef`); private SourbyPatcher uses `paper-toolchain`.
AWF has a Redis backend, and clean resident chunks can be evicted while dirty chunks
remain pinned. The Aurora task matrix and transition plan now distinguish these current
facts from planned independent scheduling and historical test evidence.

Metadata persistence remains P0: `PaperLevelOverrides` contains active game time/spawn
but is missing from the safe-world autosave whitelist. The old `level.dat`-only explanation
was corrected; the crash harness keeps its existing XFAIL until Claude verifies a fix.
No metadata save-path change was made in this increment.

Claude also reported the existing Bukkit API annotation check failing on `RegionTask`.
It now declares `@NullMarked`, matching the API's JSpecify convention and the current
`AnnotationTest` handling. That rerun is done: `org.bukkit.AnnotationTest` 1 test, 0 failures, 0 errors (`sourbyapi/build/test-results`,
XML timestamp 2026-10-06T18:27Z). It is unrelated to generation timing.
