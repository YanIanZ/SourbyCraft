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
7. **Track and release.** Priorities and status live only in [TODO.md](TODO.md); per-item Build 50
   detail is in the generated [PM matrix](releases/BUILD-50-PM-MATRIX.md) and the owner's manual
   test steps in [Tester Owner](tester%20owner.md). A build is declared STABLE only after the gates in
   its release doc under [releases/](releases/) pass, and the owner decides the channel and which build
   carries STABLE: Builds 47, 48, 49 and the 49.1 hotfix shipped as normal releases without it, Build 50
   as a prerelease (owner decisions 2026-10-21, 2026-10-22 and 2026-10-24); the 26.3 line starts at Build 100,
   Aurora Forge (owner 2026-10-24). Plan: [ROADMAP-47-50.md](releases/ROADMAP-47-50.md).

## Index

### Project direction
| Document | About |
|---|---|
| [DEVELOPMENT.md](DEVELOPMENT.md) | Active development contract, governing rules, testing matrix |
| [TODO.md](TODO.md) | The single prioritized to-do list and status source (Indonesian); its *Build 50 candidate handoff* section tracks PR #62 per workstream |
| [releases/BUILD-50-PM-MATRIX.md](releases/BUILD-50-PM-MATRIX.md) | Build 50 per-item work matrix, generated from the 2026-10-25 docs audit: problem, files, done criteria, commit and owner verification per item |
| [Tester Owner](tester%20owner.md) | Owner manual test handoff for the Build 50 candidate (Indonesian) |
| [testing/owner/00-TESTING-INDEX.md](testing/owner/00-TESTING-INDEX.md) | Owner-run testing handbook: AWF, MultiProfile, Redis Fabric, async pathfinding, bridge, governor, benchmarks, failure recovery (cases in 01–05, result and bug templates in 06–07) |
| [AURORA-ROADMAP.md](AURORA-ROADMAP.md) · [AURORA-DEVELOPMENT.md](AURORA-DEVELOPMENT.md) | Aurora plans (plan role: checkboxes are snapshots) |

