# T10 Qualification — Readiness

What T10 requires, against what exists. Written so the remaining work is a list rather than a
rediscovery, and so nothing here reads as done when it is not. The requirements were §16 of
`docs/AURORA-FULL-TRANSITION.md`, which was removed from the repository with the other planning
documents in `c86b3be` (2026-10-22); they are copied in *T10 requirements* below, and the removed
file is still in git history (`git show c86b3be^:docs/AURORA-FULL-TRANSITION.md`).

T10 is the gate the rest of the transition waits on: §4.5 of the same document allowed no deep
engine optimization without CPU, allocation, MSPT, I/O or reproducible workload evidence, so T5's
engine domains cannot move from *documented* to *optimised* until this passes.

2026-10-21: Build 47 is published as a full release (`release=true`) by owner decision, **not**
labelled STABLE and not QUALIFIED; publication does not change any row below. The STABLE label and
the stability rows are Build 49's; the certified rows are Build 50's
([ROADMAP-47-50.md](../releases/ROADMAP-47-50.md)). Since then: Build 48 (`v26.2-r48`), Build 49
(`v26.2-r49`) and its hotfix 49.1 (`v26.2-r49.1`) were published with `release=true`, Build 49
explicitly without a STABLE claim because its gate list is not PASS
([26.2-build-49-release.md](../releases/26.2-build-49-release.md)); Build 50 is a prerelease
(`v26.2-r50`, `release=pre`, tagged at `5f5a167`). No published build is QUALIFIED.

