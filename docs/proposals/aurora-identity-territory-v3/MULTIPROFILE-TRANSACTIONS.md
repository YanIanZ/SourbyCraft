# MultiProfile Transaction Safety — Hardening Specification

**Status:** target design; the documented Build 50 PR #62 code is unqualified. See [current isolation](../../awf/multiprofile/PROFILE-ISOLATION.md), [current architecture](../../awf/multiprofile/ARCHITECTURE.md), [distributed ownership](../../awf/multiprofile/DISTRIBUTED-OWNERSHIP.md). Do not replace their implementation claims with this proposal.

## Critical gaps to close before activating character-as-alt

1. Current `ProfileStateAdapter` documentation says failure/skipped capture/apply may not fail switching; Atrium `profile-adapter-required` intends fail-closed semantics. Audit actual implementation, reconcile and add required/best-effort contract.
2. Player freeze misses some damage/death/block break/command/plugin direct mutations; no full isolation guarantee.
3. Core does not isolate advancements, statistics, recipe book, gamemode, attributes, flight, PDC or arbitrary plugin state.
4. Live player data does not follow a player across servers; server-local vanilla playerdata can overwrite latest profile.
5. Shutdown ordering and crash-after-commit-before-vanilla-save are open.
6. One global `ProfileIoQueue` serializes unrelated accounts, causing head-of-line latency.
7. Offline Atrium edits can be overwritten by a later profile restore.

## Scope ownership matrix

| Domain | Key | Rule |
|---|---|---|
| Login, moderation, security | account UUID | account-wide; immutable |
| Character name, skin, settings | profile UUID | profile-wide |
| Inventory, armor, offhand, ender chest, XP, effects, food, health | profile UUID | required snapshot |
| Quest, skills, achievements, gamemode, attributes, flight, PDC | profile UUID | explicit adapter or deny unsupported mode |
| Economy | exactly one purse authority | no duplicate writes to Atrium+Vault+AWF |
| Homes, back, kits | profile UUID | fail-closed when required |
| Islands/world membership | world/island ID + profile UUID role | separate ACL and ownership |
| Staff sanctions | account UUID + optional profile UUID | never name-only |

## Proposed adapter contract

~~~java
enum DataCriticality { REQUIRED, BEST_EFFORT }
enum DataScope { ACCOUNT_WIDE, PROFILE_SCOPED, UNSUPPORTED }
record ProfileAdapterDescriptor(String id, int version,
  DataScope scope, DataCriticality criticality, long maxBytes) {}
~~~

An adapter marked REQUIRED must (a) persist a validated capture before target state becomes active, (b) verify load and version compatibility before apply, and (c) report application completion **only if durable provider state is consistent**. Failure -> switch refusal or journaled recovery; skipping data is not success. BEST_EFFORT may skip with a diagnostic but cannot silently drop prior opaque data. Adapter cap/serialization and write deadline remain bounded. Avoid adding untrusted Java deserialization.

## Transaction state machine

`VALIDATING -> TARGET_PREPARING -> QUIESCING -> SOURCE_CAPTURING -> SOURCE_DURABLE -> TARGET_APPLYING -> DESTINATION_READY -> ACTIVE_COMMITTING -> PRESENTING -> ACTIVE`.

Every phase records `txId`, source/target profile IDs, account session epoch, source/target revisions, snapshot IDs, deadline and retry outcome. Journals are idempotent and describe the authoritative commit point.

- **Preflight:** reserve account transaction; claim destination; check permissions, source snapshot availability, world readiness, adapter versions, capacity.
- **Quiesce:** server-level barrier blocks relevant player mutations, inventory writes, commands, reward callbacks and outstanding profile-scoped operations. Never wait blocking on an entity thread.
- **Capture:** core and REQUIRED adapters produce immutable snapshots, check bounds; failure leaves source active.
- **Publish:** durable snapshot + journal are confirmed on the selected storage authority, with revision/CAS and fencing.
- **Apply:** target state and REQUIRED providers apply; on failure, rollback only if target has not been committed; otherwise recover forward.
- **Commit:** activate target in authoritative account record; settle ambiguous reply by rereading verified authority. Successful UI/GameProfile swap only after state durable/ready.
- **Release:** unlock safely, notify plugin events, refresh caches. No state leak to next character.

If a node loses ownership at any point, stop mutating immediately. A failure after the commit point cannot be handled by an unvalidated local rollback.

## Cross-server handoff

Player state must be restored from **durable authoritative profile snapshot**, not destination `playerdata/<real-uuid>.dat`. Either intercept default vanilla data lifecycle safely through patches or quarantine/override before any gameplay interaction. Separate transaction journals for profile switch and node handoff may link by `txId` but have a single commit authority. The proxy cannot declare success until destination rehydrates required data.

## Scalability

Partition IO queues by account hash or implement bounded fair async dispatch, preserving per-account ordering while removing global head-of-line blocking; metrics for queue wait p95/p99 and saturation. Never introduce unbounded virtual thread spawning or synchronous storage on region threads.

## Open decisions

- Whether advancements/statistics remain account-wide in the first release; this must be an explicit product decision with corresponding GUI disclosure, not an accidental limitation.
- Exact shutdown/save barrier mechanism and rollback behavior for incomplete journals.
- Economy source of truth and Vault provider interactions.
- Required plugin adapter error semantics and duplicate reward protection.
