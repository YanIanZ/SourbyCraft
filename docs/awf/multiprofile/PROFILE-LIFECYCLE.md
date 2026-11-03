# Profile lifecycle

The Build 50 PR #62 behaviour on this page (switch order, freeze, compensation, recovery rules,
pre-profile archive, lock and slot commands) is code written and statically reviewed; not built; not
tested by the owner, and not in the published `v26.2-r50` prerelease. Owner results from earlier trees
are NEEDS RETEST ([TESTING.md](TESTING.md)).

## States

A profile is `ACTIVE`, `LOCKED` or `DELETED`. `LOCKED` is an administrative hold: it is listed but
cannot be switched to or rebound. It is set with `/awf multiprofile lock|unlock <profile>` (or
`AuroraProfiles.setProfileLocked`), on inactive, non-deleted profiles only, refused during a switch or
while a journal of the account is pending, and it leaves the revision unchanged so the latest snapshot
does not become stale. Deleting the active profile is refused (`PROFILE_LOCKED`); delete is a tombstone
(the profile record remains) whose snapshots and binding are removed. AWF worlds are never deleted by a
profile delete.

An account stores `profileIds`, `activeProfile`, `slotLimit` and a revision. Creation is refused when
the account is full (`SLOT_LIMIT`), and `max-slots` is a hard ceiling even for an account whose stored
limit is higher. `/awf multiprofile slots <player> <n>` (or `AuroraProfiles.setProfileSlotLimit`) sets
an account's limit within 1..`max-slots`; profiles above a lowered limit are kept. Account and profile
writes compare the stored revision: within one process for the file repository, atomically in the write
script for the Redis repository (`STALE_STATE` on a conflict).

## Name and policy type (Build 51)

Build 51 code written; not built by an agent; not tested by the owner. A profile gets a name and a
policy type when it is created, both stored in the profile record (store format 2) and neither ever
changed afterwards: no rename, no type change. The name is unique per account, ignoring case; creation
fails with `NAME_TAKEN`, `NAME_INVALID` or `TYPE_NOT_ALLOWED`. Name and type play no part in switching,
snapshots, recovery or ownership, and no gameplay rule is attached to the type in this build. Co-op
membership (several players on one profile) is Build 52, described in
[Co-op membership](#co-op-membership-build-52).

On a format-2 store, creating and deleting a profile are each **one intent** (COOP-DESIGN §5), not two
separately guarded writes: the engine stages the after-images (create: the new profile record at revision
0 and the account with the slot and alias; delete: the DELETED record and the account without the slot),
PREPARE reserves the records under the player's session claim `Q` (delete also under the profile's
transaction claim `P`), and COMMIT installs them. A server stopped between the two phases leaves a
PREPARED or COMMITTED intent that the next start, or the player's next login, resolves: an uncommitted
one is aborted (its reservations cleared, nothing applied), a committed one rolls forward. The store
checks the name is still free in the account at PREPARE and again at COMMIT. Login admission is the
`LOGIN` operation, a slot-limit change `ACCOUNT_LIMIT`; both are single guarded account writes. A reached
store bound (pending intents per account, profile or store; permanent receipts of a resource) is
`CAPACITY_EXCEEDED`, a reached revision or fence counter `COUNTER_EXHAUSTED`.

## Co-op membership (Build 52)

