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
