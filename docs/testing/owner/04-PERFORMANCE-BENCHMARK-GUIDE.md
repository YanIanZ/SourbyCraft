# Performance Benchmark Guide (Build 50)

This guide describes how the owner produces a reproducible, honest measurement. It contains no
performance numbers: any number in a release note must come from a run that follows this procedure and
is archived with its evidence.

Governing rules: `docs/DEVELOPMENT.md` §3 (claims), §5 (no gains from reduced gameplay work), §6
(stability budget, thread counts), §7 (regression gate). Certification rules:
`docs/architecture/qualification-readiness.md` §6 and `docs/BASELINE.md` (Certification).

## 1. What a measurement needs

| Requirement | Why |
| --- | --- |
| A clean worktree at a known commit | `run_baseline.py` refuses a dirty tree unless `--allow-dirty`; a dirty tree cannot be certified |
| Java 25 | the supported runtime |
| One server jar per run directory | no shared world, config or cache |
| A pre-generated world (`--world`) | terrain generation must not happen inside the window |
| Real clients for `players-*` / `entity-stress` | entity activation is computed around players; headless mobs measure the inactive path |
| A quiet machine (< 10 % foreign CPU) | the only check that catches contention |
| Same JVM flags, heap, GC and config hash in both runs | otherwise the difference is not the change |

Record in the result sheet: build/commit, jar SHA-256, hardware (CPU model, cores, RAM, disk), OS,
Java version, heap/GC flags, `paper-global.yml` region/chunk settings, plugin list, world name,
`aurora.yml` hash, player count, warmup and window.

## 2. Automated capture (preferred)

```bash
# One workload against one jar; isolated server directory under the output path.
python3 scripts/run_baseline.py build/libs/SourbyCraft-slim.jar \
  --workload entity-stress \
  --output build/baselines/r50-entity-ref \
  --warmup 120 --duration 600 \
  --heap-mib 6144 --gc G1GC \
  --jfr-settings profile \
  --world /path/to/a/copy/of/the/test/world \
  --plugins-dir /path/to/plugins-10 \
  --connected-players 10
```

- `--workload` choices: `idle`, `players-10`, `players-50`, `players-100`, `plugins-10`,
  `entity-stress`, `chunk-stress`, `save-stress`, `network-stress`, `all`.
- `--connected-players N` asserts that N real clients are attached; without it a players/entity
  workload is recorded as uncertified.
- `--cache-from <previous run>` reuses the bootstrap cache, so a cold download does not look like a
  startup change.
- `--allow-dirty` and `--allow-shared-machine` exist for exploratory runs only; both produce an
  uncertified result. Never present such a run as a release measurement.
- `--jfr-settings profile` records the deeper JFR configuration used for hotspot ranking.

Repeat every workload twice on the reference and twice on the candidate; the certification rule
requires comparability, and a single run cannot show noise.

## 3. Compare

```bash
python3 scripts/compare_baseline.py \
  build/baselines/r50-entity-ref \
  build/baselines/r50-entity-candidate \
  --threshold 0.03 \
  --output build/baselines/r50-entity-compare.md
```

- The default gate is 3 %; `compare_baseline.py` exits non-zero when a gated metric regresses past it
  and refuses to compare uncertified inputs unless told otherwise.
- Gated metrics include MSPT avg/p50/p95/p99/max, TPS avg, CPU, allocation, GC count/pause, RSS and
  heap-after-GC, and network round-trips.
- A regression between 3 % and 5 % must be explained in writing; above 5 % needs an owner
  justification; above 10 % blocks the change unless it fixes a correctness or security defect
  (`docs/DEVELOPMENT.md` §7). Do not hide a regression by changing the workload.

## 4. Hotspots

```bash
python3 scripts/rank_hotspots.py build/baselines/r50-entity-ref/profile.jfr \
  --limit 40 \
  --output build/baselines/r50-entity-hotspots.md
```

Read the caveats the script prints: rankings are estimates, and a workload without connected clients
does not exercise mob AI. Use a ranking to choose the next candidate, never as proof that a change
helps.

## 5. Manual profiling

When a scripted run is impractical (for example an interactive bug), use the same jar and world and
record the same provenance:

```bash
java -Xms6G -Xmx6G -XX:+UseG1GC -XX:+AlwaysPreTouch \
  -XX:StartFlightRecording=settings=profile,filename=run.jfr,dumponexit=true \
  -jar build/libs/SourbyCraft-slim.jar --nogui
```

Then, after `Done (` and a warmup of at least two minutes:

```
/spark profiler start --timeout 120
/spark profiler stop
```

Console/terminal:

```bash
python3 scripts/rank_hotspots.py run.jfr --output hotspots.md
```

`/perf` views that matter for a run (`sourbycraft.command.perf`):

| View | Shows |
| --- | --- |
| `/perf` / `/perf overview` | one-screen summary |
| `/perf tick` | TPS/MSPT and tick distribution |
| `/perf cpu`, `/perf memory`, `/perf gc` | process CPU, heap, GC pauses |
| `/perf lanes`, `/perf governor` | Resource Governor lanes, queue and rejection |
| `/perf async` | async pathfinding admitted/refused/outstanding and timings |
| `/perf chunks` | chunk generation stages (only with metrics enabled) |
| `/perf network` | wire bytes/packets, connections, AbyssLink |
| `/perf awf` | AWF storages, commits, pending work |
| `/perf region [<world> <x> <z>]` | per-region tick data |
| `/perf plugins` | per-plugin task and bridge state |
| `/perf incidents`, `/perf history` | recent stalls and rolling history |

`/sourbycraft status` shows the boot stages and bridge mode; `/sourbycraft config` shows which keys
are LIVE versus RESTART_REQUIRED and which changed since boot.

## 6. Interpreting noise

- A 3 % difference on one 10-minute window is usually noise unless the run was certified on a quiet
  machine and repeated.
- Compare like with like: same world seed/state, same player count and activity, same view distance,
  same plugins, same heap and GC.
- Watch `foreign_fraction` in `baseline.json`. A non-zero sustained value means another process was
  using the machine; the run is not evidence.
- Do not mix a cold-start run with a warm run, or a run with terrain generation with one without.
- If two runs disagree by more than the gate on an unchanged jar, stop and fix the environment before
  measuring the change.

## 7. Evidence to archive

Per run directory (`build/baselines/<name>/`):

- `baseline.json` — provenance, workload, samples, certification state;
- `profile.jfr` and the hotspot report;
- `server.log` and the config files actually used;
- the comparison report (`compare_baseline.py --output`);
- a filled `06-RESULTS-TEMPLATE.md` sheet.

Keep the reference and candidate directories together, plus the exact jar SHA-256. A report without
those is not reusable.

## 8. Build 50 surfaces worth measuring

| Surface | Workload | What to watch |
| --- | --- | --- |
| Async pathfinding ON vs OFF | `entity-stress` with real clients + APF-002 workload | MSPT p95/p99, `/perf async` admitted/refused, mob behaviour |
| Redis backend vs FILE | `save-stress` | MSPT, commit duration, `/perf awf` |
| Fabric + shared STORAGE pool | `save-stress` with AWF Redis | pool in-use, timeouts, shutdown time |
| MultiProfile switch cost | scripted switches (see `03` §MP-002) | switch duration, storage lane queue, tick impact |
| Startup cache | `idle` cold vs warm | time to `Done (`, startup-index line |
| Chunk metrics | `chunk-stress` with metrics on | stage p95/p99, MSPT |

Every one of these is `OWNER TESTING REQUIRED` until the owner runs it; source inspection is not a
measurement.
