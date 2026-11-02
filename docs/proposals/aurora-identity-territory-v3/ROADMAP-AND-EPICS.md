# Major Upgrade Roadmap — AIE + ARF

**Status:** proposed work breakdown; not a release pledge. The sole canonical active task list remains [docs/TODO.md](../../TODO.md). Existing 26.2 Build 50 readiness/PM matrix remains current. This RFC does not close items or select a version/build number. 26.3 remains the separately documented Build 100 Aurora Forge line.

## Work streams

| Epic | Dependency | Implementation targets | Done when |
|---|---|---|---|
| E00 Foundation safety | existing Build 50 | verify AWF unload/save, Redis fenced repository, shutdown and journal safety; fix source code and owner qualification | P0 blockers closed at exact commit |
| E01 AIE schema/registry | E00 | account/profile/name model, canonical uniqueness, rename/history, schema/migration, ownership model | concurrent name tests, no account UUID changes |
| E02 AIE presentation/API | E01 | public service, identity context, GameProfile presenter, secure skins, events and lookup cache | client/secure chat/lookup tests pass |
| E03 MultiProfile transaction | E00, E01 | REQUIRED adapters, total transition barrier, recovery, shutdown/playerdata fidelity | no loss/dupe under failure injection |
| E04 Atrium UX/integration | E01–E03 | native Dialog onboarding, character selector, staff inspector, policy permissions, scoped plugin modules | real-client + permission tests |
| E05 Leviathan handoff | E03 | authenticated handoff v1, destination admission, proxy route, idempotent recovery | two-node reconnect/session tests |
| E06 ARF island deployment | E00, E05 | placement of complete AWF instances among nodes, full world lease | island migration and recovery pass |
| E07 Territory registry/storage | E00, E06 | immutable territory mapping, layout, partitioned manifests, global authority, fencing | stale owner cannot commit |
| E08 Territory runtime/handoff | E05, E07 | foreign chunk denial, fixed boundary, player transfer | two-node world crossing passes |
| E09 Mirrors and affinity | E08 | read-only chunk subscription, ordered deltas, owner async actions, co-location strategy | no ghost writes, bounded lag |
| E10 Advanced simulation R&D | E09 | safe entity migration, redstone/hopper/portal dependency model, packet multiplexing feasibility | formal acceptance per mechanic |
| E11 Operational qualification | each epic | docs, migration, benchmark, incident drill, owner signoff | feature-specific production gate |

## PR cut lines (recommendation)

- AIE-01 schema + tests + migration dry-run;
- AIE-02 service + API compatibility;
- AIE-03 presentation/gameprofile + client regression;
- MP-01 required adapters, barriers and journal;
- ATR-01 UX onboarding / staff;
- LEV-01 authenticated proxy handoff;
- ARF-01 island placement;
- ARF-02 territory registry + layout;
- ARF-03 partitioned manifest/fencing;
- ARF-04 fixed boundary routing + rollback;
- ARF-05 mirrors/affinity experimental.

Do not couple AIE registry and ARF storage in one enormous patch; each must be independently rollbackable and versioned. Every code PR needs scope, current-state evidence, architectural delta, migration/rollback, test/evidence link and release/channel declaration.

## Immediate implementation inventory (from current docs)

- Build 50: owner still needs acceptance; current sources call out incomplete distributed live player state, default vanilla playerdata on cross-node join, quit snapshot during shutdown, routing transport, one FIFO across accounts, incomplete isolation categories, offline Atrium state edit conflict. All are prerequisites, not new completed work.
- Current `ProfileRequest` has no name parameter, `ProfileInfo` no dedicated character name field; add backward-compatible service/data model, avoid changing existing record binary constructors without compatibility plan.
- Existing AWF Redis writer lease is per world/store. Territory ownership requires changes to storage, chunk lifecycle, runtime/player routing and global world state. Do not enable `distributed-world` in config until that code exists.
- Existing Leviathan public multi-region edge mode is not gameplay shard routing; distinguish proposed handoff from Premium-specific internals and release gates.

## Definition of done

- Static contract validated against target code/patch tree, code style and threading policy.
- Unit/integration/real-client/manual cases as appropriate; owner-run gates remain owner responsibility.
- No blocking on region/entity/global tick or Netty; no ungated feature default.
- Every durable transition is idempotent, owner-fenced and recoverable.
- Migration + rollback rehearsal, compatibility list, security risk and performance baseline supplied.
- Change documented in API/config reference only **after** implementation; draft specs remain labelled RFC beforehand.

## Decisions still owned by maintainer

1. Profile-scope boundary: advancements, statistics, recipe book, attributes, PDC and playtime.
2. Identity registry store (single authority) and operational HA/durability tier.
3. Name release/rename policy, staff-reserved aliases and Bedrock/Floodgate policy.
4. World layout/territory size vs player density and acceptable cross-border mechanics.
5. Handoff UX SLA and error/reconnect flow.
6. Security mode of Aurora bridge; no relaxing of ownership checks merely for compatibility.
7. Release channel/build numbering and exact qualification/rollout hardware.

**Target design-quality score 10/10 is an aspiration with measurable gates, not a guarantee, benchmark or evidence of correctness.**
