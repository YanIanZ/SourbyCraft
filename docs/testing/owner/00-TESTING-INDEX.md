# Owner Testing Index — SourbyCraft 26.2 Build 50

This is the dashboard for tests only the project owner runs. The coding agent does **not** execute
tests, builds, benchmarks, server boots or failure injection: every status below is a handoff state,
never a claim that something passed.

Owner testing starts with **data safety** (AWF, MultiProfile, Redis), then entity/region safety
(async pathfinding), then runtime correctness (bridge, caches, governor), and only then performance.

## How to use this directory

| Document | Use it for |
| --- | --- |
| `00-TESTING-INDEX.md` | What to test, priority, status, and where the procedure lives |
| `01-AWF-TESTING.md` | Aurora World Fabric: persistence, templates, copy-on-write, unload, shutdown |
| `02-ASYNC-PATHFINDING-TESTING.md` | Async pathfinding: baseline, workload that triggers solves, behaviour, saturation |
| `03-EXPERIMENTAL-FEATURES-TESTING.md` | MultiProfile, Redis Fabric, Bridge, Governor, startup cache, metrics, AbyssLink, updater |
| `04-PERFORMANCE-BENCHMARK-GUIDE.md` | Reproducible before/after measurement and evidence |
| `05-FAILURE-RECOVERY-TESTING.md` | Destructive tests: crash, Redis loss/freeze, corrupted data, interrupted shutdown |
| `06-RESULTS-TEMPLATE.md` | One result sheet per test, plus a session summary table |
| `07-BUG-REPORT-TEMPLATE.md` | Sending a failure back to a coding agent |

## Status labels

- **NOT STARTED** — no run yet.
- **READY FOR OWNER TEST** — implemented and documented, waiting for the owner.
- **IN PROGRESS** — the owner has started.
- **PASSED** — the owner observed every expected result and kept evidence.
- **FAILED** — the owner observed a failure; file it with `07-BUG-REPORT-TEMPLATE.md`.
- **BLOCKED** — cannot be run with the current environment (for example one machine only).
- **NEEDS RETEST** — a fix landed after the last run; the old result no longer applies.

Owner report 2026-10-24: AWF and MultiProfile testing is complete. Those rows are marked
`PASSED (owner report)`; the result sheets and raw evidence still need to be attached, and an agent
run is still not an owner result. Redis Fabric, async pathfinding and the remaining surfaces are
unchanged.

## Environment every test assumes

- Java 25 (Temurin). `java -version` must report 25.
- A disposable server directory with its own `sourbycraft_config/`, `world/` and `awf/` folders.
- The jar built from the commit under test; record `Build`, commit and SHA-256 in the result sheet
  (`06-RESULTS-TEMPLATE.md`).
- For Redis tests: `redis-server` 7+ reachable only from the test host, a dedicated database or key
  prefix, and AOF enabled if the test asserts durability.
- For MultiProfile: an operator account; no other plugin is needed because Build 50 adds admin
  subcommands (`create`, `switch`) to `/awf multiprofile`.
- Never run a destructive case (`05-FAILURE-RECOVERY-TESTING.md`) against production data.

Build commands the owner may use (the agent did not run them):

```bash
./gradlew applyAllPatches
./gradlew :sourbycraft-server:compileJava
./gradlew slimServerJar -PsourbyBuild=50
java -Xmx2G -XX:+UseG1GC -jar build/libs/SourbyCraft-slim.jar --nogui
```

A published jar runs the same way: `java -Xmx2G -XX:+UseG1GC -jar release/SourbyCraft-26.2-REL.jar --nogui`.
Wait for `Done (` before starting.

The jar's Release row is the fastest way to confirm what you are testing: a Build 50 prerelease shows
`Build 50@<short-commit>PR!` in red; a normal release shows `Build 50 — Aurora Nexus` in green; a
development build shows `(DEV|EXP, branch@commit)` in amber. If the row does not match the build you
think you have, stop and rebuild.

## Dashboard

Priority: **P0** data/region safety, **P1** runtime correctness, **P2** performance.

### Aurora World Fabric (P0)

