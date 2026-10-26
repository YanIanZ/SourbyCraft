# Aurora Architecture

## SourbyCraft 26.2 Deep Engine Architecture

**Codename:** Aurora  
**Branch:** `26.2`  
**Java:** 25  
**Status:** Active architecture direction  
**Primary objective:** Make SourbyCraft independently own its runtime behavior, configuration, performance model, and Minecraft engine optimizations while preserving required upstream compatibility and attribution.

---

> **This is the entry point for every Aurora document.** The four sections below (document map,
> verified current state, open contradictions, deepening candidates) were added on 2026-10-07 and
> re-checked against the PR #62 tree (`build50/pm-completion`) on 2026-10-25. Sections 1–23 further
> down are the architecture principles; §2–§8 were corrected on 2026-10-25 where they still
> described Canvas as an upstream, a TOML configuration split or adapter classes that do not
> exist. Where any Aurora document disagrees with the code, the code wins (docs/DEVELOPMENT.md,
> truthfulness policy §1) and the disagreement is listed under *Open contradictions* until its
> source is corrected.
>
> PR #62 (Build 50 PM completion) is **not in any published build**: the `v26.2-r50` prerelease tag
> points at `5f5a167`, before PR #61 and PR #62, and how this work is published (r50.1 or Build 51)
> is owner decision PM-011. Rows below that cite PR #62 describe code written and statically
> reviewed; not built; not tested by the owner. Per-item status:
> [BUILD-50-PM-MATRIX.md](../releases/BUILD-50-PM-MATRIX.md).

## Document map

Roles: **Active** — describes the current branch and is kept in sync with code. **Plan** — states
intended direction; its checkboxes and "status" paragraphs are snapshots, not the current state.
**Proposal** — design argument; not a statement of what exists. **Historical** — a dated record;
read for evidence and reasoning, not for current numbers.

| Document | Role | Status | Read it for | Overlaps / superseded parts |
|---|---|---|---|---|
| `docs/architecture/AURORA.md` (this file) | Entry point; architecture principles (§1–23) | Active | Where to start; what exists now; the rules every Aurora change follows | §2–§8 corrected 2026-10-25 for the Paper-only upstream, the YAML `aurora.yml` and the real adapter names |
| `docs/AURORA-CONFIG.md` | Effective configuration contract | Active | Every implemented `aurora.*` key: type, default, LIVE/RESTART_REQUIRED, consumer; which `aurora.yml` keys are read before the configuration loads | Checked against `config/AuroraConfig.java`, `config/AuroraKeys.java` and `awf/AwfSettings.java` |
| `docs/AURORA-UX.md` | Operator console and command surface | Active | Startup console, stage lines, `/aurora`, `/perf` presentation | Stage count and order corrected 2026-10-25 (19 stages, `TOTAL_STAGES`) |
| `docs/AURORA-TASKS.md` | Task checklist grouped by Aurora area | **Not in the repository**: removed from git in `c86b3be` (2026-10-22) and kept outside it as an internal planning document (`docs/README.md`) | — | Priorities and open items live in `docs/TODO.md`; the contradictions that cite it can no longer be checked in-repo |
| `docs/architecture/execution-contract.md` | Execution contract and measured coupling inventory | Active | `OwnerHandoff`/`Admission`, region system, which files name the region backend, async-path invalidation trace | Adapter-file count corrected 2026-10-07; inventory (seven pinned files), generation check and stage statuses corrected 2026-10-25 (contradiction 28); supersedes the status notes in AURORA-INDEPENDENT-ENGINE.md §38 Phase 2 |
| `docs/architecture/aurora-resource-governor.md` | Resource Governor specification | Active | The three governed lanes, budgets, rejection policy, the thread ledger it reports, what is out of scope | Configurable budgets and STORAGE use corrected 2026-10-07; lane users, ledger rows and the executors outside the governor 2026-10-25 |
| `docs/architecture/aurora-instant-startup.md` | Instant Startup cache specification and status | Active | What is cached, never cached, fingerprints, what is not implemented | — |
| `docs/architecture/aurora-engine-package.md` | Source-tree placement | Active | `dev.iyanz.aurora.*` versus `dev.iyanz.sourbycraft.*` | — |
| `docs/architecture/engine-entity-ai.md` | T5 ownership: Entity and AI | Active | What Aurora owns and does not own in Entity/AI; the client-gap measurement constraint | Async-path overload metric corrected 2026-10-07, and again 2026-10-25 for PR #62's `declined` counter (contradiction 6) |
| `docs/architecture/engine-chunk-world.md` | T5 ownership: Chunk and World | Active | Chunk/world ownership; why `chunk-stress` certifies without clients | Patch and metric tables refreshed 2026-10-25 |
| `docs/architecture/engine-network.md` | T5 ownership: Network | Active | Network counters and the unowned network policy | Patch-count sentence corrected 2026-10-07; patch list and queue-health measurement corrected 2026-10-25 (contradiction 27) |
| `docs/architecture/engine-storage.md` | T5 ownership: Storage | Active | Durability rule; what Aurora does not own in persistence | AWF wiring sentence corrected 2026-10-07; summary and §1/§3/§5/§7 aligned with the feature patches 2026-10-25; AWF detail is in `aurora-world-fabric.md` |
| `docs/architecture/independence.md` | Independence levels, rules, build work items | Active (levels and rules); §2 ledger qualitative | Independence levels 0–5, B1–B5 build items | Counted coupling corrected 2026-10-07, re-counted 2026-10-25 (§2.1, §2.2); the authoritative ledger is `dependency-ledger.md` |
| `docs/AURORA-FULL-TRANSITION.md` | Transition plan, gates T0–T11 | **Not in the repository**: removed from git in `c86b3be` (2026-10-22), kept outside it as an internal planning document (`docs/README.md`) | The T-gate numbers other documents still cite (T5, T7, T10) | In-repo, T10 and its evidence vocabulary are defined by [qualification-readiness.md](qualification-readiness.md); T5 by the `engine-*.md` ownership documents; T7 telemetry items by qualification-readiness §3 |
| `docs/AURORA-ROADMAP.md` | Milestone roadmap M0–M28 | Plan | Milestone gates and dependency graph | §3 "Current Baseline" replaced by a pointer to this file (2026-10-25); milestone status lines corrected against code; its tracks are not scheduled 26.2 builds |
| `docs/architecture/AURORA-ENGINE-SYSTEM.md` | Engine design guide | Plan | Path A/B/C (direct NMS patch / Aurora service / adapter), patch innovation pipeline, per-domain metric lists | §4, §23, §27 and §32 (the `AuroraScheduler` wrapper, never built) superseded by `execution-contract.md`; banner names the real classes (2026-10-25) |
| `docs/architecture/AURORA-INDEPENDENT-ENGINE.md` | Independence design argument | Proposal (design proposal; most of it is not built) | Execution domains, hybrid scheduling, snapshot work, stale-result protection, replacement policy (§39) | §6, §10, §24, §31, §36–§38 carry dated *Status 2026-10-25* notes (stage statuses, seven pinned files, Canvas no longer an upstream, Phase 0 overstated); the original text under them is kept; current statements are in `execution-contract.md`, `dependency-ledger.md` and this file |
| `docs/AURORA-DEVELOPMENT.md` | The 26.2 continuation brief that started the Aurora phase | Historical | Original intent and track list | Its "immediate next tasks" (config model, key migration, typed snapshots) are done; work tracking moved to `docs/TODO.md`; §3.1/§4 annotated 2026-10-25 (namespaces live in `aurora.yml`; §4 candidate keys not implemented) |
| `docs/architecture/aurora-task-d-validation.md` | Entity/AI Task D validation record, 2026-09-21 | Historical | Why tracker candidate 0018 was withdrawn; collision arithmetic coverage | Patch numbers shifted after renumbering (note added); its reproduce commands are the 2026-09-21 ones (note added 2026-10-25) |
| `docs/architecture/executor-inventory.md` | Executor, thread and shutdown-hook inventory | Active | Every SourbyCraft-owned executor with bound, name and shutdown owner; common-pool audit; pointer to the region-I/O audit | Re-measured 2026-10-25; `executors.md` is the earlier, historical audit |