### Architecture (`architecture/`)
| Area | Documents |
|---|---|
| Entry point | [AURORA.md](architecture/AURORA.md), [aurora-engine-package.md](architecture/aurora-engine-package.md), [AURORA-ENGINE-SYSTEM.md](architecture/AURORA-ENGINE-SYSTEM.md), [AURORA-INDEPENDENT-ENGINE.md](architecture/AURORA-INDEPENDENT-ENGINE.md) |
| Execution & threading | [execution-contract.md](architecture/execution-contract.md), [aurora-resource-governor.md](architecture/aurora-resource-governor.md), [executors.md](architecture/executors.md), [executor-inventory.md](architecture/executor-inventory.md), [threading.md](architecture/threading.md), [region-parallelism.md](architecture/region-parallelism.md), [chunk-workers.md](architecture/chunk-workers.md), [region-io-audit.md](architecture/region-io-audit.md) |
| Engine ownership | [engine-chunk-world.md](architecture/engine-chunk-world.md), [engine-entity-ai.md](architecture/engine-entity-ai.md), [engine-network.md](architecture/engine-network.md), [network-allocation-copy-audit.md](architecture/network-allocation-copy-audit.md), [engine-storage.md](architecture/engine-storage.md), [persistence-save-paths.md](architecture/persistence-save-paths.md), [chunk-io-copy-audit.md](architecture/chunk-io-copy-audit.md), [command-blocks.md](architecture/command-blocks.md) |
| World storage (AWF) | [aurora-world-fabric.md](architecture/aurora-world-fabric.md) |
| AWF MultiProfile / Redis Fabric | [awf/multiprofile/ARCHITECTURE.md](awf/multiprofile/ARCHITECTURE.md), [PROFILE-LIFECYCLE.md](awf/multiprofile/PROFILE-LIFECYCLE.md), [PROFILE-ISOLATION.md](awf/multiprofile/PROFILE-ISOLATION.md), [DISTRIBUTED-OWNERSHIP.md](awf/multiprofile/DISTRIBUTED-OWNERSHIP.md), [REDIS-FABRIC.md](awf/multiprofile/REDIS-FABRIC.md), [CONFIGURATION.md](awf/multiprofile/CONFIGURATION.md), [API.md](awf/multiprofile/API.md), [SECURITY.md](awf/multiprofile/SECURITY.md), [OPERATIONS.md](awf/multiprofile/OPERATIONS.md), [TESTING.md](awf/multiprofile/TESTING.md), [BENCHMARKS.md](awf/multiprofile/BENCHMARKS.md) |
| Plugin compatibility | [aurora-plugin-bridge.md](architecture/aurora-plugin-bridge.md), [compat-boundary.md](architecture/compat-boundary.md), [compat-vs-performance.md](architecture/compat-vs-performance.md) |
| Startup & bootstrap | [aurora-instant-startup.md](architecture/aurora-instant-startup.md), [bootstrap-download-audit.md](architecture/bootstrap-download-audit.md), [slim-jar-bootstrap-failure.md](architecture/slim-jar-bootstrap-failure.md), [BOOTSTRAP.md](BOOTSTRAP.md), [CDS.md](CDS.md) |
| Independence & patches | [independence.md](architecture/independence.md), [dependency-ledger.md](architecture/dependency-ledger.md), [patch-classification.md](architecture/patch-classification.md), [patches.md](architecture/patches.md), [rebase-log.md](architecture/rebase-log.md), [inherited-guards.md](architecture/inherited-guards.md) (guard ledger), [engine-source-guards.md](architecture/engine-source-guards.md) (engine-source guard snapshot of 2026-10-21; counts and line numbers are dated) |
| Audits & reviews | [reuse-audit.md](architecture/reuse-audit.md), [cache-inventory.md](architecture/cache-inventory.md), [hotspots-entity-ai.md](architecture/hotspots-entity-ai.md), [region-safety-review-0017-0019.md](architecture/region-safety-review-0017-0019.md), [aurora-task-d-validation.md](architecture/aurora-task-d-validation.md), [qualification-readiness.md](architecture/qualification-readiness.md) |

### Operations, config and observability
[AURORA-CONFIG.md](AURORA-CONFIG.md) (config contract; samples in [config-examples/](config-examples/)) ·
[AURORA-UX.md](AURORA-UX.md) (console, `/aurora`, `/perf`) · [SPARK.md](SPARK.md) ·
[PROFILING.md](PROFILING.md) · [BASELINE.md](BASELINE.md) · [SOURBYMODS.md](SOURBYMODS.md) ·
[async-pathfinding-spec.md](async-pathfinding-spec.md) · AbyssLink: [README](abysslink/README.md),
[INTEGRATION.md](abysslink/INTEGRATION.md), [FOLIA-SAFETY.md](abysslink/FOLIA-SAFETY.md),
[TESTING.md](abysslink/TESTING.md)

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
[updater-rollback-fixture-2026-10-22.md](development/updater-rollback-fixture-2026-10-22.md) (local apply → failed boot → `.prev` restore fixture for the Build 49 Security gate; findings and its limits).
[CONFIG-FILE-RENAME-PLAN.md](development/CONFIG-FILE-RENAME-PLAN.md) (the `canvas-*.yml` → `aurora-*.yml` rename: plan, plus the Build 49 implementation record at the top; the migration itself is documented in AURORA-CONFIG.md).
[scratch-reentrancy-proof.md](architecture/scratch-reentrancy-proof.md) (DEV-A: every mutable scratch field the feature patches add, its re-entry paths and verdict).
[async-path-snapshot-audit.md](architecture/async-path-snapshot-audit.md) (DEV-E: every region member an off-thread path solve reads, vanilla vs `SnapshotPathRegion` answer, verdict per member; source audit, no server run).
[PLUGIN-NETTY-GUIDANCE.md](development/PLUGIN-NETTY-GUIDANCE.md) (for plugin authors and operators: which events run on a Netty event loop, why blocking there hurts, what to do instead; from a source audit, not a benchmark).
[SECURITY-GUARDS.md](development/SECURITY-GUARDS.md) (network security guards, SEC-1/SEC-11).
[BUILD-PROVENANCE.md](development/BUILD-PROVENANCE.md) (build identity, tree hash and provenance, OBS-3).
[fawe-currenttick-compat.md](development/fawe-currenttick-compat.md) and [multiverse-world-settings-compat.md](development/multiverse-world-settings-compat.md) (plugin compatibility findings and their fixes).

