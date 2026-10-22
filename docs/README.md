# SourbyCraft documentation

Start here. The first section is the order a new developer should read and do things in; the second
is an index of every active document. Role and status of each Aurora document (Active, Plan,
Proposal, Historical) and the known contradictions between documents are kept in one place:
the document map in [architecture/AURORA.md](architecture/AURORA.md). Where a document and the code
disagree, the code wins ([DEVELOPMENT.md](DEVELOPMENT.md), truthfulness policy).

## Developer path

1. **Orientation.** [README.md](../README.md) for what the project is, then
   [AGENTS.md](../AGENTS.md) for the build, the patch model and the rules that are easy to miss.
   Read the truthfulness policy in [DEVELOPMENT.md](DEVELOPMENT.md) before writing any status,
   benchmark or release text.
2. **Set up the toolchain.** JDK 25 and Python 3.11+. The private SourbyPatcher/SourbyClip tools
   must be in Maven Local before Gradle configures:
   [development/PRIVATE-TOOLCHAIN.md](development/PRIVATE-TOOLCHAIN.md).
   ```sh
   python3 scripts/private_toolchain.py --publish .private-toolchain
   bash scripts/setup_metal.sh
   ./gradlew applyAllPatches
   ./gradlew :sourbycraft-server:compileJava
   ```
3. **Learn the patch workflow.** Change Minecraft/Paper code in the materialized repo, commit
   there, run the matching `rebuild*Patches` task and commit the patch files (AGENTS.md, *Patch
   application*). The current patch set and what each patch is for:
   [architecture/patch-classification.md](architecture/patch-classification.md); parked patches:
   `sourbycraft-server/minecraft-patches/parked/README.md`. Bumping `paperRef`:
   [development/UPGRADING-MINECRAFT.md](development/UPGRADING-MINECRAFT.md).
4. **Understand the architecture.** [architecture/AURORA.md](architecture/AURORA.md) (entry point,
   verified current state), then [architecture/aurora-engine-package.md](architecture/aurora-engine-package.md)
   (where code lives) and [architecture/execution-contract.md](architecture/execution-contract.md)
   (region ownership and hand-off). Then the subsystem you are touching (index below).
5. **Configuration.** [AURORA-CONFIG.md](AURORA-CONFIG.md) lists every implemented key with its
   default and whether it is LIVE or RESTART_REQUIRED.