Related documents owned elsewhere: `aurora-plugin-bridge.md` (Bridge), `aurora-world-fabric.md`
(AWF), `chunk-workers.md`, `dependency-ledger.md` (the enforced coupling ledger),
`region-io-audit.md` (blocking I/O and region threads), `qualification-readiness.md` (T10 gates
and the evidence vocabulary), `compat-vs-performance.md`, `docs/development/AURORA-EDF-UPGRADE.md`,
`docs/BASELINE.md`, and the generated per-item status of PR #62,
[BUILD-50-PM-MATRIX.md](../releases/BUILD-50-PM-MATRIX.md).

**Seven stage schemes.** The documents above number the same journey seven ways: AURORA.md A1–A7,
AURORA-ROADMAP M0–M28, AURORA-FULL-TRANSITION T0–T11 (now outside the repository),
AURORA-INDEPENDENT-ENGINE Stages 1–5 and Phases 0–8, AURORA-ENGINE-SYSTEM scheduler Steps 1–4,
AURORA-DEVELOPMENT Phases Aurora-1–8, and independence.md Levels 0–5. None is a schedule. When a
gate has to be named, use the T-numbers: they are the ones `qualification-readiness.md` and the
`engine-*.md` documents cite. Their in-repo definitions are qualification-readiness.md (T10, with
its §3 metrics for T7) and the `engine-*.md` documents (T5); the full T0–T11 list is in the
transition plan kept outside the repository.

## Current state (verified 2026-10-07; re-checked 2026-10-25)

> Ruling 2026-10-07 (owner): defaults derived once at boot from the processor count — region tick
> threads, `BRIDGE_IO`, the async-path pool — are **not** auto-tuning under docs/DEVELOPMENT.md §2.2;
> only values that change under load are. Rows below that mention `processors/N` are therefore
> compliant as stated.


