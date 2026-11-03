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
could not switch into the profile in Build 52 (`COOP_SWITCH_UNAVAILABLE`); Build 53 allows it, see
[Member switching and play](#member-switching-and-play-build-53).

**Leaving and removal.** A member leaves; or a kick vote removes a member. Both are one recoverable intent
that consumes the membership epoch, releases the account's slot and records the member's personal state as a
salvage entry that is unavailable to delivery. Build 52 and 53 deliver nothing; Build 54 delivers on a claim
([salvage](#salvage-exactly-once-od-3)); a
removed member's rejoin is a new membership with a fresh epoch and an empty personal state, never the old
epoch. A member whose **active** profile is the co-op was refused in Build 52 (`MEMBER_ONLINE`); Build 53
captures and switches them out first, then removes them, see
[Removing an online member](#removing-an-online-member-build-53). A kick holds the
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

**Store level.** The first switch journal of a *non-owner* member into a co-op raises the store marker to minimum
build 53 before it is written (Build 52's rollback of such a journal would commit a co-op pointer for a non-owner
and then refuse it, leaving the account stuck); a store without one keeps the Build 52 marker. Session markers (below)
are stored under a record type Build 52 never reads and are not part of the level. The first co-op intent of a store raises the store marker to minimum build 52 before it is
prepared ([OPERATIONS.md](OPERATIONS.md#enabling-co-op-build-52)); Build 51 then refuses the store.

**Label.** The owner sets a shared display label (1 to 32 characters, not unique). It is separate from the
profile's immutable name and from every member's alias and changes neither.

**Recovery.** A membership intent that a crash left PREPARED or COMMITTED is resolved at the next start or
the affected player's next login, as the create and delete intents are above: uncommitted ones abort with
their reservations cleared, committed ones roll forward. A kick intent names two session claims (the actor's
and the removed member's); recovery takes both. A result of `UNKNOWN_OUTCOME` means the store did
not confirm the write; read the state back instead of repeating the call.

## Member switching and play (Build 53)

Build 53 code written; not tested by the owner. Everything here
needs `coop.enabled: true`, a store in format 2 and authoritative login (`login.authoritative` `auto`, which co-op
enables, or `true`).

**Switching in.** A member of a co-op can `switchProfile` into it. The switch is refused with `NOT_A_MEMBER` for
an account that is not a current member (a removed member included) and with `WRONG_NODE` on a node that does not
hold the co-op's bound world: OD-1 holds for play as it does for membership, a member plays only on the node
holding the world and nothing routes a player to another node. Each member has their own state stream in the
co-op: a **member head** names the member's current committed snapshot for their membership epoch, and
snapshots are kept per member, so two members never read or overwrite each other's inventory, ender chest,
experience, health, location or adapter data. A member who joins starts with an empty personal state; nothing
is copied from another member or from the member's other profiles.

**Capture and save.** The server's own player save (the autosave and the per-player interval save, `PlayerSaveEvent`),
the quit and the shutdown capture use a ticket minted before the entity is read; while authoritative login applies,
every vanilla player save publishes the same capture to the member stream in lockstep, at most one pending per
player, so the profile head and `players/data/<uuid>.dat` are written together. MultiProfile has no save timer of its
own: without authoritative login only quit, switch, shutdown and the API `saveProfile` write snapshots (Build 52
behaviour). The ticket binds the
capture to the player's session generation, the account's active profile, the membership epoch and the profile
revision it started from; the save is refused if any of them changed in between. A capture taken in profile A
therefore cannot be written to profile B after an A to B to A round trip, and a capture of a membership that has
since been removed cannot be written to a later membership. Entity state is read and applied only on the entity's
owning thread; storage is never awaited on it.

**Rollback.** A switch that fails after the target state was applied rolls the player back to exactly the
snapshot the journal pinned (`fromSnapshotId`), not to whatever is newest. For a co-op this is the member's own
snapshot. Pins are counted across every pending switch and membership intent, so pruning cannot remove a
snapshot an unresolved intent needs.

**Uncertain commits.** When the store does not confirm the commit of the active-profile write
(`UNKNOWN_OUTCOME`), the switch does not guess: it rereads the authority (the account's active pointer and the
member head) and then completes forward or rolls back from what the store holds. A result that still cannot be
read leaves the journal in `RECOVERING`.

**Required adapters.** A [REQUIRED adapter](PROFILE-ISOLATION.md#adapter-contract-build-53) is part of the switch:
if its capture or apply fails the switch is refused (`ADAPTER_REQUIRED_FAILED`) or journaled for recovery, and one
that holds data for the profile but is not registered on this node refuses it (`ADAPTER_MISSING`). A skip is never
reported as success. BEST_EFFORT adapters behave as in Build 52 except that their previously stored bytes are
preserved when a capture fails.

## Authoritative login (Build 53)

`login.authoritative` is `auto` (default: authoritative exactly when `coop.enabled` is true), `true` or `false`.
While it applies, a join does not trust vanilla `players/data/<uuid>.dat` (the 26.2 player data directory,
`LevelResource.PLAYER_DATA_DIR`; `playerdata/` before) for a player whose account names an active
profile. The join holds the player's gameplay gate, takes the player's session claim, resolves any pending journal or
membership intent of the account, reads the **committed head** of the active profile (for a co-op the member head),
decides between restore and adoption from the **session marker** (below), checks that the required adapters are
present, replaces the player's isolated state on the entity thread (or publishes the adopted state) and only then
releases the gate. The gate is bound to the join: a player who quits and rejoins while it runs gets a new gate, and the
old chain applies nothing to the new connection.

**Session marker.** A small per-account record outside the store format (`sessions/<uuid>.awfp` in the file
repository, `session:<uuid>` in Redis; Build 52 never reads it) says "session of generation *g* open on node *n*",
plus the *vanilla provenance*: the profile and epoch this node's `players/data/<uuid>.dat` is known to hold, and when.
The admission of an authoritative login carries the previous session's marker to the new generation unchanged, so a
refused join (claim, head, journal) never turns the next reading into "another generation"; the login then writes it
open with the provenance cleared when it restores the head (a marker that cannot be written refuses the join), or open
with the provenance set once an adoption committed (a refused adoption leaves it as carried, so the next login retries
the adoption). It gains its provenance at every lockstep save, loses it when a switch is about to apply another
profile, and is closed by the committed quit or shutdown capture. A capture or save that is refused leaves it open. A
marker that cannot be read is replaced at admission by an open one without provenance, with a WARN: that login
restores the committed head (the safe choice; `/awf multiprofile session <player> reset-marker` forces the same).

**Restore or adopt (D3).** The previous session counts as *unclean* when the marker is still open for the previous
generation (crash, `kill -9`, a shutdown that missed its deadline, a refused save), names another generation
(sessions ran under Build 52 or without authoritative login), or does not exist (the upgrade from Build 52). Then,
and only then, the join compares this server's `players/data/<uuid>.dat` modification time with the head snapshot's
`createdAtMillis` (an `UNINITIALIZED` head counts as never written). When the file is newer, no journal was
recovered at this login, and the marker's provenance names the active profile and epoch on this node (an absent or
other-generation marker is read with Build 52 semantics: vanilla holds the active profile's live state), the state
the join loaded is captured on the player's thread and published as the head's next snapshot under the login's
ticket: **adopted**, nothing is applied, and the empty initial state is never applied over existing vanilla data.
Otherwise the committed head is restored and progress since it is lost (never duplicated). The two clocks are the
filesystem's and the writer's wall clock on the same machine; a quit's vanilla save and its snapshot hold the same
state, so an adoption triggered by their ordering changes nothing.

- A head that cannot be read (damaged, unreadable store, missing snapshot a head names) **refuses the join** with
  `HEAD_UNREADABLE` and a message to the player. Vanilla playerdata is never used as a fallback, because loading
  it could resurrect items already moved elsewhere.
- A pending journal that recovery could not resolve, a session claim that failed or was not granted, a recovery that
  failed, a REQUIRED adapter that is absent or cannot migrate its data, an adoption that cannot be published
  (`STATE_TOO_LARGE`, a reserved stream): each **refuses the join** with its code. The gate stays held until the
  disconnect is final, so nothing of the vanilla state is captured; a plugin that cancels the kick leaves the player
  frozen with their saves refused (the kick is retried five times, five ticks apart, then logged).
- A profile that has never been captured (a co-op member's first join: head `UNINITIALIZED`) with no adoption is
  initialised to an empty state under a guarded write; the player's current inventory is not captured into every
  newly joined co-op.
- A player who is not in any profile (no active profile) is unaffected: vanilla data stays.
- **Fail closed during play (D4).** A lockstep or quit capture that fails with a code that repeats until someone acts
  (`STATE_TOO_LARGE`, the aggregate adapter budget, a REQUIRED adapter failure) freezes the player (item events
  cancelled, switches refused) and disconnects them; the quit capture is attempted once more, and when it fails the
  marker stays open, so the next login re-examines the vanilla playerdata the server wrote at the quit. Other
  failures (I/O, a stale ticket) are logged and retried by the next save.
- With `login.authoritative: false`, or `auto` while co-op is off, the join keeps Build 52 behaviour: the player
  keeps whatever vanilla playerdata loaded, unless a pending switch is recovered, no session marker is written and no
  lockstep save runs. This keeps the crash window described under [Guarantees](#guarantees-and-non-guarantees) and
  co-op members cannot switch into a co-op. Turning authoritative login on later is handled by the marker rule above
  (absent or other-generation marker: Build 52 semantics, so a newer vanilla playerdata is adopted at the first join).
- Running Build 52 again after Build 53 wrote markers leaves markers of an older generation; the next Build 53 login
  adopts a newer vanilla playerdata under Build 52 semantics. A store that holds a non-owner member switch journal is
  marked minimum build 53 and Build 52 refuses it.

## Removing an online member (Build 53)

`leave` and a passed kick vote work for a member whose active profile is the co-op:

1. The server takes the player's session claim and gates the player.
2. It captures the player through the same ticketed capture as a quit, and saves it as the member's last snapshot
   in this membership epoch (the snapshot a Build 54 salvage claim reads).
3. It switches the player out: to the account's next profile, or to a fresh solo profile when the account has no
   other one. The switch is the ordinary switch, with its journal, rollback and adapter rules.
4. Only after the switch committed does the removal commit: the membership epoch is consumed, the slot is freed,
   the salvage entry is recorded and `CoopMemberLeaveEvent` fires. Item ownership moves once: the captured items
   are in the member's last co-op snapshot or in the player's new state, never both.

A failure in step 2 or 3 removes nothing and reports the reason; the removal intent is retried, not repeated
blindly. `MEMBER_ONLINE` remains for a member whose session is on another node (their gate cannot be taken
here). The owner still cannot leave or be kicked.

## Shutdown capture (Build 53)

Before the server saves and removes players at shutdown, MultiProfile captures every online player of an active
profile through the same ticketed capture as a quit, saves it, and only then closes (PM-076; feature patch 0081
orders it). In Build 52 the service was closed first and these players got no quit snapshot.

- It is bounded by `shutdown.capture-deadline-ms` (default 10000): one budget, started when the adapter capture
  runs before the plugins are disabled (a quarter of it) and shared with the state captures and saves after; the
  engine's close then waits for the saves still on the storage lane until that same deadline (never less than 10 s).
  Players are gated first, so no new switch or capture starts for them; unfinished switches keep their journal.
- Saves run one at a time on the storage lane (the engine's I/O lane serialises every claim and CAS check); shutdown
  does not batch them. Each save closes the player's session marker; a save that did not finish leaves it open.
- A plugin adapter's `capture()` runs synchronously on the shutdown thread and cannot be interrupted; one that takes
  longer than 500 ms is named in the log. The contract asks adapters to return from their own data only.
- A player whose capture and save finished is exactly as after a normal quit.
- A player whose save did not finish by the deadline keeps the last committed snapshot, and any pending
  journal or intent, which the next join resolves (authoritative login does not load vanilla data over them).
  Progress since that snapshot is lost; this is logged per player. Nothing is reported as saved that was not.
- Shutdown then continues; the deadline does not extend the existing storage drain budget in
  [OPERATIONS.md](OPERATIONS.md#shutdown).

## Co-op bank, collections and salvage (Build 54)

Build 54 code written; compiled by an agent; not tested by the owner (no unit, integration or boot result is claimed
here). Design: [COOP-DESIGN.md](COOP-DESIGN.md) §5, §7, §8, §9; API: [API.md](API.md#co-op-bank-collections-and-salvage-build-54);
policy keys: [CONFIGURATION.md](CONFIGURATION.md#build-54-co-op-bank-policy). Co-op stays off by default.

### The shared record

A co-op profile's bank lives in one shared-state record `S` (format-2 record type 6, the Build 51 shape): the shared
balance and capacity, one personal balance per membership (`member, epoch`), the collections with their per-member
contribution ledger, pending transfer reservations, and the fixed currency. Money is signed-`long` minor units;
every amount is decided in Java before anything is written (positive amounts, available balance, caps, no `long`
overflow; refusals are `INVALID_REQUEST`). The record is created by the first committed bank change with the
configured currency and shared capacity and keeps both; the percentages of the policy apply at each operation.

### Who can change it

Every change is checked in this order, and nothing is written when one fails: co-op enabled, format-2 store, a co-op
profile not deleted; the type allows the action (`bank.deposit`, `bank.withdraw`, `bank.personal`, `bank.transfer`,
`collections.contribute`; `ACTION_FORBIDDEN_BY_TYPE`); no other node holds the profile's bound world (`WRONG_NODE`);
the profile is not reserved; the actor is a member **with this co-op active** (`NOT_A_MEMBER`, `STALE_STATE`); the
stored currency equals the configured one; `S` carries no pending reservation (`RECOVERY_REQUIRED`). Withdrawing from
the shared bank, and moving shared money to a personal bank, needs the OWNER or a MANAGER (`ROLE_FORBIDDEN`); interest
needs the OWNER. Members change only their own personal bank. A transfer also needs `bank.personal` and the direction's
shared rule (`bank.withdraw` out of, `bank.deposit` into the shared bank); interest credits personal banks only while
`bank.personal` is allowed and only for current memberships (a removed membership's frozen money earns nothing). Before
any change, the balances and contributions of memberships no longer current are folded into the record's archived
totals (`archivedPersonal`, per-collection `archivedContributions`; COOP-DESIGN §8: frozen, redistribution out of
scope) in the same after-image, so the per-member maps are bounded by the current members; the removal intent itself
does not touch `S` (the compaction is lazy, at the next bank change).

### Commits

- **Deposit, withdraw, personal postings, contributions, interest** are one direct guarded commit of `S`
  (`SHARED_MUTATE`): the repository compares the profile claim, the actor's claim, the account and profile revisions,
  `S` and the actor's active profile in the same atomic step as it installs the after-image. A concurrent change makes
  it `STALE_STATE`; the caller re-reads. An UNKNOWN outcome is settled from the receipt or the stored record and is
  never retried or compensated (`UNKNOWN_OUTCOME` when it cannot be established).
- **Transfer** (shared↔personal of the actor) is one recoverable intent (`BANK_TRANSFER`, COOP-DESIGN §5): PREPARE
  reserves `S`, the account and the profile; the one after-image of `S` carries both postings; COMMIT installs it and
  releases the reservations. A crash before the COMMITTED marker leaves reservations that recovery aborts (nothing
  moved); after it, recovery rolls forward. There is never a half-moved amount.
- **Receipts are permanent** for these kinds and for salvage delivery (`CoopOperation.Kind.permanent()`): FILE keeps
  them in the resource's `permanent/` receipt directory, Redis in separate `preceipts:`/`prdigest:` hashes; they are
  never pruned. A resource holding 1,000,000 of them refuses the next financial or delivery operation with
  `CAPACITY_EXCEEDED` before any write; archival is an offline owner policy, not implemented in Build 54.
- **Events** (`ProfileBankTransactionEvent`) fire on the co-op event thread after the durable commit only.
- **Replay.** A call without an operation id mints a fresh one: repeating it is a second posting. With the caller's
  own operation id (the API overloads) the id is bound to the request digest (no revision in it), and the engine looks
  the id up **before any validation** (`ProfileEngine.replayedBank`: the permanent receipt on the profile, for a
  transfer also the stored intent). Same request, committed: answered from the store (`REPLAYED`: the record as it is
  now, nothing posted, no event, no after-image staged), even when the balance moved, the cap was reached or the
  interest interval has not elapsed since. Same request, aborted: `IO_FAILURE` (nothing applied; a new id for a new
  attempt). Same request, undecided intent: `RECOVERY_REQUIRED`. Another request under the id: `INVALID_REQUEST`. A
  decided id whose actor is no longer a member: `INVALID_REQUEST` (the digest names the membership epoch, so the retry
  cannot be matched). Only an unknown id is validated and run. After
  `UNKNOWN_OUTCOME` (the exception names the operation) read the bank back or query `bankReceipt`. Interest is posted
  only when the OWNER calls it, no sooner than `bank.interest-interval-hours` after the previous posting on the store
  clock (the record keeps `lastInterestAtMillis`); the server has no interest schedule.

### Purse (OD-4)

The purse stays per profile through a REQUIRED state adapter and is never part of `S`. The engine has no transactional
purse provider, so a transfer that names the purse is refused with `PURSE_TRANSFER_UNSUPPORTED` before anything is
written. A deposit or withdrawal is a ledger credit or debit; the consumer plugin that holds the purse moves the coins on
its side (purse debit then `deposit`; `withdraw` then purse credit) and owns the ordering and compensation of those two
steps. `/coop bank` offers balances and the shared↔personal transfer only; it mints no coins.

### Salvage exactly once (OD-3)

A removal (leave, executed kick vote) consumes the member's stream (head `CONSUMED`) and records a salvage entry that
pins the exact last snapshot of that stream. A claim (`claimSalvage`, `/coop salvage claim`) by a member:

1. On the I/O lane: the record is not DELIVERED; the type allows `salvage.claim`; the profile is on this node; the
   claimant is a member with the co-op **active** and an admitted online session here (`PLAYER_OFFLINE`), and no
   switch, login restore or other delivery runs for them (`SWITCH_IN_PROGRESS`). The items are extracted from the
   pinned source snapshot (or from the remainder blob of an earlier partial claim), verified by checksum and revision; a
   missing or mismatched blob fails closed (`STALE_STATE`), nothing substituted. The claimant takes a **delivery hold**
   (`ProfileEngine.deliveries`) and is frozen: from here until the apply succeeded, every save path treats them like a
   player mid-switch (`switchInProgress`, `saveCaptured` → `SWITCH_IN_PROGRESS`): the lockstep autosave, a quit, the
   shutdown capture and `save()` write nothing, and no switch is admitted. A claim refused before the commit releases
   the hold.
2. Their state is captured on the entity thread with the capture ticket minted in step 1, bounded by
   `switching.timeout-ms` as a switch's steps are: a capture that does not finish in time fails the claim (`TIMEOUT`),
   nothing is written and the freeze and the hold are released. A rejoin of the claimant (`admitSession`) clears a
   freeze and hold its previous session's claim left; that claim then fails `CLAIM_LOST` (its ticket names the old
   session) and removes only its own entries.
3. On the I/O lane: ticket, session, account and membership are compared again; the items are placed into free
   inventory slots (36), then free ender-chest slots (27), one stack per free slot, in extraction order (inventory,
   armour, off-hand, ender chest). The destination snapshot (and, when not everything fits, a remainder snapshot in the
   consumed source stream) is staged as an immutable blob; one intent then installs the claimant's head at the
   destination, the salvage record's after-image (DELIVERED, or AVAILABLE naming the remainder) and the permanent
   receipt. With no free slot at all the claim is refused (`CAPACITY_EXCEEDED`) and nothing changes; after every such
   refusal, and before the staging, the store is raised to minimum build 54.
4. The committed state replaces the claimant's isolated state on the entity thread. If that fails, the delivery stays
   committed: the delivery hold stays until the session ends and the player's saves are refused (they are
   disconnected), so the quit writes nothing, no pre-delivery capture overwrites the head, the session marker stays
   open and the next authoritative login restores the committed head with the items. On success the hold ends (a quit
   during the run releases the session claim then).

Consequences: the source is consumed before any delivery; items are never dropped into the world and never granted
outside the claimant's committed head; a crash before COMMITTED leaves the record claimable; a crash after it rolls
forward and the head already names the destination (no duplicate); a delivered record refuses a second claim
(`INVALID_REQUEST`) and its receipt names the destination snapshot. XP, bank, purse and adapter data are never salvage.

### Store level

The first salvage delivery about to be written raises the store to minimum build 54 (`StoreFormat.SALVAGE_MINIMUM_BUILD`):
Build 53 cannot decode a DELIVERED record without a payload or an AVAILABLE record whose payload is a remainder, and
could overwrite the claimant's new head from older state. The first profile of a plugin-registered type or with a
type-pool name raises it too, and so does the registration of a built-in override that denies an action
([PROFILE-TYPES.md](PROFILE-TYPES.md)); on a format-1 Redis store these two are refused (`MIGRATION_REQUIRED`) or
logged instead, because a Build 51 node opens such a store without honouring the minimum build. A shared bank record
keeps the Build 51 shape until it first carries a Build 54 trailing section (archived totals after a removed
membership's money was folded, or the interest timestamp); that change raises the store to 54 first, because a Build
51-53 decoder refuses the trailing bytes. Plain deposits, withdrawals and transfers on a record without that section
do not raise it.

### Known gaps (Build 54)

- A removed membership's personal-bank balance is not paid out or moved: the next bank change folds it (and the
  membership's contributions) into the record's archived totals, frozen, shown to nobody through the API. The
  compaction is lazy (the removal intent does not touch `S`), so between a removal and the next bank change the old
  epoch still counts toward the per-member bounds.
- Transfers and salvage deliveries reserve the profile's metadata `M` (as every co-op intent does), so a switch, invite
  or vote of the same profile running at the same moment may see `STALE_STATE` and must be retried.
- A refused PREPARE leaves the staged destination/remainder blobs of a delivery in the store until the pin sweep; the
  salvage apply replaces the claimant's whole isolated state with the committed capture (inventory and ender chest
  included) rather than only the placed stacks.
- A legacy profile whose free-form metadata already held `aurora:type` resolves to that type id; the key is not marked
  as written by Build 54.
- FILE permanent receipts: the first operation on a resource lists its `permanent/` directory (up to 1,000,000 files)
  on the I/O lane and caches the count for the run.
- A bank record's shared capacity cannot be changed after creation (no bank-upgrade API).
- No offline archival of permanent receipts exists yet; the 1,000,000 bound is the only limit.
- Distributed (Redis) deployments: bank changes and claims run on the node that holds the profile (OD-1); there is no
  cross-node routing.

## Remaining after Build 54

Build 55: audit log, switch guard and cooldown, the SSB-AuroraProfiles consumer port (type rules for `trade`,
`auction`, `bazaar`, `island-bank` enforced by the consumer), API parity fields, per-account I/O ordering, `/perf`
metrics. Owner transfer is in no scheduled build.

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
- **Narrowed in Build 53, not removed:** a crash after the commit and before the server saves vanilla
  playerdata (autosave or quit) can leave playerdata with the previous profile's state while the account names
  the target (PM-028 / MP-COMMIT-VANILLA). While authoritative login applies, the next join restores the committed
  profile state instead of loading that playerdata (the session marker's provenance does not name the new profile,
  so it is not adopted); otherwise the window is as in Build 52. State the active profile held at the crash but never
  captured is lost either way; while authoritative login applies the loss window is the server's own player save
  interval (every vanilla save publishes a snapshot), the same as vanilla's.
- A crash between writing a new profile record and adding it to the account leaves an unreferenced
  profile record. It is never listed and is safe to delete; no player state is lost.
- Cross-store atomicity (profile records + AWF world commits + Redis claims) is **not** claimed. A
  world may have been saved while the profile switch rolled back; that leaves the world in its saved
  state, not the profile.
- Cancelling the future returned by `switchProfile` does not stop the engine's work and does not
  release ownership; recovery is the only way to resolve an interrupted run.
