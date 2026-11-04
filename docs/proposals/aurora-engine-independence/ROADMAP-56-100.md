# Aurora Engine Independence — Build 56–100 Roadmap

**Status:** strategic program and epic backlog. **NOT** a release reservation, production schedule, evidence that tests passed, or a statement the features exist. Builds 56–99 belong to the existing 26.2 continuation **subject to maintainer release decisions**; Build 100 is the planned 26.3 “Aurora Forge” boundary, with an independent upstream/toolchain upgrade gate. Do not set release flags based on this roadmap.

## Rollout principles

- Stabilize current Build 50 first (data integrity, ownership and owner acceptance); a promising later feature does not waive older P0 gates.
- Every code change maps to a concrete engine owner, adapter contract, measured bottleneck and rollback procedure.
- Split implementation PRs by one independent subsystem where possible. Do not batch major chunk scheduler, profile storage and plugin compatibility changes in one patch.
- Performance is reported with same-workload p50/p95/p99, CPU, allocation, GC and failure metrics; **never** invent estimated percentage improvement.
- Keep source attribution, public plugin contracts and source patch reproducibility. Existing `paperRef` and private toolchain pins are separate change controls.

## Planned work by build range

| Range | Program | Goal / candidate deliverable | Owner qualification gate |
|---|---|---|---|
| **56–60** | AE-100 Foundation & Safety | audit Build 50 outstanding P0s, freeze engine contracts, detailed owner-thread and I/O inventory, lifecycle hardening, legacy bridge classification | no unconfirmed AWF save reported SUCCESS; approved authority and thread test matrix |
| **61–70** | AE-200 Chunk/World | source-owned diagnostics, chunk ticket and holder lifecycle optimization, bounded load/unload, generation workers A/B | chunk-stress baseline, split/merge and unload/crash tests; no regression outside tolerance |
| **71–80** | AE-300 Execution/AI | safer async path snapshots, queue fairness, owner-valid completion, resource governor admission, executor lifecycle | wrong-owner stress, entity transitions, no lost tasks; p99 under configured workload |
| **81–90** | AE-400 Network/Storage/Bridge | allocation/copy profiling, packet queue bounds, safe legacy bridge path, AWF snapshot/commit improvements | no protocol/plugin regression, no data loss/dupe, storage failure recovery |
| **91–99** | AE-500 Consolidation | narrow Paper/Folia internal adapters, remove redundant coupling, source/package boundaries, reproducible patch profile, canary defaults | approved coupling inventory, deterministic build, equivalent plugin behavior |
| **100** | AE-600 Aurora Forge qualification | 26.3/Paper upgrade as a **separate gated track**, final integration, release candidate performance and recovery | owner sign-off at exact commit; otherwise postpone stable declaration |

The ranges are organizing labels, not promises of which exact build carries a feature. A build may be skipped, released with only bugfixes or remain prerelease. Changes that are high-risk can remain EXPERIMENTAL beyond Build 100.

## Definition of done per epic

**AE-100 Runtime Foundation**
- [ ] Recheck the current Build 50 branch/tag and updated owner results.
- [ ] Build a per-service start/stop dependency table from `SourbyCraftBootstrap`; avoid inventing a duplicate runtime bootstrap.
- [ ] Error state taxonomy for DEGRADED, FAILED, CANCELLED and UNKNOWN durable commits.
- [ ] Auditable region thread policy, including legacy-relaxed bridge quarantines.
- [ ] Region/Netty blocking-I/O negative test plan and resource cap ledger.

**AE-200 Chunk Engine**
- [ ] Profile actual chunk-holder lookup, ticket churn, generation and unload in repeatable workloads.
- [ ] Compare candidate changes to current Moonrise-integrated pipeline; do not bundle a library upgrade.
- [ ] Minimize targeted feature patch scope; preserve ordered chunk save/unload and ownership fences.
- [ ] Confirm no stale chunk reads, orphan tickets, missing scheduled ticks or duplicate entity loads under owner stress.
- [ ] Produce benchmark before deciding defaults.

**AE-300 Execution and Entity**
- [ ] Typed async context/revision validation and bounded cancellation for relevant AI work.
- [ ] Current Resource Governor lane accounting reviewed for backpressure and starvation.
- [ ] Per-account fair profile work as a **separate** consistency PR; maintain session and journal invariants.
- [ ] Prove merge/split and retired-region scheduling correctness; no tick work pushed to an unowned queue.
- [ ] Parked EDF remains off unless separately re-approved and tested.

**AE-400 Data and Network**
- [ ] Network hot-path evidence (copies, Netty queues, packet latency), protect protocol and plugin compatibility.
- [ ] AWF save/unload durability and UNKNOWN-commit recovery reviewed at actual candidate SHA.
- [ ] Crash-test profile and world save paths, including source/target state after reconnect.
- [ ] Address buffer lifetime and memory ownership only where measured; no use-after-free.
- [ ] Document partial deployment and restoration paths.

**AE-500 Independence**
- [ ] Reduce only **proven** dependence on private/internal scheduler types; preserve public Bukkit/Folia APIs.
- [ ] Patch-level dependency ledger distinguishes direct modifications from adapter references.
- [ ] Stabilize compiler/package/source reproducibility, NOTICE and public API regression gates.
- [ ] Re-run `scripts/independence_policy.py` and `scripts/patch_surface.py`; no “100% independent” claim based only on changed package names.
- [ ] Canaries on real owner environment before selecting new defaults.

**AE-600 Build 100**
- [ ] Follow actual [Minecraft/Paper upgrade procedure](../../development/UPGRADING-MINECRAFT.md), including private SourbyPatcher and paperweight pins.
- [ ] New 26.3 source and plugin compatibility reviewed independently.
- [ ] Reconcile Build 50–99 evidence; retest areas touched by 26.3 rebase.
- [ ] Release and updater identity, migration/backup and operator fallback reviewed.
- [ ] Owner signs off per-area STABLE/EXPERIMENTAL/DISABLED states; no automatic green label.

## Proposed PR sequence

AE-101 runtime state + partial boot cleanup -> AE-102 wrong-thread and blocked IO observability -> AE-201 chunk ticket measurements -> AE-202 small measured chunk optimization -> AE-301 async AI correctness -> AE-302 fair bounded queues -> AE-401 AWF durable save gate -> AE-402 network allocation work -> AE-501 internal adapter cleanup -> AE-502 source patch reproducibility -> AE-601 separate 26.3 rebase and qualification.

Actual implementation should reorder these only based on P0 severity and measured bottlenecks, with a dated reason in TODO. No task is marked complete by merging this docs-only PR.

## Relation to earlier programs

- **Metal PR #69** is closed unmerged and intentionally excluded.
- A standalone **Spottedleaf upgrade** is **not** part of this track. Existing Paper/Moonrise/LeafPile implementation remains until an independently justified compatibility or security fix.
- **Identity and territory sharding PR #63** is a separate future design. This Aurora roadmap owns common runtime correctness, not cross-JVM shard features.
