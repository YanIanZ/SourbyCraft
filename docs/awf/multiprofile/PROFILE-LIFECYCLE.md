# Profile lifecycle

## States

A profile is `ACTIVE`, `LOCKED` or `DELETED`. `LOCKED` is an administrative hold: it is listed but
cannot be switched to. Deleting the active profile is refused; delete is a tombstone (the profile
record remains) whose snapshots and bindings are removed. AWF worlds are never deleted by a profile
delete.

An account stores `profileIds`, `activeProfile`, `slotLimit` and a revision. Creation is refused when
the account is full (`SLOT_LIMIT`), and `max-slots` is a hard ceiling even for an account whose stored
limit is higher.

## Snapshot revisions

A snapshot records `revision = profile.revision + 1`, and the profile is bumped in the same operation.
Applying a snapshot requires `snapshot.revision >= profile.revision`; a lower revision is
`STALE_STATE` and the switch is refused before anything is applied. At most three snapshots per
profile are kept; the newest wins.

## Switch state machine

Phases are persisted in the journal **before** the work of that phase begins:

| Phase | Work | Crash behaviour |
|---|---|---|
| `VALIDATING` | account, target, pending journal check | nothing applied; journal is terminal-free and recoverable |
| `FREEZING` | capture old state in one entity task | capture is discarded; rollback is a no-op |
| `SAVING` | write old snapshot, bump profile revision | snapshot may be one revision ahead; the `>=` freshness rule accepts it |
| `PREPARING_TARGET` | claim target ownership, prepare AWF worlds | target never applied; rollback |
| `APPLYING_TARGET` | apply target state on the entity thread | player may hold the target state; rollback re-applies the old snapshot |
| `TELEPORTING` | Folia async teleport to the target spawn/last location | same as above |
| `COMMITTING` | validate fence, write `activeProfile` | **commit point**; recovery finishes forward |
| `ACTIVE` | journal removed | done |

`RECOVERING` is written while compensation runs; `FAILED` records the reason when the rollback can
never run (no durable snapshot). A journal whose player is offline is left at `RECOVERING` so the
next join retries it. `/awf multiprofile recover <transition>` on a `FAILED` journal removes it and
reports what it recorded.

## Recovery rules

`/awf multiprofile recover <transition>` (permission
`sourbycraft.command.awf.multiprofile.admin`), also attempted at the player's next join:

1. If the account already names the target, the switch committed: write `ACTIVE`, delete the journal.
2. Otherwise apply the previous profile's latest snapshot and point the account back at it; delete the
   journal and report `RECOVERED`.
3. If there is no previous profile, the journal is cleared and `RECOVERED` is reported.
4. If there is no durable snapshot of the previous profile, the journal is marked `FAILED` and
   nothing is guessed. If the player is offline, the journal stays non-terminal and the next join
   retries.
5. Recovery takes a fresh claim on the profile it is about to make authoritative. A journal whose
   recorded fence is older than that claim's fence is refused: a takeover happened after the journal
   was written, so recovery must not guess.

## Guarantees and non-guarantees

- A crash cannot leave two authoritative profiles: the account revision is the commit point.
- Inventory duplication requires the target state to be applied while the old snapshot was not
  durable. `switching.require-durable-save: true` (default) refuses that case; setting it to `false`
  explicitly allows a switch without a rollback point.
- A crash between writing a new profile record and adding it to the account leaves an unreferenced
  profile file. It is never listed and is safe to delete; no player state is lost.
- Cross-database atomicity (file records + AWF world commits + Redis claims) is **not** claimed. A
  world may have been saved while the profile switch rolled back; that leaves the world in its saved
  state, not the profile.
- Cancelling the future returned by `switchProfile` does not stop the engine's work and does not
  release ownership; recovery is the only way to resolve an interrupted run.