**Status 2026-10-25 (PR #62):** PR #61 and PR #62 landed after the `v26.2-r50` tag, so their work
is in no published build; how it is published is owner decision PM-011. The PR #62 changes are
code written and statically reviewed; not built; not tested by the owner. They touch AWF storage
(eviction, commits, unload durability, the Redis client), MultiProfile, configuration loading, the
auto-updater, the Aurora Bridge, spark and feature patches (owner-thread guards, shutdown). Every
run recorded below was made on an older tree; for a build that contains PR #62 those results are
NEEDS RETEST, and they stay listed with their tree or build label. Per-item status:
[BUILD-50-PM-MATRIX.md](../releases/BUILD-50-PM-MATRIX.md).

## T10 requirements

Copied from §16 of the removed transition document:

- **Workloads:** idle; 10 connected (or equivalent) players; 50 players; 100 players; entity
  stress; AI stress; chunk traversal; generation stress; save stress; network stress; a
  plugin-heavy representative workload (11 items).
- **Metrics:** TPS; MSPT average; MSPT p50/p95/p99/max; CPU; heap; RSS; allocation rate; GC
  pauses; queue depths; task latency; chunk latency; entity cost; network throughput; storage
  backlog (14 items).
- **Soak (minimum):** a 2 h+ concurrency soak; repeated joins/quits; chunk load/unload cycles; a
  restart cycle; shutdown under load; heap recovery after load.
- **Persistence:** clean shutdown, restart, chunk save integrity, player data, entity data, region
  files, world metadata.
- **Completion gate:** the seven rows of §1 except the T8 offline-boot row, which is carried here
  because it was recorded with this gate.

---

## 1. Gate items

| Gate requirement | State | Evidence |
|---|---|---|
| representative benchmark exists | **partial** | 10 of 11 workloads (§2); only AI stress is missing. Updated 2026-10-07: the plugin-heavy `plugins-10` is defined (`build-data/representative-plugins.json`; EssentialsX and Vault bridged, the SuperiorSkyblock2 Folia port native, spark) and was **measured** once, uncertified, on Sourby Demo (2026-10-06; `docs/BASELINE.md` *The plugin workload*). Its fidelity: plugins loaded, not driven by player commands. AI stress still missing |
| no unexplained >3% regression in affected workloads | **blocked** | needs a certified comparable before/after reference pair; a certified soak is not a regression pair. Still blocked on 2026-10-07: the newer observations are **measured**, not certified — Demo `players-10` (2026-10-05), Demo `plugins-10` (2026-10-06), and one exploring player's chunk delivery with 2 vs 6 workers (Demo, 2026-10-06: full view in 18–23 s vs 11–17 s; `chunk-workers.md`). None is a reference pair, and the chunk-worker result answers a different workload from the swarm A/B |
| no known region ownership bug | **none open** | §5. Both sightings (`/say`, console command ~2 s after `Done` on an AWF reboot) were one startup bug, reproduced and fixed 2026-10-13 (MC 0028 `rebind-early-console-command-sources`), in CI since run 38427215968 (2026-10-13). The release gate itself stays OPEN on watchdog-specific acceptance and a soak without ownership violations (Build 49); the 2026-10-14 audit findings ENG-1/3/4 were fixed in `1c57a736` (2026-10-20, unit-tested; no boot drains a non-empty queue). An ENG-3 follow-up found that a world unload ran the *bodies* of pending Bukkit entity tasks (saved, not removed, entities; a test-plugin unload fixture ran 16 far-future bodies and 0 retired callbacks); fixed in `63e4658` (2026-10-22, Paper feature 0021: a retired scheduler gets exactly its retired callback; fixture after the fix: 16 retired, 0 bodies run), in Build 48 and later. Not in the current build: the held EDF backend (parked EDF patch, `minecraft-patches/parked/`, 2026-10-07) whose ownership acceptance is pending |
| no known persistence bug | **not met** (2026-10-25: AWF defects known in published builds, fixed only in the unbuilt PR #62); was *no open bug known; not qualified* on 2026-10-21 | Chunk, entity and player data: no bug found — RC tree `cbef3fb9` local laptop run 2026-10-13: persistence 105/105, crash 52/52 region files and 56/56 AWF (§4); earlier 104/104 local, 20/20 on Sourby Demo, crash 49/49 and 53/53 (2026-10-05). The game-time XFAIL is fixed by MC 0027 (XPASS on every recovery; XFAIL entry removed in `3cd33b3f`). Redis backend-failure defects D1–D3/D5/D7 fixed (`d97fe247`, local scenarios). The 2026-10-14 audit findings AWF-2 (`ebb9c1fb`), ENG-5 (`1c57a736`) and AWF-3 (`AwfMaintenanceDriver`, `6d4c41fe`) are fixed, unit-tested only. Functional agent test-server runs 2026-10-20 (laptop, no clients, not certified): region files 61/61, AWF FILE 65/65. Persistence/crash runs on the Build 49 tree with clients are a Build 49 gate. **2026-10-25 audit (PR #62):** AWF persistence defects were found in the published code and fixed only in PR #62 (code written and statically reviewed; not built; not tested by the owner): a write racing post-commit eviction could be dropped and then marked clean (PM-023), an unconfirmed final commit on world unload stopped the server, a Redis commit could be confirmed by `WAITAOF` on a different pooled connection. For published builds up to `v26.2-r50` these are known, open persistence bugs; for a build with PR #62 the row needs the owner AWF runs again |
| no unbounded queue | **met** | `UnboundedQueueAuditTest`, 3 tests |
| heap/threads/tasks stabilize after load | **partial** | certified 2h 10-player soak (2026-09-15, commit `1c883a55`, before Build 47) shows stable RSS, heap-after-GC and tick duration; thread/task stabilization still needs explicit gate evidence; no certified or complete soak of the Build 47 tree or with plugins (the 2026-10-07 `plugins-10` demo soak ran ~100 min, uncertified) |
| cached runtime boots offline (T8) | **met in CI; remote qualification open** | The fresh-runtime failure of 2026-09-20 was fixed in SourbyClip 3.0.26 on 2026-09-21 ([slim-jar bootstrap failure](slim-jar-bootstrap-failure.md#resolution--2026-09-21)). The build job's step *Bootstrap failure recovery and offline boot* (`.github/workflows/build.yml`) boots a fresh directory three times: with no network it must fail within 120 s naming the file and source URL it could not fetch, leaving no `.tmp`; online it must reach `Done`; offline again it must reach `Done` from the cache (`Classpath prepared`) with no download attempt. It runs on every push build, and every published tag since `v26.2-r48` came from a build job that ran it (the release job `needs: [build, rebuild]`). Not run on the PR #62 tree (pull requests get no Gradle build or boot); that step now also feeds the Build 50 configuration migration, so its first push run after the merge is the retest. Remote-host cold download and fault-injection qualification remain open ([bootstrap download audit](bootstrap-download-audit.md)) |
| shutdown completes predictably | **partial** | unit tests, clean console shutdowns and a live restart on the deployment server; under load (6 moving clients) only on a local fixture so far. 2026-10-06: every native plugin's disable logged an ERROR after Paper patch 0007 (reproduced on a real boot); fixed by patch 0009; its CI check passes since run 37515867230 (2026-10-07) |

---

## 2. Workloads — 10 of 11

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

## 3. Metrics — 8 of 14 recorded by the harness (network throughput for network-stress only)

The harness column is what `run_baseline.py` writes into `baseline.json` (field names from a real
`idle` run, 2026-09-20; `baseline_metrics.py` reads JFR events and OS samples). The live column is
what a running server shows; none of those views is sampled into `baseline.json`.

| T10 requires | Harness (`baseline.json`) | Live source |
|---|---|---|
| TPS | ✔ `metrics.tick.tps` (mean/min/p50/p95/p99/max) | `/tps`, `/perf tick` |
| MSPT average | ✔ `metrics.tick.mspt.mean` | `/perf tick` |
| MSPT p50/p95/p99/max | ✔ `metrics.tick.mspt` | `/perf tick` (server estimates) |
| CPU | ✔ `metrics.cpu` — process, machine and foreign fractions | `/perf cpu`, `/perf lanes` |
| heap | ✔ `metrics.tick.heap_used_bytes` | `/perf memory` |
| RSS | ✔ `metrics.rss` + `metrics.drift` | `/perf memory` |
| allocation rate | ✔ `metrics.allocation.bytes_per_second` | none (`/perf memory` says it is not collected; use JFR or spark) |
| GC pauses | ✔ `metrics.gc` | `/perf gc` |
| queue depths | ✘ | `/perf async` (async-path pool), `/perf network` (outbound queues), `/perf storage` (pending chunk I/O), `/perf scheduler` (queued region tick and chunk tasks, approximate; the global tick's queue has no size accessor), `/perf governor` (lane queues and rejections) |
| task latency | ✘ | `/perf scheduler` (region task wait latency, tick and chunk tasks), `/perf async` (async-path wait latency) |
| chunk latency | ✘ | `/perf chunks` (generation-stage timing), `/perf storage` (region-file save and load timing) |
| entity cost | ✘ | `/perf entities` (entity tick counters) |
| network throughput | **network-stress only**: `network` block, client-observed round trips and bytes per second from the load generator | `/perf network` (wire and packet rates) |
| storage backlog | ✘ | `/perf storage` (pending I/O) |

Owner decision needed: whether a console-only live source satisfies a T10 metric, or the harness
must record it (sample the `/perf` views during the window or add JFR events, then parse them in
`baseline_metrics.py`). Until then the rows the harness does not record stay open for T10. See
[the network](engine-network.md) and [storage](engine-storage.md) domain documents.

---

## 4. Persistence — covered and passing on the trees listed

Every run below predates PR #62, which changed the AWF commit, eviction and unload paths and the
Redis backend: for a build that contains it, these are NEEDS RETEST.

`scripts/verify_persistence.py` writes known state, stops the server, inspects the files on
disk while nothing holds them, boots a second server over the same directory and reads the
state back. Structural checks and round-trip checks are both kept, because a region file can be
structurally valid and hold the wrong chunk.

| Run | Result |
|---|---|
| Local fixture, build 46c | **16 / 16 checks** |
| Deployment server, live restart | **4 / 4** — 64/64 blocks, 24/24 entities, game time advanced, gamerule survived |
| Local laptop (Apple M2, 16 GiB, machine not quiet), Build 47 RC tree `cbef3fb9`, jar `975d3bda…` (2026-10-13) | `verify_persistence.py` **105 / 105** (3 restarts, last stop with 6 moving clients); `verify_crash.py` region files **52 / 52**, AWF **56 / 56** (three SIGKILLs each, no corruption); game time XPASS on every recovery, so the old XFAIL is gone |
| Crash (`verify_crash_panel.py`), Sourby Demo, tree with feature 0028 (world metadata on autosave, now 0027) (2026-10-07) | game time **survives** a SIGKILL after `save-all flush` (1,792,900 ≥ 1,792,548); no corruption; 64/64 blocks; 48/48 entities (harness double-count, all survived) |
| Crash (`verify_crash_panel.py`), Sourby Demo, Build 47 candidate tree (2026-10-07) | **14 / 15** — two panel SIGKILLs (after flush, mid-write): clean boots, no corruption, 64/64 blocks and 24/24 entities of the last checkpoint; the known game-time rewind remains (XFAIL) |
| Deployment server (Sourby Demo), Build 47 candidate tree `beb7f40e` (2026-10-07) | **20 / 20** — `verify_persistence_panel.py`, 3 real panel restarts; clean shutdowns, no boot errors, 64/64 blocks, 24/24 entities, game time advancing |
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

None open in source. The one recorded here was reproduced and fixed on 2026-10-13 (Minecraft feature
patch 0028 `rebind-early-console-command-sources`, numbered 0029 before the same-day renumbering;
SPEC.md item B88; SPEC.md was removed in `c86b3be`, see git history). Verified locally and in CI run 38427215968 (head `8c70b53c`, run as `4275912f` before a message-only rewrite, 2026-10-13), whose boot step runs the early console probe.

`/say` from the console threw, once, during a config reload on the deployment server, and a console
command threw the same way ~2 s after `Done` on an AWF reboot:

```text
NullPointerException: Cannot invoke "ServerLevel.getGameRules()" because the return value of
  "CommandSourceStack.getLevel()" is null
    at Commands.executeCommandInContext(Commands.java:461)
    at RegionizedServer.globalTick(RegionizedServer.java:278)
```

**Cause.** This was not a missing region context. The console thread builds the
`CommandSourceStack` when it *reads* the line (`PaperConsole.runCommand` →
`MinecraftServer.createCommandSourceStack()` → `findRespawnDimension()` → `overworld()`). A line read
before the overworld is registered captures `level = null`. It waits in `serverCommandQueue` until
the first global tick after `Done` and throws there. The earlier 24 `/say` calls interleaved with a
reload produced nothing because the overworld already existed. The deployment log cannot show whether
that line was read during a restart. The stack matches exactly.

**Fix.** `DedicatedServer.handleConsoleInputs` rebuilds the source when its level is null; by then the
worlds exist. Both eager producers (the terminal console and the GUI) go through it.

**Evidence** (local slim jar, flat world, `/say` written to stdin every 0.25 s from process start
until `Done`): the 2026-10-07 jar threw on 97 of 109 lines, with the stack above; the fixed jar
ran 37 of 37 with no exception, all on the first tick after `Done`, then stopped cleanly (exit 0).
The bug predates Build 47: the Build 45c release jar fails the same probe. The main CI boot step
now writes `say SOURBY_EARLY_CONSOLE_PROBE` at launch and fails if it threw or did not run (checked
locally through a FIFO as CI does: 45c fails, the fixed jar passes).

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
3. **The remaining workload**: AI stress, which needs clients. The plugin-heavy representative
   set was decided on 2026-10-07 (`plugins-10`, `build-data/representative-plugins.json`); it still
   needs a local harness run with clients.
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
