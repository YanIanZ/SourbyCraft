# Metal Performance & Correctness Qualification — Owner Runbook

**Status:** proposed acceptance plan. No benchmark, unit test, server boot or fault injection was executed in this docs/code PR. Owner owns real-server/manual testing; the Java regression fixture is supplied for the owner to run.

## Evidence principle

A 15% microbenchmark win does not imply 15% improvement in server TPS. Only compare exact SHAs with the same JVM, CPU, region configuration, RAM, heap, GC flags, world seed, plugin list, datapacks, network setup, player locations and load pattern. TPS at 20 is saturated; prioritize **MSPT p95/p99**, scheduler queue wait, chunk I/O tails, allocation and GC for claims of increased capacity.

## Baseline matrix

| Baseline | Build selection | Test scope |
|---|---|---|
| BASE-UPSTREAM | no `metalProvider` or `upstream` | production-compatible reference, LeafPile monolith 1.2.0 |
| CAND-MODULAR | `-PmetalProvider=modular` | latest pinned released modules (initially 1.3.2), binary/behavior check |
| CAND-VENDORED | `-PmetalProvider=vendored` | source-owned Metal and generated map classes |
| FOLLOW-UP | exact future candidate SHA | one semantic change per A/B when possible |

Pin all dependency and plugin versions; version name alone is insufficient, record JAR SHA-256 and loaded class origin.

## Proposed owner command sheet (after private toolchain setup)

~~~bash
# Existing required toolchain setup
python3 scripts/private_toolchain.py --publish .private-toolchain
bash scripts/setup_metal.sh

# Dedicated Metal codegen + regression
./gradlew :Metal:compileJava :Metal:test --no-daemon

# Materialize source tree for canary compile
./gradlew applyAllPatches --no-daemon

# Three mutually-exclusive server candidates
./gradlew -PmetalProvider=upstream :sourbycraft-server:compileJava --no-daemon
./gradlew -PmetalProvider=modular :sourbycraft-server:compileJava --no-daemon
./gradlew -PmetalProvider=vendored :sourbycraft-server:compileJava --no-daemon

# Dependency reports: confirm one class provider
./gradlew -PmetalProvider=modular :sourbycraft-server:dependencies --configuration runtimeClasspath
./gradlew -PmetalProvider=vendored :sourbycraft-server:dependencies --configuration runtimeClasspath

# For runtime loaded classes, start a disposable server with Java 25:
# java -Xlog:class+load=info -jar <candidate.jar> --nogui
# Capture loaded class origin, do not paste secrets into a public issue.
~~~

**Note:** the code-generation Gradle task depends on the exact build patch set. Any failed code generation, class collision, missing module or patch mismatch is a BLOCKER; do not work around by disabling a guard.

## Correctness tests

| ID | Scenario | Acceptance |
|---|---|---|
| MET-001 | generate 16 specializations twice | same output hashes, no partial file |
| MET-002 | SWMR map equals/hash contract | equal maps same hash, unequal maps differ, negative keys |
| MET-003 | concurrent readers / single writer on SWMR | behavior matches documented single-writer contract |
| MET-004 | multiwriter map compute, resize, iterator behavior | no deadlock, invalid update or stale read beyond documented semantics |
| MET-005 | Folia region merge/split with chunk load/unload | no lost tasks, duplicate tick, world corruption |
| MET-006 | file/world save + power-loss/kill injection | last confirmed data recoverable |
| MET-007 | MultiProfile A->B->A plus async callbacks | no data leak or duped items |
| MET-008 | compare three classpaths and loaded class origins | no unplanned duplicate ca.spottedleaf classes |
| MET-009 | plugin compatibility / signed chat / packet tracking | no class linkage or client regressions |
| MET-010 | update 1.3.2 -> later *released* tag, compare API | no unreviewed ABI/semantic change |
| MET-011 | memory ceiling, large chunks, high player count | queues and caches bounded |
| MET-012 | JDK 25 and Gradle private patcher reproducibility | deterministic artifact and log evidence |

## Performance benchmarks

Use replayable scenarios with a warmup period and at least three independent steady-state runs per candidate. Collect distributions rather than best-case averages.

1. **idle/world spawn**: baseline tick overhead, classloading, heap and startup.
2. **chunk-stress**: high density chunk load/generation/unload, view-distance transitions and flying players.
3. **entity-stress**: mob counts, AI/pathfinding, tracked entities, passenger movement, player proximity.
4. **redstone-stress**: block updates, hopper networks and fluid interactions within Folia ownership.
5. **plugin-stress**: real installed plugins and legacy bridge conditions; separate SAFE from RELAXED checks.
6. **AWF-stress**: world save/unload, Redis backend with network latency, snapshot recovery and failure.
7. **profile-stress**: concurrent account/profile switches, queue saturation and datastore interruption.
8. **territory-lab (future)**: two nodes with a **qualified** ARF owner/manifest protocol; Metal utilities alone cannot make an ARF test meaningful.

## Metrics and decision formulas

- `MSPT_p99_delta = (candidate_p99 / baseline_p99 - 1) × 100%` (negative is faster).
- `alloc_delta = (candidate_bytes_per_operation / baseline_bytes_per_operation - 1) × 100%`.
- `throughput_ratio = candidate_completed_operations / baseline_completed_operations` under same correctness gates.
- Record player disconnects, failed chunk loads, unrecoverable profile journals, lost entity updates and crash/dupe counters. **Any confirmed P0 correctness failure is a hard rejection** regardless of throughput.
- Record p95/p99 chunk save/load, scheduler queue wait, CPU contention, GC pause p99, heap/direct memory, native thread count, lock contention, generated source/JAR SHA and CPU utilization.

Do **not** claim specific percentage improvements (e.g. 20–50%) without measured A/B results. Set owner-defined acceptance threshold after the baseline; if statistically ambiguous, remain on last-qualified provider.

## Owner result template

~~~text
Case:
Branch/commit SHA:
Build/JDK/GC/Gradle:
Metal provider / upstream tag:
Paper pin / loaded class origin:
Hardware / CPU / OS / RAM:
World seed, plugins, players, timings:
Baseline MSPT p50/p95/p99:
Candidate MSPT p50/p95/p99:
Allocation / GC / IO / queue wait:
Known errors, crash/recovery evidence:
Verdict: PASS / FAIL / BLOCKED / NEEDS RETEST
Owner signature/date:
~~~

## Rollback and safeguards

Never switch providers while the server is running; build-time mode only. Owner halts canary, restores last qualified JAR and verifies world/profile backups before reopening gameplay. No change of UUID, world-data schema or signed profile data is intended. If a later performance PR changes serialized state, it needs a separate backward-compat migration.

Existing Build 50 safety and owner testing requirements remain active; this runbook supplements but does not supersede them.
