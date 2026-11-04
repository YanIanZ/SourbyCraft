# Owner Acceptance Plan — Identity, MultiProfile, ARF

**Status: checklist for future candidate builds. No test performed, no PASS claimed.** Continue to use existing [Tester Owner](../../tester%20owner.md) and [Build 50 readiness](../../releases/BUILD-50-READINESS.md) for current work. This plan adds future RFC gates; it does not supersede or mark earlier checks complete.

## Evidence standard

For every case record: `caseId`, candidate commit SHA/tag, exact game/server/proxy versions, plugin set, owner-run date, setup, steps, observed result, expected result, logs/metrics/snapshot hashes, PASS/FAIL/BLOCKED, links to defect. A pass on a previous tree is not a pass after a persistence/ownership code change.

## Acceptance matrix

| Case | Scenario | Expected result |
|---|---|---|
| ID-001 | Verified Mojang account first join | original UUID retained, no guest override |
| ID-002 | New character native Dialog submit | server-side validation and durable profile creation |
| ID-003 | Parallel same canonical name on 2 nodes | exactly one successful reservation/profile |
| ID-004 | Parallel create over max slots | slot hard cap invariant |
| ID-005 | Name collision across casing | single canonical unique owner |
| ID-006 | Attempt staff/Mojang name impersonation | reserved policy enforced + audited |
| ID-007 | Rename while session active/after crash | no orphan alias, consistent history |
| ID-008 | A->B->A profile switching | no inventory/XP/balance/homes leakage |
| ID-009 | GameProfile change / TAB / scoreboard | updated presentation; UUID unchanged |
| ID-010 | Signed chat, textures and client re-registration | no account spoofing; documented client behavior |
| ID-011 | Staff /whois on renamed/offline profile | correct account/profile stable IDs; authorization |
| ID-012 | Nonstaff account lookup and placeholders | no private identity disclosure |
| MP-101 | Required adapter fails capture | no successful switch or data loss |
| MP-102 | Required adapter fails apply | rollback/recovery journal, not silent continuation |
| MP-103 | Delayed reward A->B->A | callback does not write wrong profile |
| MP-104 | Death, block break, command during switch | transition barrier prevents inconsistent mutation |
| MP-105 | Disconnect during apply/teleport | deterministic rollback or forward recovery |
| MP-106 | Kill after active commit before playerdata save | latest committed state recovered |
| MP-107 | Shutdown with active player | durable snapshot or explicit incomplete warning/quarantine |
| MP-108 | Offline Atrium home edit | versioned edit not overwritten by stale snapshot |
| MP-109 | Two node same-account login | only authorized owner mutable |
| MP-110 | Redis down / ambiguous commit reply | fail-closed, reconciled by transaction ID |
| ARF-001 | Negative coords and dimension mapping | stable correct territory key |
| ARF-002 | Two node territory claim race | exactly one admitted writer |
| ARF-003 | Stale source commit after epoch takeover | authoritative manifest refuses |
| ARF-004 | Kill at every migration phase | one valid checkpoint and owner or quarantined |
| ARF-005 | Missing/corrupt chunk/entity/POI/tick object | no incomplete writable mount |
| ARF-006 | Partition/Redis restart/authority loss | no split-brain writes |
| ARF-007 | Player border crossing with proxy | state preserved, one session |
| ARF-008 | Border crossing repeated under load | no bounce/dupe; bounded handoff queue |
| ARF-009 | Remote mirror stale/delta gap | full resync and zero remote writes |
| ARF-010 | Entity crossing / multipart entity | exactly one authoritative entity or safe refusal |
| ARF-011 | Cross-border redstone/hopper/explosion | documented safe behavior or blocked |
| ARF-012 | World time/weather/gamerules mutation | single global authority |
| ARF-013 | Plugin sync remote chunk call | safe refusal; no owner-thread blocking |
| ARF-014 | Rollback older binary against new partition schema | fail closed, never empty world created |
| OPS-001 | Restore verified backup of all stores | successful integrity reconciliation |
| OPS-002 | Redacted audit/log review | no credentials or private mappings leaked |
| OPS-003 | Owner benchmark at realistic load | recorded p95/p99, no unbounded growth |

## Test environment

At least two real SourbyCraft nodes, a dedicated trusted proxy and independent authority/storage processes. Reproduce single-node behavior first. Use disposable copies and synthetic test profiles for kill/network-partition cases, never production player data. Owner controls the hardware and runs manual test cases; contributors may prepare documentation and optional fixtures **only if authorized** by the current repository workflow.

Test interference variables: plugin matrix, OS/JDK, CPU pinning, storage/AOF policy, net delay/jitter, network partition, failure injection point, player count, view/simulation distance, world structure, redstone/entity density and warm/cold cache. Record exactly, not inferred. Compare to same-hardware baseline before throughput or latency claims.

## Non-negotiable blocker rules

P0 = data loss/dupe, dual writer, authentication spoofing, unverified alias mapping, unsafe required adapter skip, authority bypass, mismatched release schema. Any P0 blocks production even if UI appears functional. No release gate can be closed merely by CI status: require owner evidence at exact SHA and documented risk acceptance.

## Sign-off

- [ ] Identity contract and compatibility reviewed
- [ ] Current Build 50 safety gates accepted independently
- [ ] Storage/authority threat review completed
- [ ] Test results attached to exact candidate SHA
- [ ] Migration dry-run and restore rehearsal recorded
- [ ] Permission/privacy review completed
- [ ] Performance baseline and load envelope measured
- [ ] Owner accepts boundary limitations for shipped tier
- [ ] Release notes correctly distinguish experimental vs qualified

**Owner decision:** OPEN.
