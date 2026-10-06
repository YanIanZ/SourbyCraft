# Why compatibility and performance have not met

Written 2026-10-07 from the documents this project has produced, the git history (1296
commits on `26.2`; 356 whose message is about performance or benchmarks, 142 about
compatibility, the bridge or legacy plugins) and the measurements taken on Sourby Demo on
2026-10-06. Every claim below names its source. Nothing here is a new measurement; the
Sourby Demo numbers are **uncertified** and are quoted as observations, not references.

The short answer: the two programs have never shared an experiment, they have no metric in
common, and the architecture places compatibility work on the one thread that region
threading cannot parallelise. The project's own safety order then ranks them rather than
balancing them. None of this is a defect in either program; it is a gap between them.

## 1. No shared workload, no shared metric

| Program | What it runs | What it measures |
|---|---|---|
| Performance ([BASELINE.md](../BASELINE.md), `scripts/baseline_workloads.py`) | `idle`, `players-N`, `entity-stress`, `chunk-stress`, `network-stress` — **no plugins** | TPS, MSPT percentiles, CPU, GC, allocation, lane CPU |
| Compatibility ([aurora-plugin-bridge.md](aurora-plugin-bridge.md), [testing-legacy-plugins.md](../guides/testing-legacy-plugins.md)) | real plugins through the bridge on the demo panel | scheduler redirects, owner handoffs, rejected operations, fatal violations, quarantine — **counts, no time** |

- [qualification-readiness.md](qualification-readiness.md) §2 lists the plugin-heavy
  representative workload as missing: *"no representative plugin set defined"*.
- No metric attributes tick time to a plugin. [Build 47](../releases/26.2-build-47-aurora-nexus.md)
  records *"No per-plugin onLoad/onEnable timing"*; DEVELOPMENT.md §19 "Plugin Cost Visibility"
  describes the intent, and nothing implements it. The bridge records how many tasks it routed
  (Essentials 5 redirects, Vault 2 on `9a15f51`) and never how long they ran.
- The one run with legacy plugins present and MSPT recorded is the demo `players-10` bench
  (TODO P1: median 5.1 ms, p99 10.6 ms), uncertified, and it does not separate plugin cost from
  the rest.

Without a join key the two sets of documents cannot contradict or confirm each other. They
describe different servers.

## 2. Compatibility work lands on the serial thread

Region threading parallelises **across space**. [region-parallelism.md](region-parallelism.md)
§2: 1717 force-loaded chunks in connected bands made 3 regions; 8 sites 6000 blocks apart made 8.
One region ticks on exactly one thread.

The bridge routes a legacy sync task to the caller's region when one exists, and otherwise — from
`onEnable`, the console, the global region or async code — to the **global region**
([aurora-plugin-bridge.md](aurora-plugin-bridge.md), *Sync route*). Plugins written for one main
thread schedule most of their startup, economy and console work from exactly those contexts.
So the default effect of admitting a legacy plugin is more work on the single thread no thread
count can help, which is why SPEC §155/§156 state for the bridge increments: *"Compatibility/
lifecycle work, no throughput claim."*

The same shape appears outside the bridge:

- `/awf create` waits for `setInitialSpawn` → `syncLoadNonFull` on the global tick (TODO P0;
  Spark `RyP3FlAYME`).
- SuperiorSkyblock2's `SpawnIsland` read the spawn biome from a global-tick task and was counted
  as a bridge violation on every boot ([SUPERIORSKYBLOCK-BRIDGE.md](../development/SUPERIORSKYBLOCK-BRIDGE.md)).

## 3. The bridge routes; it cannot make a plugin region-aware

Two fixes for the SpawnIsland case were produced on 2026-10-06:

- a hash-pinned adapter for one class of one plugin version (`bridge/SuperiorSpawnTaskOwner`,
  patch 0010), and
- a port of the plugin itself to native Folia scheduling (`~/Sourby/ssb/SSB2`, branch `folia`,
  69 files; `FOLIA.md` there).