Build 52 code written and compiled by an agent; not tested by the owner. Off by default
(`coop.enabled: false`, [CONFIGURATION.md](CONFIGURATION.md#build-52-co-op-membership)); with it off no
co-op record is written. Everything below needs a format-2 store (`MIGRATION_REQUIRED` otherwise) and
applies to the file and the Redis repository alike.

**Conversion.** The owner converts a solo profile to co-op. It is one way: a co-op profile never returns
to solo. The profile's policy type must be in `coop.allowed-types` (default classic only) or the conversion
is refused with `TYPE_NOT_ALLOWED`. Before conversion the profile has exactly one member, the owner.

**Roles.** OWNER (unique, the profile's account), MANAGER, MEMBER. The permission each role has is checked
at the repository commit ([API.md](API.md#co-op-membership-build-52)). Nothing in Build 52 changes a role;
an accepted invitee is a MEMBER.

**Invitations.** `OPEN` becomes `ACCEPTED`, `DECLINED`, `CANCELLED` or `EXPIRED`. An owner or manager
issues one for an account that is not a member and has no open invitation to this profile; it expires
`coop.invite-expiry-minutes` later. Expiry is judged at commit time against the repository clock, not only
when listing, and a clock that moves backwards does not revive an expired invitation. An open invitation
does not reserve a seat.

**Accepting** is one recoverable intent: it consumes the invitation, allocates a fresh membership epoch,
creates the membership and adds the profile to the invitee's account, together. The intent records the
epoch it allocated, so a replay after a crash or a lost reply returns the same membership. The accept
re-checks, at commit, the invitation (open, unexpired), the size cap (`COOP_FULL`), the invitee's slot
limit (`SLOT_LIMIT`, a co-op membership uses one slot) and the alias it will show (an account's profile
names are unique per account; the alias a member sees is stored with that account and is independent of
the profile's name and of the shared label). Joining a co-op whose bound world is held by another node is
refused with `WRONG_NODE` (OD-1: Build 52 never loads the same world on two nodes); so is every other
membership change (conversion, label, invitation, leave, kick vote) while another node holds the profile's
claim. An accept of an invitation that already expired closes it and fails with `INVITE_EXPIRED`; repeating
an accept that already committed returns the same membership without writing. A member who just joined
**cannot switch into the profile** in Build 52: `switchProfile` into a co-op profile the player does not own
is refused with `COOP_SWITCH_UNAVAILABLE` until Build 53 adds member snapshots and login restore.

**Leaving and removal.** A member leaves; or a kick vote removes a member. Both are one recoverable intent
that consumes the membership epoch, releases the account's slot and records the member's personal state as a
salvage entry that is unavailable to delivery. Build 52 delivers nothing (salvage delivery is Build 54); a
removed member's rejoin is a new membership with a fresh epoch and an empty personal state, never the old
epoch. **A membership that is the member's active profile is not removed** in Build 52 (`MEMBER_ONLINE`;
enabled in Build 53); a member who is online but active in another profile, or offline, can leave and can
be kicked. Since Build 52 never makes a co-op profile the active profile of anyone but its owner, and the
owner cannot be removed, this refusal is reached only through an inconsistent account. A kick holds the
removed account's session claim as well as the actor's: a target whose session is on another node is
refused (`OWNERSHIP_CONFLICT`) rather than having its claim taken. The owner cannot leave, be kicked or
transfer ownership (`OWNER_ACTION_REFUSED`), and cannot delete a co-op profile that still has other
members (`OWNER_ACTION_REFUSED`; they leave or are kicked first).

**Kick vote.** Opened by the owner or a manager against a non-owner member who is not the starter. The
electorate is every other member (owner included), frozen at opening; approval is unanimous; the starter's
approval is counted when the vote opens, so a vote the owner starts already has the owner's approval. Any
membership or role change cancels the open vote (`ELECTORATE_CHANGED`, `CANCELLED`); one vote per profile
may be open; it expires `coop.kick-vote.expiry-minutes` after opening; a decline closes it. In a
two-member co-op the target must have been inactive for `coop.kick-vote.inactive-days` first. When the
last approval is recorded the vote becomes `PASSED` and a second intent, the removal, commits in the same
call; the vote becomes `EXECUTED` in that removal's commit, so a retry never creates a second salvage entry.
If the removal fails the vote stays `PASSED` and approving it again retries the removal. A ballot or a new
vote first closes an undecided vote that expired (`EXPIRED`) or whose electorate changed (`CANCELLED`).

**Store level.** The first co-op intent of a store raises the store marker to minimum build 52 before it is
prepared ([OPERATIONS.md](OPERATIONS.md#enabling-co-op-build-52)); Build 51 then refuses the store.

**Label.** The owner sets a shared display label (1 to 32 characters, not unique). It is separate from the
profile's immutable name and from every member's alias and changes neither.

**Recovery.** A membership intent that a crash left PREPARED or COMMITTED is resolved at the next start or
the affected player's next login, as the create and delete intents are above: uncommitted ones abort with
their reservations cleared, committed ones roll forward. A kick intent names two session claims (the actor's
and the removed member's); recovery takes both. A result of `UNKNOWN_OUTCOME` means the store did
not confirm the write; read the state back instead of repeating the call.

## Snapshot revisions

A snapshot records `revision = profile.revision + 1`, and the profile is advanced to it in the same
publish (file: snapshot durable first, then the record; Redis: one script). Applying a snapshot
requires `snapshot.revision >= profile.revision`; a lower revision is `STALE_STATE` and the switch is
refused before anything is applied. Three snapshots per profile are kept, plus every snapshot a pending
journal of the account names (its rollback point or the target it applied). The newest wins: in the file
repository the highest revision, and of equal revisions the newer file; in Redis the snapshot the
profile record names as `latest`.

On a player's first switch (no active profile yet) the captured pre-profile state is archived, not
adopted into the target: `accounts/<player>.legacy.awfp` (an earlier archive is kept as
`<player>.legacy.<millis>.awfp`), or the player's `mp:legacy:<player>` hash in Redis. It is the first
switch's rollback point, is never deleted automatically, and has no in-game restore. Adopting it into
the first profile instead is an open owner decision.

## Switch state machine

Each phase is written to the journal when the switch reaches it, before that phase's work starts;
`SAVING` is written once the rollback point is durable:

| Phase | Work | Crash behaviour |
|---|---|---|
| `VALIDATING` | player online (the account, target and pending-journal checks run just before this first write) | nothing applied; recovery removes the journal |
| `PREPARING_TARGET` | claim the target, make its AWF worlds ready (bounded); nothing captured | nothing applied; recovery removes the journal |
| `FREEZING` | freeze the player, claim the previous profile, capture in one entity task, write the rollback point (the previous profile's snapshot, or the pre-profile archive) | nothing applied; recovery removes the journal |
| `SAVING` | rollback point durable; read the target's latest snapshot (`STALE_STATE` check) | nothing applied; recovery removes the journal |
| `APPLYING_TARGET` | journal records the target snapshot (`WAITAOF` with Redis), then the target state is applied on the entity thread | the player may hold the target state: rollback to the journal's exact rollback point unless the account already names the target |
| `TELEPORTING` | async teleport to the last location, else the respawn point, else the PRIMARY world spawn; unreachable destinations fall back with a warning (only with `isolation.location`) | same as above |
| `COMMITTING` | validate the target claim, write `activeProfile` | **commit point**: account names the target → forward; otherwise rollback |
| (committed) | release the previous claim, delete the journal (no `ACTIVE` write) | a journal left at `COMMITTING` completes forward |

`RECOVERING` means the target state may be on the player and the rollback has not completed (the
player is offline, or the rollback failed or timed out); the next join or `recover` retries it.
`FAILED` is a terminal record (the switch failed before the apply, its rollback completed, or no
rollback point exists): it blocks nothing, is listed in `/awf multiprofile status`, and the newest 5
terminal journals per player are kept. `recover` on a `FAILED` (or a legacy `ACTIVE`) journal removes it
and reports what it recorded.

A failure before `APPLYING_TARGET` applies nothing: the captured state is not re-applied, because the
player's live state is still the previous profile's. A failure after it rolls the player back online
to the captured state within a fresh `switching.timeout-ms`; if that cannot complete, the journal stays
`RECOVERING` and the result is `RECOVERY_REQUIRED`. A target apply that has not started when the switch
fails does nothing. A teleport cannot be cancelled: one that completes after its timeout still moves the
player (the state is rolled back, the location is not).

With the Redis repository, a commit whose reply is lost is settled first: a new claim on the target
supersedes the one the commit was sent under, then the account decides (applied → the switch completes;
not applied → rollback; unknown → `RECOVERING` without rolling back). See
[DISTRIBUTED-OWNERSHIP.md](DISTRIBUTED-OWNERSHIP.md#a-lost-commit-reply).

## Recovery rules

`/awf multiprofile recover <transition>` (permission `sourbycraft.command.awf.multiprofile.admin`) and
every join run these for each incomplete journal of the player:

1. If the account already names the target, the switch committed: delete the journal (forward).
2. If the journal never reached `APPLYING_TARGET`, nothing was applied: delete the journal and report
   `RECOVERED` (with distributed ownership, under a fenced claim on the target, so a switch still
   running on another node keeps its journal).
3. Otherwise roll back to exactly the journal's rollback point (`fromSnapshotId`): the previous
   profile's snapshot, or on a first switch the archived pre-profile state; point the account back at
   the previous profile, delete the journal and report `RECOVERED`. The rollback is bounded by
   `switching.timeout-ms`; a timeout leaves the journal pending.
4. When no rollback point was recorded, or it no longer exists, the journal becomes `FAILED` and
   `RECOVERY_REQUIRED` is returned: nothing is guessed, there is no empty-state fallback. When the
   player is offline, the journal stays `RECOVERING` and the next join retries.
5. Recovery writes under a claim on the profile it makes authoritative: the player's own session claim
   when it is on that profile and still valid, otherwise a fresh claim. A journal whose recorded fence
   on that same profile (`toFence` forward, `fromFence` for a rollback) is ahead of the fresh claim is
   refused (`OWNERSHIP_CONFLICT`) and nothing is written. Fences only rise, so this means the counter
   went back (for example Redis lost epochs in a restart or failover, see
   [DISTRIBUTED-OWNERSHIP.md](DISTRIBUTED-OWNERSHIP.md#durability-is-rediss)). Fences are compared only
   when they come from the same ownership incarnation (local provider: one process; Redis: shared;
   journals of earlier builds only under Redis).

While a journal of the account is pending, switch, save, quit save, delete, bind and lock are refused
with `RECOVERY_REQUIRED`. An unreadable journal blocks only its player (`IO_FAILURE`) until an operator
removes it ([OPERATIONS.md](OPERATIONS.md#troubleshooting)).

## Guarantees and non-guarantees

- A crash cannot leave two authoritative profiles: the account's active-profile write is the commit
  point.
- Inventory duplication through the switch requires the target state to be applied while the rollback
  point was not durable. `switching.require-durable-save: true` (default) refuses that case, including
  the first switch's archive; setting it to `false` explicitly allows a switch without a rollback point.
- **Not guaranteed:** a crash after the commit and before the server saves vanilla playerdata
  (autosave or quit) can leave playerdata with the previous profile's state while the account names the
  target (PM-028 / MP-COMMIT-VANILLA, open owner decision).
- A crash between writing a new profile record and adding it to the account leaves an unreferenced
  profile record. It is never listed and is safe to delete; no player state is lost.
- Cross-store atomicity (profile records + AWF world commits + Redis claims) is **not** claimed. A
  world may have been saved while the profile switch rolled back; that leaves the world in its saved
  state, not the profile.
- Cancelling the future returned by `switchProfile` does not stop the engine's work and does not
  release ownership; recovery is the only way to resolve an interrupted run.