Dated evidence reports (history; each is linked from the TODO or release-gate row it supports):
[cmdblock-two-region-2026-10-21](development/cmdblock-two-region-2026-10-21.md),
[linux-vps-verification-2026-10-21](development/linux-vps-verification-2026-10-21.md),
[panel-bench-from-vps-2026-10-21](development/panel-bench-from-vps-2026-10-21.md),
[soulfire-load-2026-10-21](development/soulfire-load-2026-10-21.md),
[awf-backend-failure-2026-10-22](development/awf-backend-failure-2026-10-22.md),
[bridge-plugin-compatibility-2026-10-22](development/bridge-plugin-compatibility-2026-10-22.md),
[build48-log-and-commands-2026-10-22](development/build48-log-and-commands-2026-10-22.md),
[region-io-blocking-2026-10-22](development/region-io-blocking-2026-10-22.md),
[ssb2-real-jar-2026-10-22](development/ssb2-real-jar-2026-10-22.md),
[stop-during-startup-2026-10-22](development/stop-during-startup-2026-10-22.md),
[LIGHTINGLUMINOL-VERIFICATION-2026-10-23](development/LIGHTINGLUMINOL-VERIFICATION-2026-10-23.md).

### Guides (`guides/`)
[commands.md](guides/commands.md) (operator commands) ·
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
[Release roadmap 47–50](releases/ROADMAP-47-50.md) (owner plan 2026-10-21: 47 ship, 48 refinement, 49 full stable, 50 optimization; gates in [48](releases/26.2-build-48.md), [49](releases/26.2-build-49.md), [50](releases/26.2-build-50-aurora-nexus.md)) ·
[Build 47 — Aurora Nexus](releases/26.2-build-47-aurora-nexus.md) (published release and its gates) ·
Build 48: [release notes](releases/26.2-build-48-aurora-nexus.md), [gate tracker](releases/26.2-build-48.md) (published 2026-10-22, not STABLE) ·
Build 49: [release notes](releases/26.2-build-49-release.md) (normal release, not STABLE), [gates](releases/26.2-build-49.md) ·
[Build 49.1 hotfix](releases/26.2-build-49.1-hotfix.md) (test-only fix) ·
[Build 49 real-test runbook](releases/runbooks/build-49-real-test.md) (human tester and operator handover) ·
Build 50: [notes and gates](releases/26.2-build-50-aurora-nexus.md) (`v26.2-r50` prerelease), [readiness](releases/BUILD-50-READINESS.md), [PM matrix](releases/BUILD-50-PM-MATRIX.md) ·
[46](releases/46.md) · [45](releases/45.md) · [44](releases/44.md)

Dated plans and specs from the v6–v12, UniverseSpigot and Folia-base eras (May–September 2026,
formerly `docs/superpowers/`) were removed on 2026-10-13; recover them from git history if needed.

- [AbyssLink native backend](abysslink/README.md) — optional mTLS control connection, YAML setup, Folia-safe ping fallback and verification scope.

Build 50 candidate (PR #62): [readiness and partial-work closure](releases/BUILD-50-READINESS.md) ·
[PM matrix](releases/BUILD-50-PM-MATRIX.md) · [Tester Owner](tester%20owner.md). The `v26.2-r50` prerelease tag
(`5f5a167`) predates PR #61 and PR #62, so neither is in a published build; PR #62 is code written and
statically reviewed, not built, not tested by the owner, and neither PR has owner test results. On the
branch, distributed MultiProfile needs `aurora.awf.multiprofile.repository: redis` (writes made under a
session claim are fenced in Redis) and is experimental and unqualified; on `v26.2-r50` it runs on the
unfenced file repository and must not be used.