After the port, the plugin no longer passes through the bridge and the violation is gone. That is
the general outcome: **full compatibility was reached by leaving the compatibility layer.** The
bridge is a safety and diagnosis mechanism — it admits, routes, counts violations and quarantines
— and neither it nor any adapter can supply the region knowledge the plugin's author did not
write. The bridge documents do not yet say this as a design limit; §6 below asks for it.

## 4. The evidence standard retracts more than it admits

- The >3% regression gate is **blocked**: no certified comparable reference pair exists.
  Certification requires foreign CPU below 10% for the whole window; four attempts on the
  development desktop measured 21–23%. The demo panel cannot certify because it offers no shell
  for JFR ([qualification-readiness.md](qualification-readiness.md) §6–7).
- [Release 46](../releases/46.md) withdrew two published results: every baseline had measured
  world generation, and the chunk-worker A/B reversed when entities were present. The reuse
  patches 0009/0013/0014/0015 were removed because *"no benchmark ever justified the reuse"*
  ([reuse-audit.md](reuse-audit.md), [threading.md](threading.md)).
- The chunk-worker question now has three documented answers for three workloads:

  | Source | Workload | Finding for 6 workers vs 2 |
  |---|---|---|
  | [chunk-workers.md](chunk-workers.md) | 10 bots, forceloaded sites, entities silently absent | median +22%, p99 −39% |
  | [BASELINE.md](../BASELINE.md) *re-measured* | 10 bots + 300 entities, seeded world | worse at every percentile; *"leave the worker count alone"* |
  | Sourby Demo 2026-10-06 (uncertified, 1 real-protocol client, view 10, fresh terrain, 8 cores) | one player entering new terrain | full view delivered in 11–17 s instead of 18–23 s; workers 4.5–5.5 cores instead of 2.0 with ~6 idle |

  None is wrong. They measure different products. The project has not decided which workload
  represents its players, so the documents cannot converge on a recommendation, and the
  "no thread-count folklore" rule (DEVELOPMENT.md §6) was read as a prohibition where the
  single-player case needed the change.

## 5. The safety order ranks them

Build 47: *Correctness > reliability > data safety > stability > **compatibility** > tail
latency > resource efficiency > startup performance > scalability > throughput.* By
declaration, compatibility wins over every performance goal and loses to correctness. The
[Resource Governor](aurora-resource-governor.md) bounds only SourbyCraft's own lanes and
explicitly does not govern region tick threads, chunk workers, Netty or Folia's schedulers, and
performs no CPU arbitration. There is no mechanism whose job is to trade one against the other.

Contributing: the independence and toolchain programs consume the effort that would produce
certified evidence (upstream rebases re-apply 23+ Minecraft feature patches; a patch rebuild on
2026-10-06 deleted four untracked patches, see `AGENT-COORDINATION.md`). And the lane
measurements show the machine mostly idle (1.4–1.7 of 8 cores) while users report slowness:
both are true, because the limit is one thread or one pool, which averages hide.

## 6. What would make them meet

1. **A representative workload with plugins** in the same harness as `players-10`: EssentialsX
   and Vault (bridged), SuperiorSkyblock2 (native port), spark. Defined in
   `scripts/baseline_workloads.py` with its fidelity statement, runnable by `run_baseline.py`.
2. **A joining metric**: execution time of bridged tasks per plugin (count, total, max, recent
   window) on the region and global threads, shown by `/plugins <name>` and `/perf plugins`
   next to the existing counts. LIVE, no configuration.
3. **An owner decision on the product workload** — "one player exploring new terrain" versus
   "ten settled players" — recorded in TODO.md like the licence decision, because the worker
   recommendation and several patches depend on it.
4. **The bridge's limit stated** in its documents: it is a safety and diagnosis layer; full
   compatibility means a native port.

Items 1, 2 and 4 are work; item 3 is a decision.