6. **Test.** Gradle and Python suites (commands in [AGENTS.md](../AGENTS.md#running-tests)); AWF and
   legacy-plugin guides under [guides/](guides/). Performance claims need the evidence rules in
   [BASELINE.md](BASELINE.md) and [PROFILING.md](PROFILING.md).
7. **Track and release.** Priorities live only in [TODO.md](TODO.md). A build is declared STABLE only after the gates in its release doc under
   [releases/](releases/) pass; the owner decides the channel (Build 47 ships `release=true`
   without the STABLE label, see [ROADMAP-47-50.md](releases/ROADMAP-47-50.md)).

## Index

### Project direction
| Document | About |
|---|---|
| [DEVELOPMENT.md](DEVELOPMENT.md) | Active development contract, governing rules, testing matrix |
| [AURORA-ROADMAP.md](AURORA-ROADMAP.md) · [AURORA-DEVELOPMENT.md](AURORA-DEVELOPMENT.md) | Aurora plans (plan role: checkboxes are snapshots) |

### Architecture (`architecture/`)
| Area | Documents |
|---|---|
| Entry point | [AURORA.md](architecture/AURORA.md), [aurora-engine-package.md](architecture/aurora-engine-package.md), [AURORA-ENGINE-SYSTEM.md](architecture/AURORA-ENGINE-SYSTEM.md), [AURORA-INDEPENDENT-ENGINE.md](architecture/AURORA-INDEPENDENT-ENGINE.md) |
| Execution & threading | [execution-contract.md](architecture/execution-contract.md), [aurora-resource-governor.md](architecture/aurora-resource-governor.md), [executors.md](architecture/executors.md), [executor-inventory.md](architecture/executor-inventory.md), [threading.md](architecture/threading.md), [region-parallelism.md](architecture/region-parallelism.md), [chunk-workers.md](architecture/chunk-workers.md), [region-io-audit.md](architecture/region-io-audit.md) |
| Engine ownership | [engine-chunk-world.md](architecture/engine-chunk-world.md), [engine-entity-ai.md](architecture/engine-entity-ai.md), [engine-network.md](architecture/engine-network.md), [network-allocation-copy-audit.md](architecture/network-allocation-copy-audit.md), [engine-storage.md](architecture/engine-storage.md) |
| World storage (AWF) | [aurora-world-fabric.md](architecture/aurora-world-fabric.md) |
| Plugin compatibility | [aurora-plugin-bridge.md](architecture/aurora-plugin-bridge.md), [compat-boundary.md](architecture/compat-boundary.md), [compat-vs-performance.md](architecture/compat-vs-performance.md) |
| Startup & bootstrap | [aurora-instant-startup.md](architecture/aurora-instant-startup.md), [bootstrap-download-audit.md](architecture/bootstrap-download-audit.md), [slim-jar-bootstrap-failure.md](architecture/slim-jar-bootstrap-failure.md), [BOOTSTRAP.md](BOOTSTRAP.md), [CDS.md](CDS.md) |
| Independence & patches | [independence.md](architecture/independence.md), [dependency-ledger.md](architecture/dependency-ledger.md), [patch-classification.md](architecture/patch-classification.md), [patches.md](architecture/patches.md), [rebase-log.md](architecture/rebase-log.md) |
| Audits & reviews | [reuse-audit.md](architecture/reuse-audit.md), [cache-inventory.md](architecture/cache-inventory.md), [hotspots-entity-ai.md](architecture/hotspots-entity-ai.md), [region-safety-review-0017-0019.md](architecture/region-safety-review-0017-0019.md), [aurora-task-d-validation.md](architecture/aurora-task-d-validation.md), [qualification-readiness.md](architecture/qualification-readiness.md) |

### Operations, config and observability
[AURORA-CONFIG.md](AURORA-CONFIG.md) (config contract; samples in [config-examples/](config-examples/)) ·
[AURORA-UX.md](AURORA-UX.md) (console, `/aurora`, `/perf`) · [SPARK.md](SPARK.md) ·
[PROFILING.md](PROFILING.md) · [BASELINE.md](BASELINE.md) · [SOURBYMODS.md](SOURBYMODS.md) ·
[async-pathfinding-spec.md](async-pathfinding-spec.md)

### Development notes (`development/`)
Focused, current write-ups for one change or subsystem: [PRIVATE-TOOLCHAIN.md](development/PRIVATE-TOOLCHAIN.md),
[UPGRADING-MINECRAFT.md](development/UPGRADING-MINECRAFT.md), [PROTOCOLLIB.md](development/PROTOCOLLIB.md), [AUTO-UPDATER.md](development/AUTO-UPDATER.md),
[REGION-WATCHDOG.md](development/REGION-WATCHDOG.md), [STALL-RECORDER.md](development/STALL-RECORDER.md), [CHUNK-GENERATION-STUTTER.md](development/CHUNK-GENERATION-STUTTER.md),
[AURORA-EDF-UPGRADE.md](development/AURORA-EDF-UPGRADE.md), [ASP-26.2-AWF-PORT-REVIEW.md](development/ASP-26.2-AWF-PORT-REVIEW.md),
[LIGHTINGLUMINOL-BRIDGE-PORT.md](development/LIGHTINGLUMINOL-BRIDGE-PORT.md), [SUPERIORSKYBLOCK-BRIDGE.md](development/SUPERIORSKYBLOCK-BRIDGE.md), [paperlib-compat.md](development/paperlib-compat.md),
[LOGIN-GUARDS.md](development/LOGIN-GUARDS.md) (Paper login guards the Folia baseline drops or re-purposes; documentation only),
[TEST-COVERAGE-GAPS.md](development/TEST-COVERAGE-GAPS.md) (prioritised list of missing unit tests and timing-sensitive tests to convert).
[REMOTE-REPOSITORIES.md](development/REMOTE-REPOSITORIES.md) (every remote the build, CI and first boot reach, fallback order, offline behaviour).
[AGENT-TEST-SERVER.md](development/AGENT-TEST-SERVER.md) (the only sanctioned way for an agent to boot a server: isolated, time-limited, one at a time; functional evidence, not a baseline).
[CONFIG-FILE-RENAME-PLAN.md](development/CONFIG-FILE-RENAME-PLAN.md) (PROPOSED plan to rename `canvas-server.yml`/`canvas-worlds.yml` without breaking existing servers; nothing implemented).
[scratch-reentrancy-proof.md](architecture/scratch-reentrancy-proof.md) (DEV-A: every mutable scratch field the feature patches add, its re-entry paths and verdict).
[async-path-snapshot-audit.md](architecture/async-path-snapshot-audit.md) (DEV-E: every region member an off-thread path solve reads, vanilla vs `SnapshotPathRegion` answer, verdict per member; source audit, no server run).
[PLUGIN-NETTY-GUIDANCE.md](development/PLUGIN-NETTY-GUIDANCE.md) (for plugin authors and operators: which events run on a Netty event loop, why blocking there hurts, what to do instead; from a source audit, not a benchmark).

### Guides (`guides/`)
[developing-awf.md](guides/developing-awf.md) (plugin API) · [testing-awf.md](guides/testing-awf.md) ·
[testing-legacy-plugins.md](guides/testing-legacy-plugins.md) ·
[region-threaded-commands.md](guides/region-threaded-commands.md) ·
[datapack-functions.md](architecture/datapack-functions.md) ·
[wandering-traders.md](architecture/wandering-traders.md)

### Audit handoff
Handoff of the 2026-10-14 audit fix work: [development/HANDOFF-2026-10-14.md](development/HANDOFF-2026-10-14.md);
fix status is tracked in [TODO.md](TODO.md) *Audit sistem 2026-10-14*.

Internal planning documents (requirements, specification, task matrices, transition plan, dated
audits) are kept by the maintainers outside this repository; references to them elsewhere in the
docs (for example `SPEC.md` V/B numbers or `AURORA-TASKS.md` lines) point there.

### Releases (`releases/`)
[Release roadmap 47–50](releases/ROADMAP-47-50.md) (owner plan 2026-10-21: 47 ship, 48 refinement, 49 full stable, 50 optimization; gates in [48](releases/26.2-build-48.md), [49](releases/26.2-build-49.md), [50](releases/26.2-build-50.md)) ·
[Build 47 — Aurora Nexus](releases/26.2-build-47-aurora-nexus.md) (published release and its gates) ·
[Build 48](releases/26.2-build-48.md) (current development build) ·
[Build 49 real-test runbook](releases/runbooks/build-49-real-test.md) (human tester and operator handover) ·
[46](releases/46.md) · [45](releases/45.md) · [44](releases/44.md)

Dated plans and specs from the v6–v12, UniverseSpigot and Folia-base eras (May–September 2026,
formerly `docs/superpowers/`) were removed on 2026-10-13; recover them from git history if needed.
