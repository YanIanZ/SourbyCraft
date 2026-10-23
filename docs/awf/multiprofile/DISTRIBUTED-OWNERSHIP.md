# Distributed ownership and fencing

**Status: implemented behind configuration, experimental, not production-qualified.**

## Claim, epoch and fence

- A **claim** is a lease on one object (`session:<profile>`, `ownership:<kind>:<id>`).
- A **fence** is a counter that only increases and is stored in a key that does not expire
  (`session-epoch:<profile>`, `fence:<kind>:<id>`).
- A takeover is only possible after the lease expired, or with an explicit operator reset
  (`SessionFabric.reclaim` requires the exact current epoch). Every takeover increments the counter.

TTL expiry is **not** proof that the previous node stopped writing. It is permission to *replace* the
node, and the replacement's higher fence is what makes the old writer fail.

## Write-path enforcement

`ProfileEngine` validates the fence immediately before the commit write when
`coordination.fencing-enabled: true` (default):

```java
if (fencingEnabled && claim != null && !ownership.validate(claim).join()) {
    throw OWNERSHIP_CONFLICT;   // nothing is committed
}
repository.saveAccount(account.withActive(target));
```

`CoordinationFabric.validate` requires the same owner, node and fence **and** an unexpired lease. A
node whose Redis connection is lost cannot commit, because validation cannot be answered. That is the
explicit durability boundary: distributed mode is not writable while the authoritative ownership
service is unavailable.

## What is fenced

- The active-profile commit in `ProfileEngine.SwitchRun`.
- The snapshot of the profile being left: the switch claims it before capturing and validates the
  claim immediately before the snapshot write.
- `saveProfile`: validates the claim on the profile before writing its snapshot; with fencing on it
  refuses to write when the profile has no claim at all in distributed mode.
- Recovery: takes a fresh claim on the profile it is about to make authoritative and refuses a
  journal whose recorded fence is older than that claim.

The switch claims the target profile before preparing its world; a conflicting live claim refuses the
switch with `OWNERSHIP_CONFLICT` and nothing is applied.

## Shared state

Every node must mount the same `awf/profiles` directory (a shared volume). The claim serialises
writers per profile; the file repository itself is not a multi-writer database. Two nodes with
separate directories would each have local copies: the distributed mode would coordinate ownership of
state that does not follow the player, which is not a supported deployment and is why the directory is
called out here.

## What is not fenced (honest list)

- AWF world commits keep their existing per-world Redis lease. That lease is per world, not a global
  atomic lease over profile records; the two systems are not merged.
- `saveProfile` refuses to run when another node holds the profile, but the check is a read followed
  by a write, not a compare-and-set on the snapshot file. The engine serialises its own writes per
  process; two processes writing one profile is prevented by the claim in the normal switch path.
- PostgreSQL is not implemented. There is no transactional metadata backend; the file repository plus
  the fence is the chosen durability boundary because it needs no new deployment.

## Why distributed mode is experimental

Qualification requires a multi-node run that shows: a stale owner's commit is refused; a takeover
after a killed node recovers; events are processed idempotently under redelivery; and a Redis outage
stops writes instead of producing split-brain. Only the Redis-level parts (claim/takeover/stale
validation/stream retry) are covered by tests on this branch. No multi-node SourbyCraft run has been
performed, so the mode is not qualified.