| ID | Feature | Flag / config | Risk | Procedure | Status |
| --- | --- | --- | --- | --- | --- |
| AWF-001 | Create, save, restart, load a persistent world | `aurora.awf.worlds`, `aurora.awf.backend: file` | Low on disposable server | `01` §AWF-001 | PASSED (owner report) |
| AWF-002 | Template + copy-on-write instance isolation | `aurora.awf.backend: file` | High (data isolation) | `01` §AWF-002 | PASSED (owner report) |
| AWF-003 | Redis backend, lease, second-server refusal | `aurora.awf.backend: redis`, `aurora.awf.redis.*` | High | `01` §AWF-003 | PASSED (owner report) |
| AWF-004 | Unload with players present; `nosave` | — | High | `01` §AWF-004 | PASSED (owner report) |
| AWF-005 | Delete world and template | — | High (irreversible) | `01` §AWF-005 | PASSED (owner report) |
| AWF-006 | `/awf save` durability immediately after completion | — | High | `01` §AWF-006 | PASSED (owner report) |
| AWF-007 | Clean stop with dirty chunks (exit 0, `AWF-SHUTDOWN-OK`) | `aurora.awf.shutdown-deadline-ms` | High | `01` §AWF-007 | PASSED (owner report) |
| AWF-008 | Concurrent create/load/unload/delete | — | Medium | `01` §AWF-008 | PASSED (owner report) |
| AWF-009 | Missing template is refused (Build 50 fix) | — | High | `01` §AWF-009 | PASSED (owner report) |
| AWF-010 | Hand-edited registry is rejected safely (Build 50 fix) | `awf/aurora-worlds.json` | Medium | `01` §AWF-010 | PASSED (owner report) |
| AWF-011 | World properties and clone | `/awf set`, `/awf clone` | Medium | `01` §AWF-011 | PASSED (owner report) |

### AWF MultiProfile (P0, disabled by default)

| ID | Feature | Flag / config | Risk | Procedure | Status |
| --- | --- | --- | --- | --- | --- |
| MP-001 | Enable, create, list, inspect a profile | `aurora.awf.multiprofile.enabled: true` | Medium | `03` §MP-001 | PASSED (owner report) |
| MP-002 | Switch profile and verify isolation categories | `...isolation.*` | High (item loss) | `03` §MP-002 | PASSED (owner report) |
| MP-003 | Reconnect after a switch (Build 50 fix: no false duplicate kick) | `...coordination.single-active-session` | High | `03` §MP-003 | PASSED (owner report) |
| MP-004 | Save on quit and on explicit `saveProfile` | — | High | `03` §MP-004 | PASSED (owner report) |
| MP-005 | Switch timeout rolls back | `...switching.timeout-ms` | Medium | `03` §MP-005 | PASSED (owner report) |
| MP-006 | Recovery of an interrupted switch | `...switching.require-durable-save` | High | `03` §MP-006 | PASSED (owner report) |
| MP-007 | Idle world unload | `...worlds.idle-unload-seconds` | Medium | `03` §MP-007 | BLOCKED (no public world binding) |
| MP-008 | Disabled feature creates nothing | `...enabled: false` | Low | `03` §MP-008 | PASSED (owner report) |
| MP-009 | Distributed mode refuses without fabric/fencing | `...mode: distributed` | Medium | `03` §MP-009 | PASSED (owner report) |
| MP-010 | Two-node ownership and takeover | fabric + `coordination.provider: redis` | High | `03` §MP-010 | BLOCKED (needs two nodes) |

### Aurora Redis Fabric (P1, disabled by default)

| ID | Feature | Flag / config | Risk | Procedure | Status |
| --- | --- | --- | --- | --- | --- |
| RF-001 | Start, reachability probe, `/awf redis status` | `aurora.awf.redis.fabric.enabled: true` | Medium | `03` §RF-001 | READY FOR OWNER TEST |
| RF-002 | Pool isolation and `pools` view | `...pools.*` | Medium | `03` §RF-002 | READY FOR OWNER TEST |
| RF-003 | Fabric + AWF Redis store share the STORAGE pool | `...pools.storage` | High | `03` §RF-003 | READY FOR OWNER TEST |
| RF-004 | Shutdown ordering: last AWF commit succeeds (Build 50 fix) | — | High | `03` §RF-004 | READY FOR OWNER TEST |
| RF-005 | Events service off by default; enabling without a consumer backs up | `...services.events` | Medium | `03` §RF-005 | READY FOR OWNER TEST |
| RF-006 | Invalid Redis URI does not abort world load (Build 50 fix) | `aurora.awf.redis.uri` | Medium | `03` §RF-006 | READY FOR OWNER TEST |

### Async pathfinding (P0 region safety, P2 performance)

