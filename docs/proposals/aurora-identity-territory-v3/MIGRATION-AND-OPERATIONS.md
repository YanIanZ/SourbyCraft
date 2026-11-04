# Migration and Operations Runbook — Proposal

**Status:** planned operational workflow. Do not run commands in this proposal on live data until implementation, QA and owner authorization. Existing Build 50 procedures remain authoritative for current code: [operations](../../awf/multiprofile/OPERATIONS.md), [readiness](../../releases/BUILD-50-READINESS.md).

## Baseline constraints

- `v26.2-r50` prerelease is not a distributed-profile production target. PR #61/#62 land after published tag; owner tests are incomplete.
- Current FILE profile repository cannot be mounted as a shared fenced backend across multiple nodes; current migration to Redis is one-way and documents potential rollback data loss.
- Existing AWF Redis per-world leases **do not** support concurrent writes to multiple partitions of one world.
- Keep newly proposed ``identity.*` / ``territory.*` settings absent until real parsers, defaults, validation and examples are implemented. Text below is logical configuration, **not effective YAML keys**.

## Migration phases

### Phase A — Audit and freeze
1. Record exact branch SHA, release tag, world and plugin inventory, Paper/Java version and data sizes.
2. Shut off new profile creation and cross-server gameplay during migration; drain players with maintenance notice.
3. Require verified backups of world files, AWF stores, `awf/profiles`, Atrium DB and Redis snapshots/AOF as applicable; prove restore on disposable instance before proceeding.
4. Audit old profile snapshots, obsolete schema, duplicate nicknames, offline edits, economy purse authority, custom UUID references and plugin state adapters.

### Phase B — Identity registry backfill
1. Seed one immutable account identity per verified Mojang UUID and one profile UUID per existing profile.
2. Preserve current primary identity and all profile data; do **not** rewrite vanilla UUIDs or derive profile IDs from usernames.
3. Resolve collision cases by explicit migration policy (temporary alias/reservation and owner confirmation), never silently overwrite. Store old->new mapping in reversible ledger.
4. Introduce versioned registry and dual-read cutover if needed; use **one write authority**, not uncontrolled dual-write Redis+SQL.
5. Compare total account/profile counts, owner mapping, names and checksums before enabling write traffic.

### Phase C — AIE/Atrium canary
1. Enable immutable identity snapshots, staff inspection and read-only resolver on isolated node.
2. Qualify name reservation and GameProfile presentation against real clients and plugins; keep legacy nickname behavior explicit.
3. Enable character creation only after required adapter contracts and profile switching pass owner tests. Keep quick kill switch that disables creation while preserving data.
4. Monitor duplicate name rejected, profile recovery required, stale identity callback and adverse chat/lookup behavior.

### Phase D — Distributed profiles and Leviathan
1. Deploy validated shared profile repository, fencing authority and private proxy/backend trust.
2. Dry-run account handoff and fail recovery with two test nodes; no cross-node handoff for unqualified plugins.
3. Canary on limited users; synthetic/test identities only during fault injections. Observe rollback/recovery ledger and reconcile every UNKNOWN outcome.
4. Roll back *feature access* first, keep current schema/data backed up; never assume older code can read newer snapshots.

### Phase E — ARF / world migration
1. First qualify isolated world/island moves; territory partitions remain disabled.
2. Offline export verified current world and global metadata to a partition-manifest format; validate chunk/entity/POI/tick references and world seed/dimension keys.
3. Start one owner per territory; do not admit client gameplay until owners, manifest and global authority are consistent.
4. Test boundary handoff and supported redstone/entity policies on a disposable clone. Enable read-only mirrors only after validation.
5. Retain old world for rollback, **read-only**. Never mount old and new storages as writable simultaneously.

## Operational states and actions

| Incident | Default operator action |
|---|---|
| Lost Redis/SQL/control authority | pause unsafe mutations / handoffs, preserve snapshots and journals |
| Failed durable save | report ERROR and retain last confirmed manifest; do not unload/report success |
| Duplicate character names | block create/rename, investigate unique registry and conflict ledger |
| Unknown player transition | quarantine account from mutable actions, reconcile by tx ID + authority |
| Two territory owners seen | fence both from writes until authoritative epoch resolution; forensic snapshot |
| Stale mirror | invalidate view/resync, reject interaction |
| Disk/Redis pressure | stop admission and migrations, preserve rollback artifacts |
| Schema mismatch | refuse writable mount; dry-run migration with backup |
| Stuck journal | explicit admin recovery with audit, never manual deletion without a verified plan |

## Commands and configuration placeholders

**Proposed, not implemented:** `/aurora identity inspect`, `/awf profile recover`, `/arf status`, `/arf plan`, `/arf migrate --dry-run`, `/arf ownership`, `/perf territory`. Each needs permission, thread policy, dry-run behavior, observability and failure codes defined before release. Avoid overloading a shipped command with incompatible behavior.

## Operator telemetry

Identity revision conflict, name reservation conflicts, switch capture/apply/commit latency, required adapter failure, current session owner/epoch, account handoff recovery queue, stale commits rejected, territory migration success/unknown, oldest unconfirmed snapshot, manifest revision, remote mirror lag, tick MSPT per local territory, global authority staleness, coordination backpressure.

Dashboards must display **last confirmed** versus **currently requested** state. No misleading green status from an unacknowledged asynchronous task.

## Rollback policy

For pre-activation failures, disable feature flags and retain old compatible state. For post-migration failures: recover forward or restore verified complete snapshot with explicit downtime, account for transactions after snapshot, and check schema and fencing epochs before opening. Old binaries mounting new state writable is **forbidden by default**. There is no automatic safe reverse migration of all data.

## Operator acceptance

Document restore rehearsal evidence, source/tag hash, database schema and migration ledger, exact server count, benchmark, max acceptable outage, and contacts/permissions. Only owner may label deployment QUALIFIED/STABLE.
