# Aurora Engine Owner Acceptance Plan — Build 56–100

**Status: proposed owner-run cases, no tests run, no PASS claimed.** This complements [Tester Owner](../../tester%20owner.md) and [testing index](../../testing/owner/00-TESTING-INDEX.md), not a substitute for current Build 50 release qualification.

Each result must record case, candidate commit SHA, toolchain hash, JVM/GC flags, hardware, exact plugin set, world seed/backup, actions, expected/observed behavior, metrics/logs, PASS/FAIL/BLOCKED and owner decision. Results from a previous SHA are NEEDS RETEST if relevant code changed.

## Gate matrix

| Case | Scenario | Required result |
|---|---|---|
| AE-001 | Clean JDK 25 build with pinned private toolchain | patch application, server/API compile and smoke boot at exact SHA |
| AE-002 | Existing Build 50 regression suite | affected mandatory safety gates pass |
| AE-003 | Strict region ownership wrong-thread plugin call | safe refusal and useful trace; no state mutation |
| AE-004 | Entity changes owner during async path computation | stale result discarded; no cross-region mutation |
| AE-005 | Region merge/split while scheduled callbacks pending | exactly intended callbacks; no lost/duplicate tick work |
| AE-006 | Offline/unloaded world while async work outstanding | cancellation/error, no unintended reload |
| AE-007 | Chunk generation and rapid player flight | no orphan tickets, missing chunks or unbounded queue |
| AE-008 | Chunk unload/save under heavy I/O | last confirmed chunk/entity/POI/ticks recoverable |
| AE-009 | 10 and 50 simulated moving players on same seed | p50/p95/p99 comparable against certified baseline |
| AE-010 | Entity AI/pathfinding stress and queue saturation | no starvation, excessive stalls or wrong-owner recompute |
| AE-011 | Network packet stress with ProtocolLib/PacketEvents | no class/protocol regression; bounded memory and queue |
| AE-012 | Legacy plugin bridge safe vs relaxed modes | limitations explicit; no unsupported correctness claim |
| AE-013 | Resource Governor queue rejection | reject safely, never CallerRuns on owner thread |
| AE-014 | AWF Redis write, unknown reply, shutdown | no FALSE SUCCESS; recover from last confirmed journal |
| AE-015 | MultiProfile A->B->A, disconnect during switch | no item dupe/leak, valid recovery |
| AE-016 | Disk full / Redis down / lost durable acknowledgement | fail closed, no silent state loss |
| AE-017 | Partial startup fails on one required service | correct FAILED/DEGRADED state and cleanup |
| AE-018 | Repeated shutdown/restart under queued work | bounded shutdown, no executor leak or double close |
| AE-019 | Plugin API ABI and event ordering | supported plugin behavior preserved |
| AE-020 | Source/patch coupling inventory and rebuild audit | no unauthorized scheduler-internal dependency creep |
| AE-021 | Memory load and 2-hour soak | no unexplained RSS/heap increase, crash or deadlock |
| AE-022 | Canary disable/rollback with saved data | returns to last qualified build safely |
| AE-023 | 26.3/Build 100 transition (separate run) | migration, plugin and per-world save gates pass on exact 26.3 candidate |

## Qualification commands (run by owner after private toolchain ready)

~~~bash
python3 scripts/patch_surface.py --check-rebuild
python3 scripts/independence_policy.py
python3 -m unittest discover -s scripts -p 'test_*.py'
./gradlew applyAllPatches --no-daemon
./gradlew :sourbyapi:test :sourbycraft-server:test --no-daemon
# Existing benchmark harness. Use the documented workload and pinned world.
python3 scripts/run_baseline.py build/libs/SourbyCraft-slim.jar \
  --workload players-50 --output build/baselines/aurora-candidate-players-50 \
  --warmup 120 --duration 600 --heap-mib 6144
~~~

The above are candidate instructions, not assertions that they were run or that all invocations work on every unmaterialized checkout. Follow [BASELINE.md](../../BASELINE.md) for other workloads, certifications and comparison.

## Blocker classification

**P0:** world/profile data loss, inventory dupe, unauthorized thread mutation, uncertain commit reported as success, deadlock that stops server, privilege or auth bypass. Production release blocked.

**P1:** unexplained p99 severe regression, unsupported plugin compatibility regression, runaway queue, storage outage with inconsistent rollback. Requires maintainer decision and fix or explicit rollback.

**P2:** diagnostic/UX issue without data/security impact. Needs tracked action and truthful release note.

Do not mark STABLE solely from “all unit tests green.” Real-client, fault injection, recovery and repeatable performance evidence is required for whichever subsystems were changed.

## Owner sign-off template

~~~text
Build/SHA:
Minecraft/Paper pin / SourbyPatcher hash:
Test plan cases:
Host CPU/RAM/JDK/GC:
Plugins/versions/hash:
World seed/data clone / players:
Before/after p95/p99 MSPT, CPU, allocation, GC:
State recovery / snapshot evidence:
Failures and unresolved risk:
Verdict: PASS / FAIL / BLOCKED / NEEDS RETEST
Owner approval/date:
~~~

All cases are **OPEN** for this RFC.