First verified by reading the code in the 2026-10-07 working tree of branch `26.2`; the
execution-contract, Resource Governor and upstream-coupling rows were rechecked against
`ce23739e` on 2026-10-21. On 2026-10-25 every row was re-read against the PR #62 tree
(`build50/pm-completion`, draft PR #62 to `26.2`), and rows were added for the Build 48–50
subsystems and owner decisions. No build, test or server was run for this table. Where a row says
"PR #62", that part is code written and statically reviewed; not built; not tested by the owner,
and it is in no published build (`v26.2-r50` is `5f5a167`, before PR #61 and #62; route: owner
decision PM-011). Status words follow `qualification-readiness.md` §8 and README's vocabulary:
IMPLEMENTED, MEASURED, CERTIFIED RUN, QUALIFIED, PARTIAL, EXPERIMENTAL, PLANNED (plus HELD for
work parked by the owner). Paths are relative to
`sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/` unless they start with
`sourbycraft-server/`, `aurora/` (`dev.iyanz.aurora`) or `docs/`.

| Pillar | Status | What the code has | Evidence (file) |
|---|---|---|---|
| Region execution — scheduler | IMPLEMENTED (inherited) | The Folia-lineage region scheduler, SourbyCraft-owned since 2026-10-05; default scheduler type `EDF` (LeafPile's `EDFSchedulerThreadPool`) | `sourbycraft-server/paper-patches/files/src/main/java/io/papermc/paper/configuration/GlobalConfiguration.java.patch` (`SchedulerType.EDF`) |
| Region execution — tick-thread default | IMPLEMENTED; MEASURED (one uncertified 8-core block-tick A/B, not a certified pair) | AUTO = every JVM-visible processor; `aurora.cpu.cores` caps it (RESTART_REQUIRED, read from `aurora.yml` over the global file before the bootstrap runs); explicit `threaded-regions.threads` wins | `sourbycraft-server/src/main/java/dev/iyanz/aurora/cpu/AuroraCpu.java` (`regionTickThreads`), applied only by feature patch 0002 (the thread ledger and the Spark metadata read it for display); `docs/architecture/region-parallelism.md` |
| Region execution — Aurora EDF backend | HELD — parked by owner decision 2026-10-07, not built | The parked EDF patch would make the default `EDF` type construct the LeafPile-derived `AuroraEdfScheduler` (in the main tree, unselected). Build, tests, boot and profiling are pending, and `test_independence_policy.py` failed on it (2026-10-07). It is not the current feature patch 0028 | `sourbycraft-server/minecraft-patches/parked/0028-…patch` and `parked/README.md`, `docs/development/AURORA-EDF-UPGRADE.md` |
| Execution lanes (CPU attribution) | IMPLEMENTED; MEASURED (uncertified lane observations on the demo server) | `ExecutionLane` maps thread names to 11 lanes (`Worker-Main` has its own `BACKGROUND` lane); `/perf lanes` | `execution/ExecutionLane.java`, `execution/LaneCpuSampler.java`, tests `ExecutionLaneTest`, `LaneCpuSamplerTest`; `compat-vs-performance.md` §5 |
| Execution contracts | PARTIAL | `OwnerHandoff`/`Admission` used by async-path completion and the wandering-trader coordinator; the `execution/region` contract (`RegionOwnership`, `RegionBackend`, `RegionChunkLoading`) used by owned teleports, command blocks, data commands and the guards; other owned services use the published `threadedregions.scheduler` API directly (HUD, replies, updater, bridge), which the policy does not count as coupling. Scheduler and engine coupling is tracked by the inventories in `scripts/independence_policy.py`, pinned in `scripts/test_independence_policy.py` | `execution/OwnerHandoff.java`, `execution/region/`, `scripts/test_independence_policy.py`, `execution-contract.md` |
| Resource Governor | PARTIAL | Three governed lanes: `BRIDGE_IO` (max(2, processors/4) threads, queue 256), `STORAGE` (1 thread, queue 64; AWF commits and AWF MultiProfile steps, the latter one at a time through `ProfileIoQueue` since PR #62), and `PATHFINDING` (max(1, processors/4) threads, queue 1024, used by `AsyncPathProcessor`); Bridge I/O and Storage budgets from `aurora.scheduler.*` (RESTART_REQUIRED; a reload's change shows as pending), Pathfinding sizing fixed at lane creation; reject, never caller-runs. `/perf governor` also prints the thread ledger: configured sizes of the upstream pools, never resized, not measured. Not governed: region ticks, chunk workers, Netty, and SourbyCraft's other bounded executors (`executor-inventory.md` §1). `execution/FairLane` exists and is not wired. No CPU arbitration | `execution/ResourceGovernor.java`, `execution/ThreadLedger.java`, `config/AuroraConfig.java` (`Scheduler`), `aurora-resource-governor.md` |
| Aurora Compatibility Bridge | IMPLEMENTED, default off; not QUALIFIED | `aurora.bridge.mode` default `OFF` (RESTART_REQUIRED); routing, violation counting, quarantine; entity-owner route (`EntityTask`, Paper 0011); per-plugin bridged body timing (`BridgeTelemetry`); same-instance re-enable (Paper 0012). In the published Builds 47–49 (their CI build, tests and boot test). PR #62 bounds the guard log windows, coalesces overlapping repeating async runs and releases dropped work | `config/AuroraConfig.java` (`Bridge.DEFAULT`), `bridge/AuroraBridge.java`, `bridge/BridgeTelemetry.java`, `aurora-plugin-bridge.md` |
| Bridge thread checks (owner exception to region ownership) | IMPLEMENTED; **unsafe and unqualified by owner decision** | `aurora.bridge.thread-checks` default `relaxed` (owner decision 2026-10-22, "Tiru penuh LightingLuminol"; RESTART_REQUIRED; `-Daurora.bridge.thread-checks` overrides): failed region-ownership checks are counted and logged instead of thrown. Takes effect only while `aurora.bridge.mode = safe`; with the default `off` the checks stay enforced. `bridged` and `enforced` are the narrower values. The relaxed path ports LightingLuminol's cross-region world-data pool (`compat/luminol`) and MC feature patches 0084/0085 | `config/AuroraConfig.java` (`ThreadChecks`, `Bridge.DEFAULT`), `guards/ThreadChecks.java`, `aurora-plugin-bridge.md` "Thread checks" |
| Aurora World Fabric | EXPERIMENTAL, default off | No world uses AWF unless listed in `aurora.awf.worlds` (default empty) or managed through `/awf`; FILE and Redis backends; wired under `RegionFileStorage` (feature patch 0021) and committed at shutdown (0019, 0071). Crash test 53/53 is MEASURED. PR #62 hardens eviction, unload/save durability, the registry, the Redis client and Redis commits (WAITAOF) | `awf/AwfSettings.java` (`DEFAULT`), `awf/redis/RedisBackend.java`, `awf/AwfEngine.java`, `qualification-readiness.md` §4, `aurora-world-fabric.md` |
| AWF MultiProfile | IMPLEMENTED, default off; not qualified. Shared Redis repository EXPERIMENTAL | `aurora.awf.multiprofile.enabled` default `false` (RESTART_REQUIRED). Standalone with the file repository is the default shape. A distributed shape is allowed only with `aurora.awf.multiprofile.repository: redis` (fenced Lua commits in `RedisProfileRepository`, PR #62) and is EXPERIMENTAL and unqualified: a player's live state does not follow them between servers, and proxy/portal handoff and quit snapshots at shutdown (patch 0081 ordering) are open | `awf/multiprofile/ProfileRuntime.java`, `awf/multiprofile/ProfileSettings.java` (`DEFAULT`), `awf/multiprofile/RedisProfileRepository.java`, `docs/awf/multiprofile/ARCHITECTURE.md` |
| Aurora Redis Fabric | IMPLEMENTED, default off; not qualified | `aurora.awf.redis.fabric.enabled` default `false` (RESTART_REQUIRED): sessions, coordination, events, cache, routing and node identity over bounded per-workload Redis clients; boot stage "aurora redis fabric"; does not start on a misconfigured or unreachable Redis (logged; the stage still returns) | `awf/redis/fabric/FabricRuntime.java`, `awf/redis/fabric/FabricSettings.java` (`DEFAULT`), `docs/awf/multiprofile/REDIS-FABRIC.md` |
| SourbyMod (ML1) | IMPLEMENTED; not qualified | Native `mods/*.jar` loader with a `sourbymod.yml` descriptor; boot stage "sourby mods" after configuration, `onEnable` at `ServerLoadEvent(STARTUP)`, `onUnload` at shutdown for every loaded mod (PR #62); jars load in file-name order (PR #62). Unit tests `ModLoaderTest`, `ModDescriptorTest` | `mod/ModLoader.java`, `mod/SourbyMod.java`, `docs/SOURBYMODS.md` |
| Network security guards | IMPLEMENTED, every group default `LOG`; not QUALIFIED | Packet/connection limits in `sourbycraft-security.yml` (LIVE on `/sourbycraft reload`; proxy detection RESTART_REQUIRED) through MC feature patches 0034 (guard hooks), 0036 (connection rate limit), 0037 (no DNS lookup for handshake addresses), 0040 (consume rejected frames) and 0041 (proxy-aware admission keys). `LOG` counts and logs; `KICK` is opt-in | `security/SecurityGuards.java`, `docs/development/SECURITY-GUARDS.md` |
| Instant Startup cache | PARTIAL | Descriptor and compatibility scan cached with integrity checks; the dependency graph is recomputed every boot from the cached descriptors; transform outputs (none exist) and AWF indexes not cached; `-Daurora.startup.cache=false` disables the file. No cold/warm measurement, so no startup-time claim | `startup/StartupCache.java`, `startup/StartupIndexStage.java` (`DependencyGraph.analyse`), `aurora-instant-startup.md` |
| Boot stages | IMPLEMENTED | 19 stages (`TOTAL_STAGES`) in a fixed order; a failed stage is logged and stepped past; RUNNING when all succeed, DEGRADED when some fail, FAILED only when all fail | `core/SourbyCraftBootstrap.java`, `docs/AURORA-UX.md` "Sections, launch to ready" |
| Configuration surface | IMPLEMENTED | `sourbycraft_config/sourbycraft_global_config.yml` and `sourbycraft_config/aurora.yml` (YAML since Build 49; one-time boot conversion and layout migration, copies kept as `*.migrated`). PR #62: pre-Build-49 utility names, `perf.ai.async-pathfinding`, `-DCanvas.*` flags and `canvas.broadcasting.receiver` are no longer read (one WARN each; the one-time boot rewrite stays); persisted readers (`Canvas.Distance`, `data/canvas/pearls.dat`, the `canvas-*.yml` migration) are kept pending owner decision PM-179. `/sourbycraft reload` runs off the tick thread on the I/O executor and is refused while stopping (PR #62); a reload never rewrites a SourbyCraft file and rewrites an engine file only to add or remove a key | `SourbyCraftConfig.java`, `config/ConfigMigration.java`, `aurora/engine/util/LegacyNames.java`, `command/SourbyCraftCommand.java` (`reload`), `docs/AURORA-CONFIG.md` |
| Telemetry / perf | IMPLEMENTED; one CERTIFIED RUN (2 h, 10 players, soak); regression reference pair absent; not QUALIFIED | `MetricsRuntime`, immutable snapshots, 19 listed `/perf` views (plus the bare overview) including `network` (counters with the LIVE switch `aurora.network.counters`, and a point sample of outbound queue health), `governor`, `storage`, `awf`, `incidents`, registered in `command/PerfViews.java`; per-packet cost of the network counters unmeasured | `perf/MetricsRuntime.java`, `command/PerfCommand.java`, `command/PerfViews.java`, `perf/ConnectionQueueHealth.java`, `perf/StorageBacklog.java`, `docs/BASELINE.md` ("Second soak"), `qualification-readiness.md` §1 |
| Async pathfinding | EXPERIMENTAL, default off | `aurora.entity.async-pathfinding` default `false` (LIVE); `PATHFINDING` lane, max(1, processors/4) threads, queue 1024. Saturation contract: the navigation asks for capacity before it builds a snapshot; a full or stopping pool declines (`/perf async` declined), and a submission the lane still rejects is refused (`AbortPolicy`, `/perf async` refused). Either way that slot runs the vanilla synchronous recompute on the mob's owning region thread, the same cost as the flag off; the pool never runs a refused solve on the submitting thread (an inline solve happens only before the pool has started). PR #62: the offload declines on any thread that does not own the mob; `/perf async` counts declined, applied, discarded (entity / path / generation / no path) and retired results; and the `/perf incidents` signal (label "async path pool refused or declined work", unit refused/declined) fires on growth of refused plus declined, so real saturation now raises it. No certified client-attached measurement | `config/AuroraConfig.java` (`DEFAULT`), `perf/AsyncPathRecompute.java`, `perf/AsyncPathProcessor.java`, `command/PerfAsyncView.java` |
| Independence — upstream | IMPLEMENTED | Paper is the only upstream, pinned by `paperRef`; engine coupling (including `config/upstream/CanvasConfigBridge`, `awf/world/AuroraWorldsService` and `perf/LiveSchedulerSource`) recorded by `scripts/independence_policy.py` and pinned in `scripts/test_independence_policy.py`; use that inventory for current sites | `gradle.properties`, `scripts/test_independence_policy.py` |
| Independence — scheduler stages (AURORA-INDEPENDENT-ENGINE §10) | Stage 1 IMPLEMENTED; Stage 2 PARTIAL; Stage 3 IMPLEMENTED for SourbyCraft's own sources (pinned); Stage 4 PARTIAL; Stage 5 PLANNED | Stage 1 (contract extraction): `execution-contract.md`, `dependency-ledger.md` and the pinned inventories. Stage 2 (Aurora-owned dispatch): see "Execution contracts" above; only part of the owned work goes through Aurora contracts. Stage 3 (backend isolation): internal region-scheduler symbols appear in seven pinned files of SourbyCraft code: four adapters (`execution/RegionOwnerHandoff`, `execution/region/FoliaRegionBackend`, `awf/world/AuroraWorldsService`, and `compat/luminol/RegionizedWorldDataPool` with its `WorldDataAccess` helper, relaxed checks only) plus two read-only views (`perf/LiveSchedulerSource`, `perf/RegionLocator`); feature patches reach the scheduler only at recorded sites. Stage 4 (specialized execution): the async-path lane and the governed lanes exist, default off or idle, and no benchmark shows a benefit yet. Stage 5 (scheduler replacement): not started; the parked EDF backend would swap the pool inside the same region model and is not an independent scheduler | `execution-contract.md`, `scripts/test_independence_policy.py` (`SchedulerCouplingTest`), `AURORA-INDEPENDENT-ENGINE.md` §10 |
| T10 qualification | not QUALIFIED | Gate items partial or blocked: no certified comparable reference pair; one required workload missing (AI stress; 10 of 11 defined). No region-ownership bug open in source (the one recorded was fixed 2026-10-13 by MC feature patch 0028); the release gate stays open on watchdog acceptance and a soak without ownership violations | `qualification-readiness.md` §1–§6 |

## Open contradictions

One line each: what disagrees, and with what. "Fixed" means the code settled it and the stale
sentence was corrected or annotated (2026-10-07, or the date given); "Out of repo" means the stale
side is a planning document that left git in `c86b3be` (2026-10-22, kept outside the repository,
`docs/README.md`), so it can no longer be corrected or checked here and the code alone decides;
everything else is open. Re-checked against the PR #62 tree on 2026-10-25.

1. README.md (Aurora World Fabric row) said "no database backends"; `awf/redis/RedisBackend.java` and `aurora-world-fabric.md` ("Implemented backends: FILE and Redis") say otherwise. — **Fixed**: code decides (`awf/redis/RedisBackend.java`, commit `1fe984ed`); the README rewrite in `c86b3be` (2026-10-22) no longer carries the sentence.
2. README.md ("region backend confined to two adapter files") versus `scripts/test_independence_policy.py`. — **Fixed** in README (rewritten in `c86b3be`, no adapter count). The pinned set has since grown to seven files (`test_the_backend_is_named_only_inside_the_adapters`: four adapters plus two read-only views; see *Current state*, "scheduler stages"), so execution-contract.md's "exactly three files" went stale again; corrected there 2026-10-25 (item 28).
3. AURORA-FULL-TRANSITION.md T4 status ("three files and no others") versus the policy test. — **Out of repo**; the policy test pins seven files.
4. AURORA-FULL-TRANSITION.md T2 status ("Two settings exist"; "`CanvasConfigBridge` is now the only file in SourbyCraft that names Canvas") versus AURORA-CONFIG.md and the policy test (`AuroraWorldsService` also names Canvas). — **Out of repo.**
5. AURORA-FULL-TRANSITION.md T3 status (saturation "runs the solve on the submitting thread") versus `perf/AsyncPathProcessor.java`. — **Out of repo.** Code decides (re-read 2026-10-25): the pool rejects (`AbortPolicy`) and never runs the solve on the submitting thread, but the slot is not skipped either: a declined or refused offload makes `PathNavigation` run the vanilla synchronous recompute on the mob's owning region thread, the same cost as the flag off (`perf/AsyncPathRecompute#offload` returns `false`). The verdict recorded here earlier ("refusal", and this file's "never runs on the region thread") was half right and is corrected in *Current state*.
6. engine-entity-ai.md §1.1/§2 (inline degradation; "the inline count is the one that judges this domain") versus `AsyncPathProcessor.PathStats` (`inline` is a legacy count). **Fixed** 2026-10-07 (the doc named `refused`). Reopened by PR #62: saturation now shows mostly as `declined` (the capacity check before the snapshot, `AsyncPathProcessor#noteDeclined`); `refused` counts only a submission the lane still rejects after that check. — **Fixed** 2026-10-25: engine-entity-ai.md §2 now names `declined` (with `refused`) as the saturation signal, as does the async-pathfinding spec's *Saturation contract*.
7. engine-storage.md summary ("the engine's chunk save path does not use it"; "none of the 16 feature patches touch region files") versus feature patches 0019 and 0021 and `RegionFileStorage`'s AWF hook. **Fixed.**
8. aurora-resource-governor.md ("budget comes from code defaults"; operator budgets "not in scope yet"; STORAGE "idle until AWF is wired") versus `config/AuroraConfig.java` (`aurora.scheduler.*`) and `awf/AwfEngine.java` (commits on `STORAGE`). **Fixed.** Re-checked 2026-10-25: the header now says three lanes, `STORAGE` also carries MultiProfile steps, and `/perf governor`'s thread ledger is described.
9. engine-network.md ("no direct NMS patch touches networking — all 16 feature patches") versus the feature patches that edit network classes. — **Fixed** in engine-network.md, which now lists the security/admission patches 0034, 0036, 0037, 0040 and 0041. (Its separate "no queue-health service" sentence is item 27.)
10. independence.md §2.1 (Canvas coupling "two calls in `SourbyCraftConfig`"; region-threading coupling "4 sites" in `PerformanceCollector`, `HudBars`, `AsyncPathCompletion`) versus `scripts/independence_policy.py` (5 Canvas sites in two files; 11 scheduler sites in three files). **Fixed.**
11. AURORA-INDEPENDENT-ENGINE.md §38 Phase 2 ("five sites across eighty files"; isolation "not started") versus the policy test (internal scheduler symbols confined to seven pinned files). — **Annotated** 2026-10-25: §38 carries a dated status note (seven pinned files, Stage 3 done for SourbyCraft's own sources) and §10 the stage statuses of *Current state*; the original counts stay as the first measurement.
12. AURORA-INDEPENDENT-ENGINE.md §38 ("Phase 0 is therefore complete") versus `qualification-readiness.md` §1/§6 (no certified comparable reference pair; the >3% gate is blocked). — **Annotated** 2026-10-25: a dated note under the sentence says "Phase 0 is complete" overstates the qualification state. (Its other source, AURORA-TASKS.md §C, is out of repo.)
13. AURORA-TASKS.md §C ("[ ] 2h+ concurrency soak") versus `docs/BASELINE.md` ("Second soak — 10 players, two hours … CERTIFIED"). — **Out of repo.**
14. AURORA-TASKS.md §C ("[x] 10-player connected baseline — certified both sides of the chunk-worker A/B") versus `docs/BASELINE.md` (the certified `ab-workers-2`/`ab-workers-6` baselines "are superseded … a workload that silently contained no entities"). — **Out of repo.**
15. AURORA-ROADMAP.md §3 listed the execution contract, compatibility layer, storage engine and dependency isolation as "Not complete" with no partial state. — **Fixed** 2026-10-25: §3 now points to *Current state* above, and the milestone status lines were corrected against `core/AuroraRuntime`, `execution/OwnerHandoff`, `bridge/AuroraBridge`, AWF and `UpstreamDependencyLedgerTest`.
16. AURORA-ENGINE-SYSTEM.md §23 Step 1 ("AuroraScheduler → Folia/Canvas adapter") versus the code, where no `AuroraScheduler` exists: owned services use the published `threadedregions.scheduler` API directly, scheduler internals are confined by `UpstreamDependencyLedgerTest` and the independence policy, and owner handoff goes through `execution/OwnerHandoff`. — **Fixed** 2026-10-25: the ENGINE-SYSTEM banner marks §4, §23, §27 and §32 superseded by `execution-contract.md` (the earlier "deliberately not built" rationale was in AURORA-FULL-TRANSITION T4, now out of repo).
17. AURORA-INDEPENDENT-ENGINE.md §31 ("Canvas remains a valuable upstream source during migration"), README.md ("Canvas remains an upstream implementation source where required") and this file's §2 diagram ("Canvas retained internals") versus AGENTS.md and `gradle.properties` (Paper is the only upstream since 2026-10-05; `paperRef`, lines 30-33). — README half **fixed** (`c86b3be`); this file's §2–§4 **fixed** 2026-10-25; AURORA-INDEPENDENT-ENGINE.md §31 **annotated** 2026-10-25 (Canvas is no longer an upstream; the original sentence is kept under the note).
18. independence.md §9 B2 ("the active Canvas-based 26.2 build") versus the same migration. — **Fixed**: §9 B2 no longer says "Canvas-based".
19. AURORA-DEVELOPMENT.md §3.1 (add Aurora namespaces to `sourbycraft_global_config.toml`) and this file's §3 (`sourbycraft_config/aurora/*.toml` split) versus the implemented `sourbycraft_config/aurora.yml` layered over the global file, both YAML since Build 49 (`config/AuroraKeys.FILE`, `SourbyCraftConfig`). — **Fixed** 2026-10-25: this file's §3 describes the one YAML file (split only when the number of keys justifies it), and AURORA-DEVELOPMENT.md §3.1/§4 carry a note that the namespaces live in `aurora.yml` and the §4 candidate keys were not added.
20. AURORA-UX.md stage count versus `core/SourbyCraftBootstrap.java`. Fixed 2026-10-07 at 14, then stale again (AURORA-UX said 15 with a 15-name order). — **Fixed** 2026-10-25: `TOTAL_STAGES = 19`, and AURORA-UX lists the order from `SourbyCraftBootstrap#init` (four stages added since Build 48: sourby mods, aurora redis fabric, awf multiprofile, abysslink).
21. aurora-task-d-validation.md ("collision candidate 0019") versus the feature-patch directory, where the collision patch is now `0018-SourbyCraft-translate-block-collision-boxes-without-.patch` (staged rename). **Annotated.** (The AURORA-TASKS.md half is out of repo.)
22. `qualification-readiness.md` §3 ("storage backlog ✘") versus `perf/StorageBacklog.java`/`RegionIoQueue` and `/perf storage` (per-world chunk-system I/O tasks accepted and not finished). Whether that satisfies the T7/T10 "storage backlog" metric is a decision, not settled here. (The README half, "no vanilla save-queue metrics", is gone since `c86b3be`.)
23. README.md called AURORA-INDEPENDENT-ENGINE.md "the phased roadmap". — **Fixed**: the README rewrite in `c86b3be` dropped it.
24. AURORA-TASKS.md Bridge section ("`[-]` here means implemented and unit-tested but not compiled into a server or run") versus its own next lines. — **Out of repo.**
25. AURORA-CONFIG.md omitted `aurora.bridge.max-pending-tasks-per-plugin` and `aurora.bridge.max-running-async-tasks-per-plugin`. — **Fixed**: AURORA-CONFIG.md lists both (LIVE, default `0` = unlimited).
26. AURORA-INDEPENDENT-ENGINE.md §38 Phase 0 names `EDFSchedulerThreadPool` as the contended scheduler; with the EDF patch (then feature 0028, now parked) the default `EDF` type constructs `AuroraEdfScheduler`. The measurement is historical and does not describe that backend. — settled 2026-10-07: the EDF patch was held and parked by owner decision (`sourbycraft-server/minecraft-patches/parked/README.md`); the baseline `SchedulerUtil.java.patch` again constructs LeafPile's `EDFSchedulerThreadPool`, so the Phase 0 measurement describes the active default backend again. `AuroraEdfScheduler` stays in the main tree, unselected. See `docs/development/AURORA-EDF-UPGRADE.md`.
27. engine-network.md §1/§3 ("no queue-health service"; queue health listed as unowned) versus `perf/ConnectionQueueHealth.java`, which `/perf network` samples (outbound bytes pending and unwritable channels per connection, a point sample, no policy). — **Fixed** 2026-10-25: engine-network.md §1/§3 describe the measurement and list only a queue-health *policy* as unowned, as the *Deepening* table below does.
28. execution-contract.md ("the backend is named in exactly three files"; "Stage 2 dispatch API and Stage 3 backend isolation are not implemented"; "epoch and target validation outstanding") versus `scripts/test_independence_policy.py` (seven pinned files), this file's stage statuses and `perf/AsyncPathValidity#sameGeneration` with MC feature patch 0047 (a navigation-generation check exists). — **Fixed** 2026-10-25: execution-contract.md names the seven pinned files, the `sameGeneration` check, and its *Status boundary* repeats this file's stage statuses.
29. The class javadoc of `execution/ResourceGovernor` ("the governor neither resizes nor reports" the upstream pools) versus `command/PerfGovernorView`, which prints their configured sizes through `execution/ThreadLedger`. The spec (aurora-resource-governor.md) is corrected; the javadoc is open.

## Deepening the engine — candidates and constraints

What Aurora does **not** own yet, collected from `engine-entity-ai.md` §3, `engine-chunk-world.md`
§3, `engine-network.md` §3, `engine-storage.md` §3 and AURORA-ROADMAP M9–M17/M22. This is a menu,
not a schedule: no row is planned for a date, and choosing one is the owner's decision.

Constraints that bind **every** row:

- **Measured only** — docs/DEVELOPMENT.md §2.4 (the rule AURORA-FULL-TRANSITION §4.5 stated before
  that plan left the repository): no deep engine optimization without CPU, allocation, MSPT, I/O or
  reproducible workload evidence; uncertified runs guide investigation only (docs/DEVELOPMENT.md
  truthfulness policy §3).
- **No automatic performance configuration of any kind** — docs/DEVELOPMENT.md §2.2. Nothing on this
  list may resize pools, change view/simulation distance, mob limits, AI, compression, JVM flags
  or any other setting in response to load. Diagnostics may warn and recommend; the operator
  decides.
- **Region ownership is authoritative** — docs/DEVELOPMENT.md §2.3: region-local state, immutable
  publication, owner handoff; no shared mutable scratch (§12 of this file). **One owner exception
  exists:** `aurora.bridge.thread-checks` defaults to `relaxed` (owner decision 2026-10-22,
  RESTART_REQUIRED), so while `aurora.bridge.mode = safe` admits legacy plugins a failed
  region-ownership check is counted and logged instead of thrown. With the default `mode = off`
  the checks stay enforced. A relaxed server is unsafe and unqualified by the owner's own
  statement; see [aurora-plugin-bridge.md](aurora-plugin-bridge.md) "Thread checks" and
  docs/DEVELOPMENT.md §2.3 "Owner decisions that override this rule". No row below may rely on the
  relaxation.
- **The product workload is undecided** — `compat-vs-performance.md` §4/§6.3: "one player
  exploring new terrain" and "ten settled players" give opposite answers (for example on chunk
  workers). A candidate whose benefit depends on that choice cannot be justified until the owner
  records it in docs/TODO.md.

Patch numbers in the second column are MC feature patches (`sourbycraft-server/minecraft-patches/features/`),
checked against that directory on 2026-10-25 (0001–0087). The column lists what each domain
has; ownership, correctness and guard patches that change no performance path are in the
`engine-*.md` documents, not here.

| Candidate area | What Aurora has today | Not owned (source) | Evidence needed before any change | Extra constraint |
|---|---|---|---|---|
| AI scheduling | Async periodic path recompute (EXPERIMENTAL, off): 0005 (`SnapshotPathRegion`), 0006 (offload), 0039 (immutable mob snapshot), 0042 (creaking home snapshot), 0047 (navigation generation), 0048 (no loads for the snapshot's IfLoaded reads), 0082 (no shared path-type cache off a region thread) | GoalSelector, Brain, Sensor scheduling; target scans; navigation beyond periodic recompute (engine-entity-ai §3) | AI-stress workload — missing (`qualification-readiness` §2) — with real clients attached | No AI disabling or reduction under lag (§2.2); mob AI does not run without players in range |
| Entity tick and tracking | 0003, 0007, 0008, 0009, 0011, 0012, 0014, 0015, 0017, 0044, 0046; entity tick counts per world 0052 | Tracking and metadata sync, item merge/despawn policy, living/mob tick ordering (engine-entity-ai §3) | Certified `entity-stress`/`players-N` pair with clients | Tracker paths cross synchronous plugin callbacks (`aurora-task-d-validation.md`); plugin semantics are part of correctness |
| Collision | Patch 0018 (arithmetic-tested, not gameplay-qualified) | Broader collision engine (ROADMAP M11) | Gameplay qualification of 0018 first | Vanilla full-block versus epsilon rules must not be unified |
| Chunk system | Lane attribution, region tick metrics (0013, 0079), optional generation-stage timing (0026, unverified); storage-traffic and timing telemetry: 0021, 0043 (region-file save time), 0050 (region-file load time), 0051 (chunk lifecycle transitions); region task-queue depth and wait time (0045, 0049) | Holder lookup, tickets, load/integration/unload/send, generation scheduling (engine-chunk-world §3) | Certified `chunk-stress` reference — certifiable without clients, the cheapest first reference | Worker count is product-workload-dependent; no default change and no automatic sizing |
| World tick | Patches 0004, 0010 | Scheduled ticks, block and fluid updates, block entities (engine-chunk-world §3) | Certified `chunk-stress`; random-tick volume is a weak lever on the measured host | — |
| Network | Lane CPU, byte/packet/connection counters (`aurora.network.counters`, LIVE); a queue-health measurement exists (`perf/ConnectionQueueHealth`, `/perf network`, point sample, no policy); security/admission guard hooks 0034, 0036, 0037, 0040, 0041 (default `LOG`) | Per-packet-type counts, allocation/copy analysis, compression policy, chunk and entity packet paths, any queue-health policy (engine-network §3) | Certified `network-stress` with clients (never certified so far) | Compression settings stay operator-owned; no adaptive compression (§2.2) |
| Storage / persistence | AWF (EXPERIMENTAL, off; 0019, 0021, 0053, 0071, 0072), storage backlog view, persistence tooling | Save queue, serialization handoff, region-file write policy, shutdown flush (engine-storage §3) | Persistence and crash validation before any throughput number | No performance gain from weakened durability (truthfulness policy §4, correctness outranks benchmark wins) |
| Memory | Heap/GC telemetry, cache inventory | No Aurora memory owner document exists (ROADMAP M17) | Allocation profiles with provenance; a soak for any leak claim | Reuse only with proven ownership (§12) |
| Scheduler | Contracts; internal scheduler symbols confined to seven pinned files (four adapters, two read-only views; *Current state*); the LeafPile-derived EDF backend (parked EDF patch) is HELD and parked by owner decision 2026-10-07 (`minecraft-patches/parked/`) | Independent compute domains beyond async path; scheduler replacement (INDEPENDENT-ENGINE Stages 4–5, ROADMAP M22) | Ownership/shutdown acceptance of the parked EDF patch first; then workload evidence against the current backend | Replacement policy (INDEPENDENT-ENGINE §39); thread counts must account for total CPU (truthfulness §6) |
| Plugin cost | Bridge counts plus per-plugin body timing and onLoad/onEnable wall time (2026-10-07); first joined measurement: demo `plugins-10` 2026-10-06, uncertified | Plugin behaviour itself; the `plugins-10` workload loads plugins but does not drive them with player commands (`qualification-readiness` §1/§2) | A certified `plugins-10` run with connected clients (the set is defined: `build-data/representative-plugins.json`, `scripts/baseline_workloads.py`) | Bridged sync work lands on the global region, which no thread count parallelises |


---

# 1. Meaning of Aurora

Aurora is not only a release codename and not only a branding layer.

Aurora is the name of the SourbyCraft architecture used to progressively move important runtime behavior out of loosely coupled downstream patches and into a coherent SourbyCraft-owned engine model.

Aurora means:

```text
SourbyCraft public runtime
        +
Sourby-owned configuration
        +
Sourby-owned lifecycle
        +
Sourby-owned telemetry
        +
Sourby-owned performance policy
        +
measured Minecraft/NMS optimization
        +
small upstream integration hooks
```

It does **not** mean blindly copying Canvas or Paper classes into `dev.iyanz.*`.

It also does **not** mean refusing to modify Minecraft/NMS internals. Aurora explicitly allows deep engine changes where measurement proves the change is valuable and correctness can be validated.

---

# 2. Core Architectural Goal

The target architecture is:

```text
                         SOURBYCRAFT
                              │
                    Aurora Runtime Core
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
 Configuration          Performance Core       Diagnostics
        │                     │                     │
        │               Metrics / Health        Spark / HUD
        │                     │                     │
        └─────────────── Aurora Services ──────────┘
                              │
                      Aurora Engine Hooks
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
       Minecraft            Paper          Engine baseline
        / NMS        (only upstream,     (region threading, former
                     pinned paperRef)    Canvas/Folia; owned since
                                         2026-10-05)
```

(Diagram corrected 2026-10-25: it showed "Canvas retained internals" as a third upstream. Since the
2026-10-05 migration Paper is the only upstream, pinned by `paperRef` in `gradle.properties`; the
former Canvas/Folia region-threading code is SourbyCraft's own engine baseline —
`minecraft-patches/sources/`, `paper-patches/files/`, `dev.iyanz.aurora.engine.*` — checked by
`scripts/baseline_policy.py`.)

The important point is ownership:

- SourbyCraft defines the runtime contract.
- SourbyCraft defines its own configuration surface.
- SourbyCraft owns diagnostics and performance semantics.
- SourbyCraft may modify Minecraft/NMS code directly when an optimization belongs in the engine hot path.
- Paper remains an implementation input where useful, and the inherited engine baseline is owned
  code, not an upstream; neither is the product architecture.

---

# 3. Aurora Configuration Model

Aurora must deepen SourbyCraft configuration ownership instead of continuing to add new Sourby behavior into the engine configuration files (`config/aurora-server.yml` / `aurora-worlds.yml`, the former Canvas files).

The configuration files (all YAML since Build 49):

```text
sourbycraft_config/sourbycraft_global_config.yml   (Build 49 layout; older layouts migrated once)
sourbycraft_config/aurora.yml
sourbycraft-security.yml
config/aurora-server.yml                             (was config/canvas-server.yml; migrated once)
config/aurora-worlds.yml                             (was config/canvas-worlds.yml; migrated once)
```

New SourbyCraft-owned behavior goes under the Aurora namespace, in one file:
`sourbycraft_config/aurora.yml`, layered over the global file (a key left in the global file counts
only where `aurora.yml` does not set it; the Build 49 migration moved such keys once). Its sections
are the ones that have keys with consumers (`config/AuroraKeys.java`, documented in
[AURORA-CONFIG.md](../AURORA-CONFIG.md)):

```yaml
aurora:
  entity:        # async-pathfinding (LIVE)
  diagnostics:   # lane-sampling (LIVE), chunk-generation-metrics, stall-recorder.* (RESTART_REQUIRED)
  cpu:           # cores (RESTART_REQUIRED, read before the configuration loads)
  bridge:        # mode, thread-checks (RESTART_REQUIRED); sync-route, quarantine-after, per-plugin limits (LIVE)
  scheduler:     # Resource Governor lane budgets (RESTART_REQUIRED)
  network:       # counters (LIVE)
  world:         # command-blocks, tick-functions (LIVE)
  awf:           # Aurora World Fabric, with awf.multiprofile and awf.redis.fabric (RESTART_REQUIRED)
  abysslink:     # optional control client, off by default (RESTART_REQUIRED)
```

A section exists only once a key has a consumer: there is no `aurora.performance`,
`aurora.chunk` or `aurora.memory` section, because nothing reads one yet (docs/TODO.md). The
earlier plan for a `sourbycraft_config/aurora/*.toml` directory split is not what the code does; a
split would only be made when the number of settings justifies it, and empty files are not created
for appearance.

---

# 4. Configuration Ownership Rule

A setting belongs to Aurora when it controls SourbyCraft-owned implementation or a SourbyCraft-specific optimization.

Examples:

```text
Aurora-owned:
- async pathfinding policy
- telemetry collection intervals
- profiler integration behavior
- performance warning thresholds
- Sourby scheduler helper queues
- Sourby entity optimization modes
- Sourby chunk optimization modes
- diagnostic/HUD behavior
- Sourby network instrumentation

Engine- or upstream-compatibility-owned:
- values the inherited engine baseline reads from config/aurora-server.yml and
  config/aurora-worlds.yml (the former canvas-*.yml; region-scheduler options, tick rate, autosave)
- region scheduler sizing in config/paper-global.yml (threaded-regions)
- Paper plugin compatibility settings
```

New Sourby features must not add new keys to the engine files simply because the engine already has a file.

---

# 5. No Auto-Tuning Still Applies

Aurora configuration ownership does not permit automatic configuration mutation.

Aurora may provide explicit settings such as (real keys in `sourbycraft_config/aurora.yml`):

```yaml
aurora:
  entity:
    async-pathfinding: false
  network:
    counters: true
```

but it must never do this internally:

```text
MSPT high → reduce entity AI
RAM high → reduce view distance
CPU high → rewrite region worker setting
```

Aurora optimizes implementation, not operator intent.

---

# 6. Immutable Runtime Configuration

Aurora settings should be parsed into immutable runtime snapshots.

Target flow:

```text
operator config
     ↓
parser
     ↓
validation
     ↓
AuroraConfigSnapshot
     ↓
service constructors / atomic snapshot publication
```

Hot paths must not repeatedly parse TOML/YAML or perform dotted-string lookups.

Preferred runtime model (the shape `config/AuroraConfig` uses: one record per section, published
as one immutable snapshot, for example `AuroraConfig.Entity(boolean asyncPathfinding)`):

```java
record Entity(boolean asyncPathfinding) {}
```

Settings that are read every tick should be reduced to primitive/final fields or immutable snapshots.

---

# 7. Reload Policy

Aurora settings must declare one of these behaviors:

```text
LIVE
RESTART_REQUIRED
IMMUTABLE_FOR_RUN   (design category only)
```

In code, `AuroraConfig.Lifecycle` has exactly two values, `LIVE` and `RESTART_REQUIRED`; every key in
`config/AuroraKeys.java` and `config/UtilityKeys.java` declares one of them. `IMMUTABLE_FOR_RUN`
(a value that can never change while the process runs, such as a fundamental region-ownership
mode) is a design category, not an enum value: such a setting is declared `RESTART_REQUIRED`.

Examples:

- `aurora.network.counters`, `aurora.bridge.sync-route`: LIVE
- executor topology (`aurora.scheduler.*`): RESTART_REQUIRED
- `aurora.bridge.thread-checks` (read once, when the checks first run): RESTART_REQUIRED

A reload command may update only settings explicitly marked live. A RESTART_REQUIRED key that a
reload sees changed is listed as pending (`/sourbycraft config`, `/perf governor`), not applied.
Since PR #62 `/sourbycraft reload` reads and applies the files on the SourbyCraft I/O executor,
never on the tick thread that dispatched it, and it is refused while the runtime is stopping or
failed to start (PR #62: code written and statically reviewed; not built; not tested by the
owner).

Do not pretend a setting was applied when a constructor-cached value still requires restart.

---

# 8. Aurora Engine Layers

Aurora uses four technical layers.

## Layer A — Runtime Services

Owned source under `dev.iyanz.sourbycraft.*`.

Examples:

- metrics runtime
- health evaluator
- HUD service
- profiler bridge
- lifecycle registry
- bounded I/O executor
- configuration service

## Layer B — Engine Adapters

Small Sourby-owned interfaces used only where they reduce concrete upstream coupling.

The ones that exist (corrected 2026-10-25; the names this section used to list, such as
`AuroraRegionAccess` or `AuroraSchedulerAccess`, were never created):

```text
execution/region/RegionBackend, RegionOwnership, RegionChunkLoading,
  RegionTopology, RegionTickRate, EnginePools   the Aurora region system;
                                                FoliaRegionBackend is its one backend adapter
execution/OwnerHandoff, Admission              hand work to an entity's owner;
                                                RegionOwnerHandoff is the adapter
perf/LiveSchedulerSource, perf/RegionLocator    read-only scheduler views for /perf
config/upstream/UpstreamConfigBridge            engine configuration reload
  (CanvasConfigBridge)
```

The independence policy pins which files may name the region backend (*Current state*,
"scheduler stages"). Do not wrap every upstream class.

## Layer C — Integration Patches

Small patches that call Sourby-owned logic or expose required engine data.

Target: small, reviewable, low-conflict patches.

## Layer D — Deep Minecraft/NMS Optimizations

Direct modifications to Minecraft server code for measured hot paths.

These are first-class Aurora work, not forbidden exceptions.

---

# 9. Deep Minecraft/NMS Optimization Policy

Aurora must optimize code **inside Minecraft/NMS** when that is the correct technical location.

Examples of valid deep optimization domains:

- `Entity`
- `LivingEntity`
- `Mob`
- `GoalSelector`
- `Brain`
- `PathNavigation`
- `ServerLevel`
- `ServerChunkCache`
- chunk holder/ticket paths
- collision routines
- random/block/fluid ticks
- entity tracking
- packet generation
- serialization hot paths
- block entity ticking

Aurora is not limited to external helper classes.

The decision rule is:

> If the cost is inside a hot Minecraft method, and the optimization requires local state or algorithm changes there, modify the NMS path directly rather than adding an inefficient external wrapper.

---

# 10. Direct NMS Patch Requirements

Every deep engine optimization must include:

1. profiler/JFR evidence or a reproducible benchmark reason
2. identified hot method or allocation source
3. region ownership analysis
4. behavior/compatibility analysis
5. before/after benchmark
6. regression test where practical
7. explanation of retained upstream semantics

A patch must not be accepted only because it reduces source lines or appears clever.

---

# 11. Optimization Classes

Aurora deep optimizations should be classified as:

```text
ALGORITHM
ALLOCATION
LOOKUP
LOCALITY
BATCHING
LOCK_CONTENTION
SERIALIZATION
NETWORK
SCHEDULING
```

This classification helps explain why a patch exists and what metric should improve.

---

# 12. Allocation Optimization

Aurora may reuse mutable state only when ownership is proven.

The branch already found unsafe cases where shared `ServerLevel` scratch state crossed region threads. This failure class must not return.

Rules:

- entity-owned scratch may be valid when the entity is region-confined and data does not escape
- region-owned scratch belongs in region-local state
- `ServerLevel` fields are not automatically region-local
- static scratch state is prohibited for mutable hot-path buffers
- published values must not reuse mutable scratch storage unless ownership is transferred permanently

If allocation is cheap and reuse adds correctness risk, keep the allocation.

---

# 13. Entity / AI Aurora Work

The entity engine is a priority deep-optimization domain.

Aurora should profile and potentially optimize:

```text
Entity.tick
LivingEntity.tick
Mob.tick
GoalSelector.tick
Brain.tick
Sensor.tick
PathNavigation.tick
collision queries
entity section lookups
item merge/pickup scans
entity tracking
```

Potential techniques:

- avoid duplicate spatial queries
- eliminate repeated coordinate/object conversion
- use region-confined reusable state where proven safe
- reduce unnecessary AI recalculation
- reduce repeated path validation
- improve data locality
- reduce allocation in collision/entity query paths

Gameplay semantics must remain explicit and stable.

---

# 14. Chunk / World Aurora Work

Aurora should own a measured chunk optimization program.

Profile:

```text
chunk holder lookup
tickets
chunk loading
chunk generation
chunk integration
chunk saving
unload
region file I/O
player chunk tracking
chunk packet construction
```

Potential direct NMS changes include:

- reducing duplicate holder/map lookup
- eliminating repeated packed-coordinate calculation
- batching safe save work
- reducing serialization copies
- reducing temporary collection creation
- improving queue ownership

Persistence correctness is a release-blocking requirement.

---

# 15. Scheduler Aurora Work

Aurora should progressively define the scheduling contract it needs rather than directly depending everywhere on the inherited engine's implementation details (the former Canvas/Folia region scheduler, owned since 2026-10-05).

Aurora owns:

- Sourby runtime worker lifecycle
- administrative I/O execution
- telemetry scheduling
- diagnostics tasks
- any Sourby-specific CPU workers

Upstream region scheduling remains the execution authority until a proven replacement exists.

The goal is replaceability, not an immediate scheduler rewrite.

---

# 16. Network Aurora Work

Deep network optimization may touch NMS packet construction and the Netty-facing pipeline where appropriate.

Profile first:

- encode/decode CPU
- compression CPU
- packet allocation
- entity tracking traffic
- chunk packet traffic
- flush behavior
- queue growth

Rules:

- no disk/HTTP/database work on event loops
- no adaptive compression mutation
- preserve packet guards and security limits
- avoid unsafe packet object reuse

---

# 17. Aurora Performance Core

Aurora should become the canonical source of performance semantics.

```text
engine counters
region counters
GC/JVM sampler
network counters
scheduler counters
      ↓
Aurora Metrics Runtime
      ↓
immutable PerformanceSnapshot
      ↓
/tps /mspt /ram /perf HUD Spark/API
```

No command should invent its own TPS or MSPT definition.

---

# 18. SourbySpark under Aurora

Spark integration is an Aurora observability component.

Aurora should provide Spark with:

- SourbyCraft platform identity
- Sourby config group
- Sourby tick/MSPT values
- region-aware information
- runtime metadata
- worker/thread classification
- health context where cheap

Do not duplicate expensive scans simply to enrich Spark metadata.

A dedicated Spark fork is only justified if adapter-level integration cannot deliver required region/runtime visibility.

---

# 19. Aurora Health Model

Aurora diagnostics should evaluate:

```text
Tick health
Region health
CPU pressure
Memory pressure
GC pressure
Scheduler backlog
Chunk pressure
Network pressure
```

The result is advisory.

Example:

```text
HEALTH: WARNING
Primary cause: world region 12,-8 p95 MSPT 46.2ms
Secondary: GC overhead 4.1%
```

Aurora must never respond by silently changing gameplay settings.

---

# 20. Aurora Independence Milestones

## Aurora A1 — Identity

- SourbyCraft public identity everywhere
- Canvas/Folia named only as provenance and attribution of the engine baseline, not as an upstream
  or in public version strings (Build 47+)

## Aurora A2 — Configuration

- new Sourby performance settings live under Aurora config
- Spark displays Aurora/Sourby config
- no new Sourby feature requires the engine (former Canvas) configuration files

## Aurora A3 — Runtime

- lifecycle, telemetry, diagnostics, HUD, async I/O fully Sourby-owned

## Aurora A4 — Engine Integration

- repeated engine-baseline internals (former Canvas/Folia) hidden behind narrow Sourby contracts where useful
- patches shrink to integration points

## Aurora A5 — Deep Engine Optimization

- measured NMS optimization exists across entity/chunk/network/scheduler domains
- each optimization has evidence and tests

## Aurora A6 — Build Freedom

- clean repository builds reproducibly
- no separate Canvas binary/runtime service required
- cached runtime remains functional without upstream network availability

## Aurora A7 — Replaceability

- selected upstream components can be replaced/rebased without changing the Sourby public contract

---

# 21. Aurora Development Order

Required order:

```text
Correctness
   ↓
Certified baseline
   ↓
Aurora config ownership
   ↓
Runtime/telemetry stabilization
   ↓
JFR hot-path ranking
   ↓
Deep NMS optimization
   ↓
Patch reduction / ownership migration
   ↓
Soak + persistence qualification
   ↓
Stable release
```

Do not reverse this into “add many patches first, benchmark later.”

---

# 22. Aurora Acceptance Criteria

Aurora can be considered mature when:

- SourbyCraft owns its public runtime contract
- SourbyCraft owns new performance configuration
- all new performance settings are explicit and operator-controlled
- NMS optimization is evidence-driven rather than patch-count-driven
- major hot paths have benchmark/JFR evidence
- telemetry is low-overhead and unified
- region safety remains intact
- persistence and restart tests pass
- no standalone Canvas JAR or service is required
- SourbyCraft can run after first bootstrap with cached dependencies while upstream network is unavailable
- large Sourby implementations live in Sourby-owned code where practical
- remaining direct upstream dependencies are documented and intentional

---

# 23. Final Principle

Aurora is successful when SourbyCraft becomes more independent **and** more efficient without sacrificing correctness.

The target is not:

```text
more patches = more performance
```

The target is:

```text
measured engine improvements
+ clear ownership
+ first-party configuration
+ smaller integration surface
+ stable region-thread behavior
+ predictable operation
```

Aurora should make SourbyCraft a server engine that can stand on its own runtime contract while still using upstream code responsibly where doing so remains technically beneficial.
