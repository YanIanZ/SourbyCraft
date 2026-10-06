# T10 Qualification — Readiness

What §16 of `docs/AURORA-FULL-TRANSITION.md` requires, against what exists. Written so the
remaining work is a list rather than a rediscovery, and so nothing here reads as done when it
is not.

T10 is the gate the rest of the transition waits on: §4.5 allows no optimization without
measurement, so T5's engine domains cannot move from *documented* to *optimised* until this
passes.

---

## 1. Gate items

| Gate requirement | State | Evidence |
|---|---|---|
| representative benchmark exists | **partial** | 9 of 11 workloads (§2). Updated 2026-10-07: the plugin-heavy `plugins-10` is defined (`build-data/representative-plugins.json`; EssentialsX and Vault bridged, the SuperiorSkyblock2 Folia port native, spark) and was **measured** once, uncertified, on Sourby Demo (2026-10-06; `docs/BASELINE.md` *The plugin workload*). Its fidelity: plugins loaded, not driven by player commands. AI stress still missing |
| no unexplained >3% regression in affected workloads | **blocked** | needs a certified comparable before/after reference pair; a certified soak is not a regression pair. Still blocked on 2026-10-07: the newer observations are **measured**, not certified — Demo `players-10` (2026-10-05), Demo `plugins-10` (2026-10-06), and one exploring player's chunk delivery with 2 vs 6 workers (Demo, 2026-10-06: full view in 18–23 s vs 11–17 s; `chunk-workers.md`). None is a reference pair, and the chunk-worker result answers a different workload from the swarm A/B |
| no known region ownership bug | **one open** | §5. A second sighting of the same family (console command, `CommandSourceStack.getLevel()` null ~2 s after `Done` on an AWF reboot) is recorded in TODO P0, reproduced once. Not in the current build: the held EDF backend (patch 0028, parked 2026-10-07) whose ownership acceptance is pending |
| no known persistence bug | **one known limitation** | Chunk, entity and player data: no bug found — 104/104 local and 20/20 on Sourby Demo (2026-10-05, post-migration build), crash 49/49 region files and 53/53 AWF (§4). Known XFAIL: world game time/spawn metadata (`PaperLevelOverrides`) is not written by autosave, so a crash rewinds game time to the last boot (TODO P0). No run on the Build 47 release-candidate tree of 2026-10-07 |
| no unbounded queue | **met** | `UnboundedQueueAuditTest`, 3 tests |
| heap/threads/tasks stabilize after load | **partial** | certified 2h 10-player soak (2026-09-15, commit `1c883a55`, before Build 47) shows stable RSS, heap-after-GC and tick duration; thread/task stabilization still needs explicit gate evidence; no soak of the Build 47 tree or with plugins |
| cached runtime boots offline (T8) | **corrected** | a *populated* runtime boots offline; a *fresh* one does not boot at all — [slim-jar bootstrap failure](slim-jar-bootstrap-failure.md) |
| shutdown completes predictably | **partial** | unit tests, clean console shutdowns and a live restart on the deployment server; under load (6 moving clients) only on a local fixture so far. 2026-10-06: every native plugin's disable logged an ERROR after Paper patch 0007 (reproduced on a real boot); fixed by patch 0009, with a CI check that has not run yet |

---

## 2. Workloads — 9 of 11

Defined in `scripts/baseline_workloads.py`:

| §16 requires | Have | Note |
|---|---|---|
| idle | ✔ `idle` | certifiable without clients |
| 10 / 50 / 100 players | ✔ `players-10/50/100` | `requires_connected_players` |
| entity stress | ✔ `entity-stress` | `requires_connected_players` |
| network stress | ✔ `network-stress` | never certified |
| chunk traversal | ✔ `chunk-stress` | certifiable without clients |
| generation stress | ✔ `chunk-stress` | its moving window generates fresh terrain continuously; the plan's own fidelity note says generation dominates. Listed as missing here until 2026-09-20, which was wrong |
| save stress | ✔ `save-stress` | rewrites loaded chunks that keep changing, then flushes; needs no clients, so certifiable in the same window as `idle` |
| **AI stress** | ✘ | distinct from entity stress: goal/brain/pathfinding cost, not tick count. Needs connected clients to mean anything |
| plugin-heavy representative | ✔ `plugins-10` | defined 2026-10-07 (`build-data/representative-plugins.json`); `requires_connected_players`; one uncertified Sourby Demo run (2026-10-06); local harness run not possible on the development machine |

---

## 3. Metrics — 10 of 14

From a real `baseline.json` (`idle`, 2026-09-20):

| §16 requires | State | Source |
|---|---|---|
| TPS | ✔ | `metrics.tick.tps` (mean/min/p50/p95/p99/max) |
| MSPT average | ✔ | `metrics.tick.mspt.mean` |
| MSPT p50/p95/p99/max | ✔ | `metrics.tick.mspt` |
| CPU | ✔ | `metrics.cpu` — process, machine and foreign fractions |
| heap | ✔ | `metrics.tick.heap_used_bytes` |
| RSS | ✔ | `metrics.rss` + `metrics.drift` |
| allocation rate | ✔ | `metrics.allocation.bytes_per_second` |
| GC pauses | ✔ | `metrics.gc` |
| queue depths | **partial** | async-path pool only (`/perf async`); no chunk, network or save queue |
| task latency | **partial** | async-path wait latency only |
| chunk latency | ✘ | — |
| entity cost | ✘ | — |
| network throughput | ✘ | — |
| storage backlog | ✘ | — |

