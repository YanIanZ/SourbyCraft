# Builds 51–55: co-op MultiProfile, one complete slice per build

**Status:** plan, accepted by the owner on 2026-11-03. Nothing below is implemented unless its build's
release notes say so. Design and file lists: [COOP-DESIGN.md](../awf/multiprofile/COOP-DESIGN.md).
Status source remains [docs/TODO.md](../TODO.md).

## Owner decisions (2026-11-03)

| ID | Decision |
| --- | --- |
| Release | Builds 51, 52, 53, 54 and 55 are published from `26.2` with `release=true`, one build at a time. |
| Scope | Port of the 2026-10-24 co-op WIP (`wip/multiprofile-coop-20261024`, `18f78fb4`) onto the PR #62 MultiProfile. Each build ships its slice **complete**: wired end to end (engine, FILE and Redis repositories, recovery, API, commands, config, docs). It is not a dormant skeleton. MultiProfile and co-op stay **default-off**. |
| OD-1 | One node per co-op. Every co-op session runs on the node that holds the profile's bound world. A join to a co-op profile on another node is refused with a reason. Cross-node routing stays epic E05 (RFC v3). *Build 56 (owner 2026-11-04): lifted for satellite nodes behind `coop.cross-node` (default off = this decision); see [Build 56](26.2-build-56-aurora-nexus.md).* |
| OD-2 | Once a store is format 2, binaries before format 2 must not run on it. Enforced where it can be: Build 51+ (`update/StoreDowngradeGuard`) refuses to restore `<jar>.prev`, to stage or to swap in a jar older than Build 51 while the configured store is format 2 (Redis unreadable at that point = refused, fail closed). Builds older than 51 have no gate of their own (they treat format-2 records as corrupt and write format-1 records beside them), so a manual downgrade is only possible by restoring the complete pre-migration backup into an isolated store. In-place downgrade is unsupported. |
| OD-3 | Salvage ships in Build 54 with exactly-once semantics: the source is consumed before delivery, a durable receipt is kept, and the claimant must have that profile active. The owner's crash/replay cases define its supported scope. |
| OD-4 | Transactional transfers are bank-only, with receipts. The purse stays per profile through a REQUIRED state adapter (snapshot/restore). Purse↔bank transfers are refused until a transactional provider API exists. |
| Tests | Agents write the design's owner cases as JUnit sources and **never run** tests or benchmarks. CI runs them on push. Only the owner runs tests and benchmarks and qualifies. |
| Developer API (2026-11-04) | AWF, profiles and Aurora services are usable by every plugin built on `sourbyapi` through one entrypoint, `dev.iyanz.sourbycraft.api.SourbyAPI` (`worlds()`, `profiles()`, `profileTypes()`, `metrics()`, `scheduler()`, `build()`). Build 54: the entrypoint, AWF API gaps (listing, world info, instances) and the profile type registry. Build 55: AWF and profile events, an Aurora scheduler facade, a developer guide with an example plugin, and the `sourbyapi` jar (+ sources and javadoc) attached to every `v26.2-rN` release. |

## Build map

| Build | Slice (COOP-DESIGN §10) | Complete when |
| --- | --- | --- |
| 51 | 2 + 3, plus names and types (G1) | Four independent revision domains and typed claims. Record format 2 on FILE and Redis. Intent journals with recovery at every phase. Solo format-1→2 migration. OD-2 downgrade gate. Account-unique immutable cute names and profile types (classic/ironman/stranded/bingo) persisted and returned by the API (`ProfileInfo` stays binary compatible). |
| 52 | 4 (G2) | Invite, accept, decline, expire, leave. OWNER/MANAGER/MEMBER permissions. Vote kick with electorate checks and the inactivity rule. Co-op size cap (default 5, max 8). Paged lists. Commands, API and events. Online-member removal is refused with a reason (enabled in 53). Owner transfer and owner kick are refused. |
| 53 | 5 + RFC E03 | Per-member snapshots and capture tickets on save and quit. Login restores the committed member head, not vanilla playerdata. REQUIRED/BEST_EFFORT adapter contract with unknown-blob preservation and the aggregate budget. Quit snapshot at shutdown (patch 0081 order, PM-076). Online-member removal enabled. OD-1 enforced. |
| 54 | 6 (G3) + type registry + developer API entrypoint | **Addon-managed profile types (owner 2026-11-04):** a public `ProfileTypeRegistry` in sourbyapi where a consumer (the SSB-AuroraProfiles addon, from its `types.yml`) registers profile types (id, display name, naming pool, co-op allowed) and a rule set per action; built-in type ids stay compatible and the engine enforces the actions it owns (bank, co-op operations) — e.g. an "Ironman: no bank" definition from the addon's `types.yml` (the built-in `ironman` denies trade/auction/bazaar only). Shared bank and personal bank (20 % / 10 % policy values), collections with a per-member contribution ledger, bank transfers with permanent receipts, overflow refusal and replay safety. Salvage per OD-3. Purse per OD-4. |
| 55 | 7 + G5–G7 + PM-091 + addon port + developer API completion | Audit log on the bounded I/O lane. Switch guard and cooldown (only a committed switch consumes it). Rules from the type registry enforced at every engine action boundary, and exposed for the addon's own actions (e.g. SSB2 island bank); bingo archived read-only. API parity fields and events. Per-account I/O ordering without global head-of-line blocking (PM-091). `/perf` metrics. Complete MultiProfile docs. The SSB-AuroraProfiles addon (`/Volumes/Ext/Sourby/SSB-AuroraProfiles`, local git since 2026-11-04) is ported to the Build 51–55 API with `types.yml`. |

## Per-build procedure

1. om codex (workspace-write) implements the slice on `build5N/...`. A second om codex run reviews it read-only against COOP-DESIGN and the PR #62 safety properties: fencing, revision CAS, exact rollback, bounded reads, no item duplication.
2. The integrator compiles (`:sourbyapi:compileJava :sourbycraft-server:compileJava`, plus test compilation), reviews the diff and fixes it. No test or benchmark is executed by an agent.
3. Bump `sourbyBuild=5N`, set `release=true`, write `docs/releases/26.2-build-5N-aurora-nexus.md` with the owner test cases and known limits, then update `docs/TODO.md`.
4. Open a PR to `26.2` and merge it. CI (`cancel-in-progress`) must finish green and publish `v26.2-r5N` before the next build is merged.

No build is labelled qualified or STABLE because it compiled, passed CI or was published. Acceptance needs the owner's evidence on that build's commit.
