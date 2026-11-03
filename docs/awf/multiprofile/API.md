# API

## Obtaining the service

```java
AuroraProfiles profiles = Bukkit.getServicesManager().load(AuroraProfiles.class);
if (profiles == null || !profiles.isEnabled()) {
    // AWF MultiProfile is off on this server.
    return;
}
```

The service is registered by `ProfileRuntime` during the `awf multiprofile` boot stage, before plugins
enable. A disabled feature (or one that refused to start) registers nothing.

## Value types (`dev.iyanz.sourbycraft.api.profile`)

- `ProfileRequest(accountId, type, metadata, profileType, name)` — `type` is `[a-z0-9_-]{1,32}`;
  metadata at most 32 entries, keys 1–64 characters, values up to 512. `profileType` and `name` are
  optional (Build 51, see [Names and policy types](#names-and-policy-types-build-51)); the
  three-argument constructor and `ProfileRequest.of(accountId, type)` of earlier builds still exist and
  leave both to the server.
- `ProfileInfo(id, accountId, type, metadata, createdAtMillis, revision, status, active, worlds)` —
  unchanged in Build 51.
- `ProfileDetails(info, name, profileType)` and the enum `ProfileType` (`CLASSIC`, `IRONMAN`,
  `STRANDED`, `BINGO`) — Build 51.
- `ProfileWorldRef(world, role, loaded)` — `role` is `PRIMARY`, `NETHER`, `END`, `SHARED` or `AUXILIARY`.
- `ProfileSwitchResult(success, errorCode, message, profile, transitionId, durationMillis)`.
- `ProfileOwner(node, epoch, expiresAtMillis, distributed)`.
- `ProfileErrorCode` — `DISABLED`, `SWITCHING_DISABLED`, `PLAYER_OFFLINE`, `PROFILE_NOT_FOUND`,
  `NOT_OWNED`, `SLOT_LIMIT`, `PROFILE_LOCKED`, `SWITCH_IN_PROGRESS`, `STALE_STATE`,
  `OWNERSHIP_CONFLICT`, `WORLD_UNAVAILABLE`, `TIMEOUT`, `RECOVERY_REQUIRED`, `RECOVERED`, `IO_FAILURE`,
  `INVALID_REQUEST`, `STATE_TOO_LARGE`, `INTERNAL_ERROR`, and, appended in Build 51 after
  `INTERNAL_ERROR` (existing ordinals unchanged), `NAME_TAKEN`, `NAME_INVALID`, `TYPE_NOT_ALLOWED`,
  `STORE_FORMAT_REFUSED`, `MIGRATION_REQUIRED`, `COUNTER_EXHAUSTED` (a revision or fence counter reached
  2^53-1; offline migration needed), `CLAIM_LOST` (the session or transaction claim lapsed or was taken
  over; nothing written), `UNKNOWN_OUTCOME` (the store did not confirm a write and reading it back did
  not settle it; nothing retried or compensated), `CAPACITY_EXCEEDED` (a store bound is reached: pending
  intents per account, profile or store, permanent receipts of a resource, or a full index; retry later).
  `STATE_TOO_LARGE` was added in Build 50 PR #62 before `INTERNAL_ERROR`; a plugin that persisted ordinals
  must map them again.
- `ProfileOperationException` — unchecked, carries the code for methods other than `switchProfile`
  (which returns a result instead).

Codes worth handling: an invalid type or metadata is `INVALID_REQUEST`; a state whose encoding exceeds
8 MiB is `STATE_TOO_LARGE` (nothing stored, size in the message); a switch whose rollback is still
pending is `RECOVERY_REQUIRED`; `saveProfile`, a switch, `deleteProfile`, `bindProfileWorlds` and
`setProfileLocked` are refused with `RECOVERY_REQUIRED` while a transition journal of the account is
pending; `IO_FAILURE` when the player's journal is unreadable, a write failed, or the Redis repository
refuses writes before its migration; `OWNERSHIP_CONFLICT` when another process holds the profile or this
node's claim no longer holds it; `INTERNAL_ERROR` when the profile I/O queue is full.

## Threading contract

Every method returns a `CompletableFuture`. Callbacks have no guaranteed thread. **Never** call
`join()`/`get()` on a region, entity or global-tick thread; attach `thenAccept`/`whenComplete` and use
`SourbyReply.run` when the result must be delivered to a command sender. The engine itself never joins
on a tick thread and never performs Redis I/O from one: repository, ownership and Fabric-publish work
runs inside the engine's profile I/O steps, which run one at a time on the storage lane (a bounded queue
of 4096 steps per server).

`switchProfile` is rejected with `SWITCH_IN_PROGRESS` while another switch, a recovery or a join's
return teleport runs for the same player. Deleting the active profile returns `PROFILE_LOCKED`. A
switch failure never throws: the result carries the code, and a failure after the target state reached
the player is rolled back, or reported as `RECOVERY_REQUIRED` while the rollback is pending.

## State adapters

```java
profiles.registerProfileStateAdapter(this, new ProfileStateAdapter() {
    public String id() { return "example:claims"; }
    public int version() { return 1; }
    public byte[] capture(Player player) { /* return null when nothing to store */ }
    public void apply(Player player, byte[] data) { /* restore */ }
    public Plugin plugin() { return ExamplePlugin.this; }
});
```

Adapters run inside the same entity task as the core capture/apply. Bytes are stored verbatim under
`id:version`; bump `version()` when the format changes. `version()` is read once, at registration.

- Limits: 2 MiB per adapter and 6 MiB for all adapters of one snapshot, at most 32 adapters, and an
  `id:version` key of at most 64 characters.
- Registration throws `IllegalArgumentException` for a malformed id, a version below 1, a key over 64
  characters, an id another enabled plugin registered, a 33rd id, or (Build 53) a `descriptor()` that throws or
  disagrees with `id()` / `version()`; `ProfileOperationException`
  (`INVALID_REQUEST`) when the registering plugin is not enabled. A plugin may replace its own adapter;
  disabling a plugin removes its adapters.
- Data stored under another version is not applied (one warning) and stays in the profile, within the
  limits, until the adapter stores data at its current version. Data of adapters not registered on this
  boot is carried into new snapshots.
- What happens when a capture or apply fails, or the adapter is missing, depends on its criticality
  (Build 53, below). An adapter that does not override `descriptor()` is BEST_EFFORT: a failing capture or
  apply is logged and skipped and never fails the switch, as before, except that the bytes it already stored
  at its current version are now preserved when its capture fails.

### Adapter contract (Build 53)

```java
public interface ProfileStateAdapter {
    default ProfileAdapterDescriptor descriptor() { /* BEST_EFFORT, PROFILE_SCOPED, id(), version(), 2 MiB */ }
}
public enum ProfileDataCriticality { REQUIRED, BEST_EFFORT }
public enum ProfileDataScope { ACCOUNT_WIDE, PROFILE_SCOPED, UNSUPPORTED }
public record ProfileAdapterDescriptor(String id, int version, ProfileDataScope scope,
                                       ProfileDataCriticality criticality, long maxBytes) {}
```

An adapter that holds data the profile cannot be correct without overrides `descriptor()` and returns
`REQUIRED`. The addition is binary compatible (a `default` method, new types only): an adapter compiled against
Build 52 keeps running as BEST_EFFORT. `maxBytes` is 1 to 2 MiB; the id and version must equal `id()` and
`version()`.

REQUIRED means: the capture is durable before the target profile becomes active; the apply is verified before
the switch is reported successful; a failure refuses the switch (`ADAPTER_REQUIRED_FAILED`) or is journaled for
recovery; an adapter that is absent while the profile holds its data refuses it (`ADAPTER_MISSING`); a skip is
never success. BEST_EFFORT means a failure is skipped with a warning and the opaque bytes the profile already
holds for the adapter are preserved. Details and the full table: [PROFILE-ISOLATION.md](PROFILE-ISOLATION.md#adapter-contract-build-53).
An operator can force adapter ids to REQUIRED with `adapters.required`.

## Example: create and switch

```java
profiles.createProfile(ProfileRequest.of(player.getUniqueId(), "skyblock"))
    .thenCompose(created -> profiles.switchProfile(player.getUniqueId(), created.id()))
    .whenComplete((result, error) -> {
        if (error != null) return;
        if (!result.success()) {
            logger.warn("switch failed: {} {}", result.errorCode(), result.message());
        }
    });
```

## Names and policy types (Build 51)

Build 51 code written; not built by an agent; not tested by the owner. Co-op membership is the next
section (Build 52); the Build 51 types below carry none of it.

Every profile has two independent type-like fields. `ProfileInfo.type()` is the free consumer
namespace (`skyblock`, a plugin's own name) that the core never interprets. The new
`ProfileType` is the policy type (`classic`, `ironman`, `stranded`, `bingo`) chosen at creation and never
changed. Both are stored in the profile record (store format 2, see
[CONFIGURATION.md](CONFIGURATION.md#build-51-store-format-2-names-and-types)).

```java
profiles.createProfile(ProfileRequest.of(accountId, "skyblock", ProfileType.IRONMAN))
    .thenCompose(created -> profiles.getProfileDetails(created.id()))
    .thenAccept(details -> logger.info("{} ({})", details.name(), details.profileType().id()));
```

- `getProfileDetails(profileId)` and `listProfileDetails(accountId)` return `ProfileDetails`
  (`ProfileInfo` plus `name` and `profileType`). Both are `default` methods that fail with
  `UnsupportedOperationException` on an implementation built before Build 51; `ProfileInfo` was not
  changed so code compiled against earlier builds keeps working.
- The **name** is chosen once when the profile is created, never changes and is unique per account
  (compared ignoring case). With `names.policy: cute` the server assigns a fruit or vegetable name
  from its pool; a numeric suffix is appended only when the pool is exhausted for that account. A
  request may name a pool name instead of a rolled one. Any other text fails with `NAME_INVALID`; a name
  the account already uses fails with `NAME_TAKEN`. There is no rename.
- The **policy type** must be in `types.allowed` or creation fails with `TYPE_NOT_ALLOWED`; a request
  without one gets `types.default`.
- **Enforcement:** Build 51 stores and returns the type and enforces nothing. Since Build 54 the rules come
  from the `ProfileTypeRegistry` (next section): the server checks its own actions (bank, co-op, salvage);
  `trade`, `auction`, `bazaar` and every other consumer action are the consumer's to check with `allows`.
- `STORE_FORMAT_REFUSED` means the store is in a record format this build does not use; nothing was
  written. `MIGRATION_REQUIRED` means the store is still format 1 and the operation needs format 2 (an
  operator runs the migration, see [OPERATIONS.md](OPERATIONS.md#format-2-migration-and-rollback-build-51)).
  Both are failures of the operation, not of the player.
- A profile that existed before the store was migrated receives its name at migration time (stable
  across restarts) and the policy type `classic`.

## Profile type registry (Build 54)

Build 54 code written; compiled by an agent; not tested by the owner. Full contract:
[PROFILE-TYPES.md](PROFILE-TYPES.md).

- `ProfileTypeRegistry` is a Bukkit service while MultiProfile is enabled
  (`Bukkit.getServicesManager().load(ProfileTypeRegistry.class)`, or `AuroraProfiles.typeRegistry()`, a default
  method that looks the service up and throws `UnsupportedOperationException` without it).
- Value types: `ProfileTypeDefinition` (with a `Builder`), `ProfileRule`, `ProfileNaming`, `ProfileRuleSet`,
  `ProfileTypeSource`. Error codes appended: `ACTION_FORBIDDEN_BY_TYPE`, `TYPE_UNKNOWN`.
- `register(plugin, definition)` / `unregister(plugin, id)`; the last definition stays in force (stored) when the
  plugin unregisters or disables, so a deny is never lifted by a missing plugin.
- `allows(typeId, action)` is synchronous (no I/O); `rules(profileId)` / `allows(profileId, action)` (also on
  `AuroraProfiles`) are asynchronous because they read the profile.
- `ProfileRequest.withTypeId(id)` creates a profile of a registered type (stored as `classic` plus the metadata entry
  `aurora:type`); `ProfileDetails.typeId()` returns the effective id.

## Co-op membership (Build 52)

Build 52 code written and compiled by an agent; not tested by the owner. Co-op is **off by default**:
`aurora.awf.multiprofile.coop.enabled` is `false`, and every method below then fails with
`ProfileErrorCode.COOP_DISABLED` (`getCoopDetails` too). All of them are `default` methods on
`AuroraProfiles` that fail with `UnsupportedOperationException` on an implementation built before
Build 52. `ProfileInfo`, `ProfileDetails` and `ProfileRequest` are unchanged; the co-op view is a
separate record. The arguments named `actorId`, `playerId` or `voterId` say who asks; the service checks
that account's role at the repository commit, but, as for every mutator in this API, a plugin must still
decide which player may ask for what.

| Method | Who | Result |
|---|---|---|
| `getCoopDetails(UUID profileId)` | anyone | `Optional<CoopDetails>`: label, members, size cap; empty for a solo or unknown profile |
| `convertToCoop(UUID actorId, UUID profileId)` | the profile's owner | `CoopDetails`. One way: a co-op profile never becomes solo again. The profile's policy type must be in `coop.allowed-types` |
| `invite(UUID actorId, UUID profileId, UUID inviteeId)` | owner, manager | `CoopInviteInfo` (state `OPEN`), expires `coop.invite-expiry-minutes` after issue |
| `acceptInvite(UUID playerId, UUID inviteId)` | the invitee | `CoopMemberInfo` of the new `MEMBER` |
| `declineInvite(UUID playerId, UUID inviteId)` | the invitee | `Void` |
| `cancelInvite(UUID actorId, UUID inviteId)` | owner (any invite of the profile), manager (its own) | `Void` |
| `listInvitesFor(UUID playerId, String cursor)` | the invitee | `CoopPage<CoopInviteInfo>` of open invitations |
| `listInvites(UUID actorId, UUID profileId, String cursor)` | owner, manager | `CoopPage<CoopInviteInfo>` of open invitations of the profile |
| `leave(UUID playerId, UUID profileId)` | the member | `Void` |
| `startKickVote(UUID actorId, UUID profileId, UUID targetId)` | owner, manager | `CoopKickVoteInfo` |
| `voteKick(UUID voterId, UUID voteId, boolean approve)` | a member of the frozen electorate | `CoopKickVoteInfo`; `false` declines and closes the vote |
| `getOpenKickVote(UUID profileId)` | anyone | `Optional<CoopKickVoteInfo>` |
| `listMembers(UUID profileId, String cursor)` | anyone | `CoopPage<CoopMemberInfo>`, owner first, then by join time |
| `setCoopLabel(UUID actorId, UUID profileId, String label)` | owner | `Void`; 1 to 32 characters (`NAME_INVALID` otherwise); a solo profile is `NOT_COOP` |

`convertToCoop` by anyone but the owner is `NOT_OWNED`. `listInvites` by a member that is not the owner or a
manager is `ROLE_FORBIDDEN`. Every call runs on the profile I/O lane and returns at once; nothing blocks the
calling thread.

Value types: `CoopRole` (`OWNER`, `MANAGER`, `MEMBER`), `CoopMemberInfo(playerId, role, joinedAtMillis,
lastSeenAtMillis)`, `CoopInviteInfo(id, profileId, inviter, invitee, createdAtMillis, expiresAtMillis,
state)` with `CoopInviteState` (`OPEN`, `ACCEPTED`, `DECLINED`, `CANCELLED`, `EXPIRED`),
`CoopKickVoteInfo(id, profileId, target, votes, electorateSize, createdAtMillis, expiresAtMillis, state)`
with `CoopKickVoteState` (`OPEN`, `PASSED`, `EXECUTED`, `DECLINED`, `CANCELLED`, `EXPIRED`),
`CoopDetails(profileId, label, members, maxMembers)` and `CoopPage<T>(items, nextCursor)`. Enums only gain
constants at the end.

**Permissions** (checked at the commit, not only in a command): the OWNER converts, invites, cancels any
invitation, starts and votes in kick votes, and sets the label. A MANAGER invites, cancels the invitations
it issued, starts and votes in kick votes, and may leave. A MEMBER may leave and vote when it is in the
electorate. Build 52 has no operation that changes a role: an invitee joins as `MEMBER`; `MANAGER` is
stored and read but cannot yet be assigned.

**Sizes and limits.** A co-op holds at most `coop.max-members` members (default 5, 2 to 8), owner
included; open invitations do not count, accepting does (`COOP_FULL`). A co-op membership uses one of the
account's profile slots (`SLOT_LIMIT` on accept). Lists are paged: a page holds at most 32 entries and the
cursor is opaque, bound to the listing and refused with `INVALID_REQUEST` when the underlying index
changed (restart from a `null` cursor). A reached store bound is `CAPACITY_EXCEEDED`.

**Kick vote.** The electorate is every member except the target, owner included, frozen when the vote
opens; approval must be unanimous. The starter's approval is counted when the vote opens, so a vote the
owner starts carries the owner's approval, while a vote a manager starts still needs the owner's. The
target never votes. One vote can be open per profile (`VOTE_OPEN`) and it expires `coop.kick-vote.
expiry-minutes` after opening. A membership or role change cancels an open vote (`ELECTORATE_CHANGED`);
a fresh vote is needed. In a **two-member** co-op the target must have been inactive for
`coop.kick-vote.inactive-days` or the start is refused with `TARGET_NOT_INACTIVE`; Build 52 records a
member's last activity at join time and on membership changes only, not from gameplay, so the rule
compares against that value. When the last approval arrives the removal commits in the same call, as a
second intent after the vote's: if that removal fails, the call fails with the removal's code, the vote
stays `PASSED`, and `voteKick(voter, voteId, true)` by an eligible voter retries the removal. Before a
vote is cast or a new one opened, an undecided vote that expired is closed (`EXPIRED`; the cast fails with
`VOTE_NOT_FOUND`) and one whose electorate no longer matches the members is cancelled (`CANCELLED`; the
cast fails with `ELECTORATE_CHANGED`). `getOpenKickVote` returns the undecided (`OPEN` or `PASSED`),
unexpired vote.

**Expired invitations.** An invitation expires by the store clock (Redis `TIME` with a Redis repository).
`invite` first closes, as `EXPIRED`, up to 32 expired invitations still indexed for the profile and for
the invitee, so the bound of 64 open invitations per profile and per invitee counts live ones; accepting
an expired one closes it and fails with `INVITE_EXPIRED`, and an accept whose commit lands after the
expiry fails the same way. A repeated `acceptInvite` of an invitation whose accept already committed
returns the same membership without writing.

**Names.** A profile's immutable name and each account's persisted alias stay as in Build 51 and Build
52 never changes them; the shared label (`CoopDetails.label()`, `setCoopLabel`) is a separate,
non-unique, owner-set text. Changing the label changes no member's alias.

**Refused** (nothing is written; the failure carries the code; the Build 52 rows that Build 53 relaxed are
marked):

| Operation | Code |
|---|---|
| any co-op operation while `coop.enabled` is false | `COOP_DISABLED` |
| any co-op operation on a format-1 store | `MIGRATION_REQUIRED` |
| removing a membership that is the removed account's **active** profile: Build 52 refused it. **Build 53** captures and switches the member out first, then removes; `MEMBER_ONLINE` remains only when that cannot be done here (the member's session is on another node) | `MEMBER_ONLINE` |
| owner transfer, the owner leaving, kicking the owner; deleting a co-op profile that still has other members | `OWNER_ACTION_REFUSED` |
| `switchProfile` into a co-op profile the player does not own: Build 52 only. **Build 53** allows it for a current member; a non-member gets `NOT_A_MEMBER` and a node that does not hold the co-op's bound world gets `WRONG_NODE` (OD-1) | `COOP_SWITCH_UNAVAILABLE` (Build 52) |
| a membership change (convert, label, invite, accept, leave, kick vote start or ballot) while another node holds the profile's claim, which marks the node playing its bound world (OD-1). Decline, cancel and the listings are not refused | `WRONG_NODE` |
| removing a member whose session is on another node (its account claim cannot be taken here) | `OWNERSHIP_CONFLICT` |
| converting a profile whose policy type is not in `coop.allowed-types` | `TYPE_NOT_ALLOWED` |

Other codes: `NOT_COOP`, `ALREADY_COOP`, `NOT_A_MEMBER`, `ALREADY_MEMBER`, `ROLE_FORBIDDEN`,
`INVITE_NOT_FOUND`, `INVITE_EXPIRED` (judged at commit time, not only when listed), `INVITE_EXISTS`,
`VOTE_NOT_FOUND`, `VOTE_OPEN`, plus the Build 51 `UNKNOWN_OUTCOME`, `CLAIM_LOST`, `COUNTER_EXHAUSTED`,
`CAPACITY_EXCEEDED`. After `UNKNOWN_OUTCOME` read the state back (`listMembers`, `listInvitesFor`,
`getOpenKickVote`) instead of repeating the call.

**Store level.** Before the first co-op record of a store is prepared, the store marker is raised once,
durably, to payload schema 2 and minimum build 52 (`format.awfm` gets a 4-byte trailer; the Redis `meta`
hash gets `schema 2` and a `minimumBuild` field). Build 51 then refuses the store at startup, and the
auto-updater refuses to install or restore a build below 52 (see `OPERATIONS.md`). A store that never
held a co-op record keeps its Build 51 marker.

**Removal.** Committing a leave or a passed kick vote is one recoverable intent (file and Redis): the
membership epoch is durably consumed, the account's slot is released and the member's personal state is
recorded as a salvage entry that is unavailable to delivery. **Nothing is delivered in Build 52 or 53**; Build 54
delivers it on a claim (`claimSalvage`, next section). Rejoining the same profile later is a new membership with a fresh epoch and an empty
personal state; the old epoch cannot be restored.

**Playing a co-op (Build 53).** A member `switchProfile`s into the co-op like any profile. The state is the member's
own (their member head and their own snapshots); members never share inventory, ender chest, experience,
health, location or adapter data. While authoritative login applies (`login.authoritative` `auto`, the default,
means: when `coop.enabled` is true; or `true`) a join restores the committed state of the active profile, or adopts a
newer vanilla playerdata into it once after an unclean session or the upgrade from Build 52, and is refused
(`HEAD_UNREADABLE` and the other codes the server reports) when neither can be done, rather than loading vanilla
playerdata; every vanilla player save of the server then publishes a profile snapshot, and `saveProfile` on a
format-2 store saves every member who plays the profile on that node (`PLAYER_OFFLINE` when none does).
`ProfileStateAdapter.migrate(fromVersion, data)` (default: unsupported) converts data stored under an older adapter
version; a REQUIRED adapter that cannot migrate refuses the apply (`ADAPTER_REQUIRED_FAILED`). The snapshot records
which stored adapter entries were REQUIRED, so a REQUIRED plugin that is absent later still refuses (`ADAPTER_MISSING`).
`leave` by a member whose session is live on another node fails with `MEMBER_ONLINE`.
New codes (appended, ordinals unchanged): `ADAPTER_REQUIRED_FAILED`, `ADAPTER_MISSING`, `HEAD_UNREADABLE`.
`COOP_SWITCH_UNAVAILABLE` is returned only while authoritative login does not apply (`false`, or `auto` with co-op off). Leaving or being kicked while the co-op is your
active profile is allowed: `CoopMemberLeaveEvent` still carries `LEFT` or `KICKED`, and fires after the
member was switched out and removed. See [PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md#member-switching-and-play-build-53).

**Not in Build 53** (see [BUILD-51-55-PLAN](../../releases/BUILD-51-55-PLAN.md)): shared bank, personal bank,
collections and salvage delivery (Build 54, next section; the purse stays per profile, OD-4); the audit log, switch guard and cooldown, ironman,
stranded and bingo rules, API parity fields and `/perf` metrics (Build 55). Ownership transfer is in none of
those builds. Co-op stays off by default.

### Events

`CoopMemberJoinEvent(profileId, playerId, role, inviteId)`, `CoopMemberLeaveEvent(profileId, playerId,
reason, voteId)` (`Reason.LEFT` or `Reason.KICKED`; `voteId` is null for `LEFT`) and
`CoopInviteEvent(invite)` (creation, decline and cancellation: read `invite().state()`; not fired for
acceptance, which is the join event, and not for expiry). They are asynchronous Bukkit events
(`isAsynchronous()` is true), not cancellable. Each is fired only after the change is durable and never
for a failed or `UNKNOWN_OUTCOME` operation; it is fired on the profile service's own co-op event thread
(`SourbyCraft-AWF-coop-events`), which is not a region, entity, global tick or Netty thread, so a handler
must not touch world, entity or player state directly and should hand work to the right scheduler. A
slow handler delays the next co-op event, not the storage work. Delivery is not guaranteed: it is fired
by the node that committed the change, at most 256 events wait for the thread (beyond that one is
dropped with a warning in the log), and a stop between the commit and the firing loses it. Treat the
store (`listMembers`, `listInvitesFor`) as the source of truth.

## Co-op bank, collections and salvage (Build 54)

Build 54 code written; compiled by an agent; not tested by the owner. Co-op must be enabled (`COOP_DISABLED`
otherwise) on a format-2 store (`MIGRATION_REQUIRED`), and the profile must be a co-op (`NOT_COOP`). All methods are
`default` methods on `AuroraProfiles` (`UnsupportedOperationException` on an implementation without them), run on
the profile I/O lane and never block the caller. Design: [COOP-DESIGN.md](COOP-DESIGN.md) §7, §8;
lifecycle: [PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md#co-op-bank-collections-and-salvage-build-54).

| Method | Who | Type action | Result |
|---|---|---|---|
| `bank(UUID viewerId, UUID profileId)` | a member, or `null` (operator) | none | `ProfileBankInfo` |
| `deposit(UUID actorId, UUID profileId, long amount)` | any member | `bank.deposit` | `ProfileBankInfo` |
| `withdraw(actorId, profileId, amount)` | OWNER, MANAGER | `bank.withdraw` | `ProfileBankInfo` |
| `depositPersonal(actorId, profileId, amount)` / `withdrawPersonal(...)` | any member, own personal bank | `bank.personal` | `ProfileBankInfo` |
| `transfer(actorId, profileId, ProfileBankAccount from, ProfileBankAccount to, amount)` | any member; `SHARED`→`PERSONAL` needs OWNER or MANAGER | `bank.transfer` | `ProfileBankInfo` |
| `applyInterest(actorId, profileId)` | OWNER | `bank.deposit` | `ProfileBankInfo` |
| `collections(UUID viewerId, UUID profileId)` | a member, or `null` | none | `List<ProfileCollectionInfo>` |
| `contribute(actorId, profileId, String collection, long amount)` | any member | `collections.contribute` | `ProfileCollectionInfo` |
| `salvage(UUID viewerId, UUID profileId, String cursor)` | a member, or `null` | none | `CoopPage<ProfileSalvageInfo>` (outstanding records, oldest first) |
| `claimSalvage(UUID claimantId, UUID salvageId)` | a member, online here, co-op active | `salvage.claim` | `SalvageDeliveryResult` |

**Money.** Amounts are `long` minor units of the bank record's `ProfileCurrency(providerId, currencyId, scale)`
(`1250` at scale 2 is 12.50) and must be positive (`INVALID_REQUEST`). Arithmetic is checked in Java: insufficient
available funds, a cap and a `long` overflow are refused before anything is written (`INVALID_REQUEST`). The personal
cap is `bank.personal-cap-percent` of the record's shared capacity (no cap when either is 0). A bank record keeps the
currency and shared capacity it was first written with; while the configured currency differs from a record's,
that bank's changes are refused (`INVALID_REQUEST`), reads still work. Personal banks are keyed by membership epoch:
a member who leaves and rejoins starts with a new, empty personal bank; the old balance is not paid out by the
server. The next bank change after a removal folds the removed membership's personal balance and contributions into
the record's archived totals (frozen, never credited, debited or paid interest; COOP-DESIGN §8), so the per-member
maps stay bounded by the current members (see Known gaps in PROFILE-LIFECYCLE.md).

**Who may.** A mutator's actor must be a member with this co-op as the **active** profile (`NOT_A_MEMBER`,
`STALE_STATE`; the repository checks the same at commit), the profile's type must allow the action
(`ACTION_FORBIDDEN_BY_TYPE`, [PROFILE-TYPES.md](PROFILE-TYPES.md); a `transfer` needs `bank.transfer`, `bank.personal`
and `bank.withdraw` out of or `bank.deposit` into the shared bank, and `applyInterest` credits personal banks only
while `bank.personal` is allowed, so a type that denies the bank denies every route into or out of it), and another
node must not hold the profile's bound world (`WRONG_NODE`, OD-1). A shared record reserved by a pending transfer refuses changes with
`RECOVERY_REQUIRED` until recovery resolves it. A bound of the shared record (256 collections, contributors per
collection, personal accounts) is `CAPACITY_EXCEEDED`.

**Commit and receipts.** `deposit`, `withdraw`, the personal postings, `applyInterest` and `contribute` are one direct
guarded commit of the shared record (`SHARED_MUTATE`); `transfer` is one recoverable intent (`BANK_TRANSFER`) whose
after-image holds both postings, so no half-moved amount exists after a crash. Each leaves a **permanent receipt**
that is never pruned; a resource that holds 1,000,000 of them refuses the next financial or delivery operation with
`CAPACITY_EXCEEDED` (archival is an offline owner policy). A call without an operation id mints a new one, so
repeating it posts again. Every ledger method has an overload taking the caller's own `operationId` (any UUID the
caller never used before, kept until the outcome is known), bound to a digest of the request (kind, actor, profile,
amount, accounts or collection, the actor's membership epoch; never a revision). **Retry contract:** the server looks
the id up *before* validating anything (the permanent receipt on the profile; for `transfer` also the stored intent),
and validates and runs the request only when the id is unknown. A known id is answered without checking the current
balance, capacity, interest interval, role, type rules or active profile:

| Stored under the id | Answer |
| --- | --- |
| the same request, committed | the bank as stored now; nothing posted again, no `ProfileBankTransactionEvent` (a withdraw retried after the balance moved, a deposit retried at the cap, interest retried inside the interval all complete this way) |
| the same request, aborted (transfer only) | `IO_FAILURE`: nothing was applied under the id; a new attempt needs a new id |
| the same request, undecided (a transfer intent awaiting recovery) | `RECOVERY_REQUIRED`: retry the same id after recovery |
| another request | `INVALID_REQUEST` ("already used for a different request") |
| any decision, but the actor is no longer a member | `INVALID_REQUEST`: the retry cannot be matched (the digest names the membership); `bankReceipt` tells the outcome |

Each of these failures names the id in `ProfileOperationException.operationId()`. A consumer that debited the purse
before `deposit` refunds it after a failure only when `bankReceipt(null, profileId, id)` shows no `COMMITTED`
receipt. A retried transfer stages nothing. An `UNKNOWN_OUTCOME` failure names its operation in
`ProfileOperationException.operationId()`; `bankReceipt(viewerId, profileId, operationId)` answers the permanent
receipt (`ProfileReceiptInfo`: kind, committed or aborted, decided-at) or empty while the store holds none. Read
`bank()` back as well; never repeat without the id. `applyInterest` posts when the OWNER calls it, no sooner than
`bank.interest-interval-hours` (default 24) after the previous posting on the store clock (`INVALID_REQUEST` naming
the wait; the record remembers its last posting); the server schedules no interest.

**Purse (owner decision OD-4).** The purse stays per profile through its REQUIRED state adapter. `ProfileBankAccount`
has only `SHARED` and `PERSONAL`; there is no purse↔bank transfer in the server (`/awf multiprofile coop bank transfer`
naming the purse fails with `PURSE_TRANSFER_UNSUPPORTED`). A consumer that offers "deposit coins" debits its purse
itself and then calls `deposit` (and `withdraw` before crediting the purse). The two steps are not one transaction:
the consumer owns their ordering and its own compensation.

**Salvage (owner decision OD-3).** `claimSalvage` delivers a removed membership's items (inventory, armour, off-hand,
ender chest, in that order) into free inventory slots, then free ender-chest slots, of the claimant's captured state;
the claimant's head, the salvage record and the permanent receipt commit in one intent, and only then is the state
applied to the online player. What does not fit stays in the record (`remainingStacks`, the record's info shows
`partial`); with no free slot at all the claim fails with `CAPACITY_EXCEEDED` and nothing changes; a delivered record
fails with `INVALID_REQUEST`. The claimant must be online on this node (`PLAYER_OFFLINE`) with the co-op active
(`STALE_STATE`), and no switch or login restore may be running (`SWITCH_IN_PROGRESS`). From admission until the
committed state is applied, the claimant holds a delivery hold that every save path honours like a running switch
(lockstep autosave, quit, shutdown capture and `save()` write nothing, `SWITCH_IN_PROGRESS`; no switch is admitted),
so no pre-delivery capture overwrites the destination head. The capture is bounded by `switching.timeout-ms` like a
switch's steps: a capture that does not finish in time fails the claim with `TIMEOUT`, nothing written and the hold
released. A rejoin of the claimant starts unfrozen; a claim of the previous session can no longer commit
(`CLAIM_LOST`). `applied == false` means the delivery is committed but the
apply to the player failed: the hold stays until their session ends, they are disconnected, their quit writes nothing
and the next authoritative login restores the committed state with the items. The first delivery that is about to be
written raises the store to minimum build 54 (a refused claim raises nothing).

**Events.** `ProfileBankTransactionEvent(profileId, actorId, type, collection, from, to, amount, sharedBalanceAfter,
currency, operationId)` with `Type` `DEPOSIT`, `WITHDRAW`, `DEPOSIT_PERSONAL`, `WITHDRAW_PERSONAL`, `CONTRIBUTE`,
`INTEREST` (amount = total credited), `TRANSFER`; and `ProfileSalvageDeliveredEvent(result)`. Both are asynchronous,
fired on the co-op event thread after the durable commit only, with the same delivery limits as the co-op events
above. `ProfileSalvageDeliveredEvent` fires before the apply to the player, so its `result().applied()` is `true`; the
`claimSalvage` result is the one that reports a failed apply.

New codes (appended): `PURSE_TRANSFER_UNSUPPORTED`. New value types: `ProfileBankInfo`, `ProfileCurrency`,
`ProfileBankAccount`, `ProfileCollectionInfo` (with `Contribution`), `ProfileSalvageInfo`, `SalvageDeliveryResult`.

## Compatibility

`AuroraWorlds` keeps its exact contract. `AuroraProfiles` is a new, separate service; plugins that do
not use profiles do not depend on Redis or on this API. The newer methods (`bindProfileWorlds`,
`setProfileLocked`, `setProfileSlotLimit`, and in Build 51 `getProfileDetails` and
`listProfileDetails`) are `default` methods that fail with `UnsupportedOperationException` on other
implementations. Build 51 changes no existing signature: `ProfileInfo` and the three-argument
`ProfileRequest` constructor are as before, `ProfileRequest` gained two optional components, and
`ProfileErrorCode` constants were only appended. Build 52 adds the co-op `default` methods listed above and changes no existing
signature; `ProfileDetails` is unchanged and the co-op view is `CoopDetails`. Build 54 only appends: the type registry, the
bank/collections/salvage `default` methods, new records and events, and the codes `ACTION_FORBIDDEN_BY_TYPE`,
`TYPE_UNKNOWN`, `PURSE_TRANSFER_UNSUPPORTED`; `ProfileDetails` gained the method `typeId()`.

## Build 50 world-binding API (owner testing pending)

The binding API and the bind/unbind commands landed in PR #61, after the `v26.2-r50` tag, so they are
absent from the published prerelease jar; the lock and slot methods landed in PR #62 (code written and
statically reviewed; not built; not tested by the owner).

```java
var request = new ProfileWorldBindingRequest(profileId,
    Map.of("island_50", ProfileWorldRole.PRIMARY), "starter_template");
profiles.bindProfileWorlds(request).thenCompose(ignored -> profiles.getProfileWorlds(profileId));
```

Use `dev.iyanz.sourbycraft.api.profile` types. This replaces the entire binding (0–16 worlds),
without creating or deleting worlds. Empty map unbinds. Names are `[a-z0-9_-]{1,48}`. Only an inactive
profile in status `ACTIVE` (not `LOCKED`, not deleted) that no process holds can be rebound, outside an
account transition and with no pending journal. Roles describe relationships, not access permissions;
the consumer must authorize player requests and enforce shared-world membership itself.

World creation and loading happen on activation and on the join of a player whose active profile has
bindings, never at boot; a missing world is created from the binding's template. With
`isolation.location` on:

- a profile without a stored location is entered at the spawn (block-centred) of its first PRIMARY
  world; when that spawn is not known the player stays where they are with a warning, and the switch
  still succeeds. The PRIMARY spawn is also the last fallback destination of a switch;
- on join, a player outside the bound worlds while one of them was unloaded is returned to the
  profile's last location in them, or to its PRIMARY spawn when none is stored, bounded by
  `switching.timeout-ms` and only while no journal is pending. That destination may be older than the
  last session when no quit snapshot was written (a crash, or a shutdown capture that missed its deadline). A switch that starts before the join
  teleport takes its reservation wins; one started after it is refused with `SWITCH_IN_PROGRESS` until
  the teleport ends.

Admin commands: `/awf multiprofile bind <profile-uuid> <world> [template]` replaces the binding with
one PRIMARY world (names lower-cased); `/awf multiprofile unbind <profile-uuid>` clears it. Both require
`sourbycraft.command.awf.multiprofile.admin` in addition to normal AWF permissions.

Account and profile metadata writes compare the stored revision: within one process for the file
repository, atomically with the claim check for the Redis repository (EXPERIMENTAL; see
[DISTRIBUTED-OWNERSHIP.md](DISTRIBUTED-OWNERSHIP.md)). A save revalidates the account revision and the
exact claim checked before the capture, so a capture spanning A→B→A is rejected. Recovery holds the
player's transition reservation until its future completes. Owner regression cases are in
[Tester Owner](../../tester%20owner.md).