| ID | Feature | Flag / config | Risk | Procedure | Status |
| --- | --- | --- | --- | --- | --- |
| APF-001 | Feature OFF baseline | `aurora.entity.async-pathfinding: false` | Low | `02` §APF-001 | READY FOR OWNER TEST |
| APF-002 | Feature ON with a workload that actually submits solves | `...: true` | Medium | `02` §APF-002 | READY FOR OWNER TEST |
| APF-003 | Mob matrix: zombie, villager, bee, aquatic, creaking, doors, water/lava | `...: true` | Medium | `02` §APF-003 | READY FOR OWNER TEST |
| APF-004 | Target changes, region transitions, entity removal | `...: true` | High (region safety) | `02` §APF-004 | READY FOR OWNER TEST |
| APF-005 | Worker saturation and shutdown | `...: true` | Medium | `02` §APF-005 | READY FOR OWNER TEST |

### Other experimental surfaces (P1)

| ID | Feature | Flag / config | Risk | Procedure | Status |
| --- | --- | --- | --- | --- | --- |
| BR-001 | Bridge admission, quarantine, guard severity | `aurora.bridge.mode`, `region-scheduler.guard-severity` | High | `03` §BR-001 | READY FOR OWNER TEST |
| BR-002 | Bridged plugin task limits | `aurora.bridge.max-pending-tasks-per-plugin` | Medium | `03` §BR-002 | READY FOR OWNER TEST |
| RG-001 | Resource Governor lanes and `/perf governor` | `aurora.scheduler.*` | Medium | `03` §RG-001 | READY FOR OWNER TEST |
| SC-001 | Startup index cold/warm start | `-Daurora.startup.cache=false` | Low | `03` §SC-001 | READY FOR OWNER TEST |
| CH-001 | Chunk generation metrics | `aurora.diagnostics.chunk-generation-metrics` | Low | `03` §CH-001 | READY FOR OWNER TEST |
| CH-002 | Region/chunk thread sizing and ledger | `config/paper-global.yml` | Medium | `03` §CH-002 | READY FOR OWNER TEST |
| NW-001 | Network counters | `aurora.network.counters` | Low | `03` §NW-001 | READY FOR OWNER TEST |
| AB-001 | AbyssLink disabled path | `aurora.abysslink.enabled: false` | Low | `03` §AB-001 | READY FOR OWNER TEST |
| AB-002 | AbyssLink enabled against a test peer | `aurora.abysslink.*` | Medium | `03` §AB-002 | BLOCKED (needs a Leviathan test peer) |
| SM-001 | SourbyMod (ML1): load, reject, isolate | `mods/*.jar` with `sourbymod.yml` | Medium | `03` §SM-001 | READY FOR OWNER TEST |
| UP-001 | Auto-updater notify mode | `updater.apply-mode: notify` | Low | `03` §UP-001 | READY FOR OWNER TEST |

## Highest-risk Build 50 changes (owner reported tested 2026-10-24)

These were changed after the last Build 49 tag. The owner reported AWF/MultiProfile testing complete on
2026-10-24; rerun the relevant row after any new code change to these areas:

1. AWF shutdown ordering with the Redis fabric (RF-004) — a regression here loses the last commit
   interval on every clean stop.
2. MultiProfile session claims (MP-003) and save/switch serialisation (MP-004) — a regression here
   either kicks a reconnecting player or stores the wrong profile's state.
3. Missing-template refusal (AWF-009) and registry validation (AWF-010) — these change what a broken
   setup does, from "silently empty world" to "loud refusal".
4. Async pathfinding is still OFF by default; nothing ships enabled that was not enabled in Build 49.

## Known limitations recorded instead of hidden

- `aurora.awf.multiprofile` has no public world-binding API yet: a profile cannot be bound to an AWF
  template through the API in Build 50. `getProfileWorlds` returns nothing unless a consumer wrote a
  binding through internal code.
- Distributed MultiProfile is **experimental and unqualified**; only two-node owner testing can
  change that. `RF-006`/`MP-009` do not qualify it.
- The AWF event stream has no consumer in this build; `services.events` is off by default.
- `/awf save` returns when the save was scheduled, not necessarily when every chunk reached the
  store (AWF-006 measures this).
- AWF `shutdown-deadline-ms` starts before the vanilla world save, so a save longer than the deadline
  reports an unconfirmed storage (AWF-007 measures the boundary).
