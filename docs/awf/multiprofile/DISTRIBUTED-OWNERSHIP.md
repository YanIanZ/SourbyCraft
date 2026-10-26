# Distributed ownership and fencing

Build 50, PR #62 (2026-10-25): code written and statically reviewed; not built; not tested by the
owner. The prerelease tag `v26.2-r50` (5f5a167) predates PR #61 and PR #62, so nothing on this page is
in a published build; the publication route is owner decision PM-011. Evidence and per-item status:
[readiness](../../releases/BUILD-50-READINESS.md), [PM matrix](../../releases/BUILD-50-PM-MATRIX.md)
(PM-037) and [Tester Owner](../../tester%20owner.md).

**Status: EXPERIMENTAL, not qualified, not production-ready.** The fenced shared repository
(`RedisProfileRepository`) exists in code; the owner two-node test MP-010 has not run, and the gaps
listed under [Known gaps](#known-gaps) are open.

## When distributed mode starts

`ProfileRuntime.validate` decides at startup (every key RESTART_REQUIRED):

| Settings | Result |
|---|---|
| `mode: standalone`, `repository: file` (default) | one server; local or Redis ownership |
| `mode: standalone`, `repository: file`, `coordination.provider: redis` | starts with a WARN: a single-node setup, not distributed |
| `mode: distributed`, `repository: file` | **refused**: the file repository cannot fence its writes with Redis ownership |
| `repository: redis` (`standalone` or `distributed`) | starts only with the fabric running, `coordination.provider: redis` and `coordination.fencing-enabled: true`; EXPERIMENTAL |

`standalone` with `repository: redis` passes the same checks as `distributed` and nothing limits it to
one server. Every `repository: redis` start logs a WARN that names the gaps below. The repository also
refuses at startup a Redis Cluster, a replica, Redis before 7.0, `appendonly no` while
`redis.require-aof: true`, and a repository written in another format (`RedisProfileRepository.open`).
`aurora.awf.redis.fabric.services.sessions` must stay on: the claims live there.

The published `v26.2-r50` prerelease jar has neither a refusal of `mode: distributed` (PR #61 refused
distributed mode outright; PR #62 narrowed that to `distributed` + `file`) nor the directory lock
(PR #62): it starts distributed mode on a file directory. Do not run `mode: distributed` on that jar.

## Claim, epoch and fence

- A **claim** is a lease on `session:<profile>` in `SessionFabric` (hash `session`, `node`,
  `incarnation`, `owner`, `epoch`, `expiresAt`, `state`; TTL `sessions.ttl-seconds`). The `session`
  field is `node/incarnation/owner`: node id, process incarnation (`NodeIdentity`) and a random owner
  token per claim.
- The **epoch** is the fence: `session-epoch:<profile>`, a counter that never expires and is never
  deleted.
- Claim outcomes (one Lua script, Redis `TIME`): no live claim, or an expired one, gives a new claim
  with the next epoch. The same `session` refreshes, same epoch. The same node and incarnation with a
  new owner token (a rejoin after a switch) replaces the token and keeps the epoch; the old token then
  fails validation and the repository fence. This node's previous incarnation, when `NodeIdentity`
  proves it stopped (same `awf/node.lock` file and the same boot), is taken over with the next epoch.
  Any other live claim is refused as `duplicate`, including one from the same node id with another
  incarnation (a copied directory, a second server, a restart without that proof). A node id alone
  never authorises a claim.
- `RECLAIM` takes a session over only when its epoch is exactly the expected one, and increments it.
  `/awf multiprofile reclaim <profile> <epoch>` uses it only when another process holds the profile,
  and releases the claim at once.

TTL expiry is **not** proof that the previous node stopped writing. It is permission to replace the
node; the replacement's new claim is what makes the old writer's commits fail.

## Write path: fenced commits

Every `RedisProfileRepository` mutation is one Lua script (`saveAccount`, `saveProfile`, `saveBinding`,
`publishSnapshot`, `saveLegacySnapshot`, `saveTransition`, `deleteTransition`, `deleteProfileData`).
Inside Redis, in one atomic step, the script:

1. when the write carries a claim, reads the claim's session hash and requires the same `session`
   field, owner token and epoch, and `expiresAt` later than Redis `TIME` in ms; otherwise it replies
   `FENCED` (`superseded` or `expired`), which becomes `OWNERSHIP_CONFLICT`;
2. when the record has a revision and the write expects one, requires the stored revision to equal
   it; otherwise `STALE`, which becomes `STALE_STATE`;
3. only then applies every change of the mutation.

A refused write changes nothing and is never retried by the repository. `MISSING` (a staged snapshot
expired) and `REFUSED` (a journal id that belongs to another player, or a new journal for a player who
already has 64) become `IO_FAILURE`. The scripts start with the `#!lua` flag line, so Redis refuses one up
front when it is out of memory instead of applying half a commit (Redis 7.0+).

`ProfileEngine` also validates a claim before the snapshot and commit writes (`coordination.fencing-enabled`).
That pre-check is a read followed by a write and is not a fence by itself; the script is.

| Write | Claim it carries | Revision checked |
|---|---|---|
| switch: snapshot of the profile being left | the previous-profile claim taken at `FREEZING` | profile |
| switch: pre-profile archive (first switch) | the target claim | none |
| switch: account commit | the target claim | account |
| switch: journal phases from `FREEZING` to `COMMITTING` | the target claim | none (journal must belong to the player) |
| save and quit snapshot | this node's session claim on the active profile | profile |
| delete, lock/unlock, bind/unbind | a short administrative claim (Redis provider) | profile, account (binding: none) |
| recovery | the player's valid session claim on that profile, else a fresh claim | account |
| create a profile, set a slot limit | none | account (profile: must not exist) |

### A lost commit reply

A Redis write can run and lose its reply. When the switch's account commit fails with an I/O error on
the Redis repository (`ProfileRepository.remote()`), the switch does not roll back at once. It claims
the target again with a new owner token, which supersedes the token the commit was sent under, so a
commit still on its way can no longer apply; then it reads the account. Account names the target at a
higher revision: the switch completes (WARN `lost the reply of its commit, but the account shows it was
applied`). Account unchanged: normal compensation. Claim or read failed too: nothing is rolled back, the
journal stays `RECOVERING` with `whether the commit was applied is unknown`, saves and switches of that
player are refused, and recovery decides from the account later.

### Snapshots

A snapshot (up to 8 MiB) is first written alone under `mp:stage:<snapshot>` with `SET ... PX 600000 NX`
(10 minutes). The publish script moves it into `snaps:<profile>`, indexes it, advances the profile
record and names it `latest`, and prunes beyond 3 snapshots (keeping every snapshot a pending journal
of the account names) in the same step. A failed or refused publish deletes the staged key, which also
stops a commit still on its way (it finds nothing to move); a crash leaves an orphan that expires.

## Durability is Redis's

- `redis.require-aof: true` (default) refuses `appendonly no`. With `false` the server starts with a
  WARN and profiles are as durable as Redis's RDB snapshots.
- With `appendfsync everysec` a Redis crash can lose about 1 s of acknowledged writes.
- `redis.wait-for-aof: true` (default) sends `WAITAOF 1 0 <ms>` in the same pipeline, on the same
  connection, as the writes a later step relies on: snapshots, pre-profile archives, accounts, profiles,
  bindings and the `APPLYING_TARGET` journal. The wait is half the remaining client timeout (2500 ms
  with the default `aurora.awf.redis.timeout-ms: 5000`). A write Redis does not confirm in time is still
  applied: it is counted in `/awf multiprofile status` and logged at most once a minute, not failed.
  When Redis refuses `WAITAOF` (before 7.2, or AOF off) it is turned off with one WARN. Other journal
  writes and deletions are acknowledged from memory: losing them can only make a journal look earlier
  or reappear, which recovery resolves.
- Session claims are not confirmed with `WAITAOF`. A Redis restart or a failover can lose claims and
  move epochs back. The scripts still compare the whole `session` field and owner token, so a writer
  whose claim is gone or was replaced stays refused; a regressed epoch can make recovery refuse a
  journal recorded under a higher fence (`OWNERSHIP_CONFLICT`) until the operator resolves it.
- An asynchronous replica promoted on failover can lose acknowledged profile writes. **One primary is
  the supported topology**: a replica endpoint and a cluster are refused, and connected replicas are
  reported with a WARN.

## The file directory with `repository: redis`

Redis is the shared state. The file directory (`multiprofile.directory`) is only read: while the Redis
repository is empty (no commit, no finished import) and `accounts/*.awfp` exists there, every profile
write is refused until `/awf multiprofile migrate-repository confirm` copies it (see
[OPERATIONS.md](OPERATIONS.md#migrating-to-the-redis-repository)). The refusal lifts on its own once
another server has committed to, or migrated into, the Redis repository. A file directory must never
be shared by two running servers; its `repository.lock` refuses a second one where the filesystem
implements OS file locks.

## What is not fenced (honest list)

- Journal phases written before a switch holds any claim (`VALIDATING`, `PREPARING_TARGET`), and the
  outcome records of a failed switch (`FAILED`, or `RECOVERING` when its rollback is pending), which
  are written after the run released its target claim. They are checked only for belonging to the
  player.
- The removal of a terminal journal by `recover`.
- Creating a profile and setting a slot limit: revision-checked only. Bindings carry no revision.
- The file repository compares revisions under its own lock in one process; it is never a distributed
  fence, which is why `distributed` + `file` is refused.
- AWF world commits keep their own per-world Redis lease; the two systems are not merged.
- PostgreSQL is not implemented; there is no cross-store transaction.

## Known gaps

- A player's live state does not follow them between servers. A join keeps that server's vanilla
  playerdata; only a switch applies a profile snapshot, and the next save or quit snapshot stores the
  local state. A player who moves between servers can therefore lose progress.
- No quit snapshot at shutdown: feature patch 0081 closes MultiProfile before the server saves and
  removes players (needs a materialized-patch change).
- No proxy or portal handoff. `RoutingFabric` has no transport and advertises an empty address.
- No Redis Cluster and no replica topology.
- Profile steps run one at a time per server (`ProfileIoQueue`), so one slow Redis call delays every
  profile step on that server.
- A crash after the commit and before the server saves vanilla playerdata (PM-028, open owner
  decision) is not covered.
- No multi-node run, no MultiProfile probe plugin, and no new tests were written in PR #62.

## Qualification

MP-010 on two servers must show: a stale owner's write refused after a takeover (Redis keeps the
record unchanged); takeover after a killed node; a Redis outage refusing writes instead of a split
brain; a lost commit reply settled from the account; a crash mid-switch recovered on either node; a
verified one-way migration. The procedures are in
[03-EXPERIMENTAL-FEATURES-TESTING.md](../../testing/owner/03-EXPERIMENTAL-FEATURES-TESTING.md) and
[Tester Owner](../../tester%20owner.md). Until they pass on the owner's hardware, distributed mode stays
EXPERIMENTAL.