The four absent ones are the T7 telemetry items that need hooks into upstream paths; see
[the network](engine-network.md) and [storage](engine-storage.md) domain documents.

---

## 4. Persistence — covered and passing

`scripts/verify_persistence.py` writes known state, stops the server, inspects the files on
disk while nothing holds them, boots a second server over the same directory and reads the
state back. Structural checks and round-trip checks are both kept, because a region file can be
structurally valid and hold the wrong chunk.

| Run | Result |
|---|---|
| Local fixture, build 46c | **16 / 16 checks** |
| Deployment server, live restart | **4 / 4** — 64/64 blocks, 24/24 entities, game time advanced, gamerule survived |
| Deployment server (Sourby Demo), post-migration build (2026-10-05) | **20 / 20** — `verify_persistence_panel.py`: 3 real panel stop/start cycles, each reading back 64 blocks, 24 entities and an advancing game time written by the previous boot; clean shutdowns, no boot errors; probe placed at y=300 and removed afterwards. No player check: the server is online-mode |
| Crash (`verify_crash.py`), post-migration build (2026-10-05) | Region files **49 / 49**, AWF **53 / 53**: three SIGKILLs 3, 8 and 15 s into save traffic (4 moving clients, a block site rewritten every second); every boot comes up, logs no chunk corruption and holds the flushed checkpoint exactly. Known limitation, reported as XFAIL: game time rewinds to the last boot, because level.dat is only written at startup and clean stop |
| Local fixture, post-migration build (2026-10-05) | **104 / 104** — 4 consecutive restarts each reading back the previous boot's writes; probe player's inventory, XP level and position round-tripped on disk and through the server; last stop issued with 6 moving clients and the probe player online |

Two limits are stated rather than hidden:

- **Player data** was structural only until 2026-10-05; a headless probe player now round-trips
  inventory, experience level and position, both on disk and through the server.
- **Region files in unused dimensions** reference up to one sector past EOF while the
  overworld's are exact, and the server reads all of them. The trailing sector is unpadded, not
  missing, so overruns under 4 KiB are reported benign and a whole missing sector still fails.

§11.6 forbids buying performance with durability. This is the check that would notice.

## 5. Known region-ownership bug

One, open and unreproduced.

`/say` from the console threw, once, during a config reload on the deployment server:

```text
NullPointerException: Cannot invoke "ServerLevel.getGameRules()" because the return value of
  "CommandSourceStack.getLevel()" is null
    at Commands.executeCommandInContext(Commands.java:461)
    at RegionizedServer.globalTick(RegionizedServer.java:278)
```

Same family as the fixed `/execute if block` crash (patch 0016): a console command running on a
tick thread with no region context bound. **24 subsequent `/say` calls interleaved with a
reload produced 0 reproductions**, so it is recorded rather than patched — a speculative guard
on one sighting would be a change nobody can verify.

The gate says *no known region ownership bug*. This is a known one until it is either
reproduced and fixed, or explained.

---

## 6. Blocked on a quiet machine

Certification requires foreign CPU below **10%** of the machine for the whole window.
Four attempts on 2026-09-18/20 measured 21–23% average with peaks of 55–78%, against a
development desktop in active use. The runs produce complete evidence and are refused only as
*comparison references*, which is the correct behaviour.

Consequences, in order of what they block:

1. no certified **comparable reference pair** → no regression comparison → the >3% gate cannot be evaluated,
2. the certified 2h 10-player soak already provides long-window memory/tick stability evidence, but it does not replace per-change soak requirements or explicit thread/task stabilization evidence,
3. `players-*` and `entity-stress` additionally need real clients attached
   (`--connected-players N`), because entity activation is computed around players — see the
   [client gap](engine-entity-ai.md#4-the-measurement-constraint-this-domain-has).

`idle` and `chunk-stress` certify without clients and are therefore the cheapest first
references to obtain.

---

## 7. Order of remaining work

1. ~~Persistence validation tooling~~ — **done**, §4.
2. **`idle` + `chunk-stress` certified references** — one quiet window, no clients. Still the
   binding constraint: without a reference there is nothing to compare a regression against.
3. **The remaining workloads**: AI stress, which needs clients, and a plugin-heavy
   representative set, which needs a decision about which plugins represent the product.
4. **Client-attached runs** for `players-*` and `entity-stress`.
5. **The regression gate**, once two certified runs of the same workload exist.

### What the deployment server can and cannot settle

The panel is dedicated and idle, which is exactly what this desktop is not, so the soak runs
there. What it reaches without clients: sustained load, chunk load/unload cycling, heap
recovery after the load is removed, restart, and shutdown. What it cannot reach: repeated
joins/quits and any entity-AI load, because mob AI does not run without a player in activation
range — driving mobs there would measure the inactive path and call it a soak.

It also cannot produce a *certified baseline*: `run_baseline.py` needs a shell on the host to
record JFR and operating-system samples, and the panel offers a console and a file API. So the
panel settles stability; certification still needs the local harness on a quiet machine.


---

## 8. Evidence vocabulary — mandatory

To prevent qualification drift, use these words precisely:

- **implemented**: code exists and functional verification passed;
- **measured**: a run produced data, regardless of certification;
- **certified run**: the harness accepted that run's provenance/noise constraints;
- **certified soak**: a certified long-duration run; it is stability evidence, not automatically a performance reference;
- **certified comparison/reference pair**: comparable before/after runs suitable for a regression claim;
- **qualified**: every applicable T10 gate is satisfied.

A commit, README or release note must not replace one term with a stronger one. In particular, "certified soak" must not be shortened to "T10 qualified", and an uncertified profiler delta must not be reported as a throughput improvement.
