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

## Session handoff on a proxy server switch (Build 55.1)

Behind Velocity (or any proxy) a `/server` switch logs the player in to the new server **before** the old server
sees their quit. The new server's join queues the strict session claim `Q` at once; the old server's quit handler
captures the player, commits the quit save (`saveCapturedAndClose`), releases the profile node claim and only then
releases `Q`, all FIFO on that player's I/O lane. On Builds 51–55 the new server attempted `Q` exactly once, so
almost every switch was refused with "Your profile is active on <node>. Try again in a moment." and the player was
kicked: no duplication and no loss (the login gate kept the new server's vanilla playerdata out of every save), but
a bad experience.

`coordination.handoff-wait-ms` (RESTART_REQUIRED, default `0` = the Build 55 single attempt; `5000` recommended
behind a proxy; ceiling `15000`) makes the join wait for the handoff:

- A refusal whose cause is **another live session holding `Q`** (a refusal that names a holder) is retried with a
  bounded backoff (100 ms, doubled on every refusal up to 1 s, never past the window). A node claim on the active
  profile held by another node is retried under the **same window** (the old server releases it before `Q`, so this
  mostly covers a co-op profile its other members still play there, OD-1: that wait ends in the refusal).
- Every retry is the **same strict claim** (`admitSession` → `PlayerSessionOwnership.claim`): it is granted only
  once the holder released the claim, or its lease lapsed and the epoch rules above replace it. The wait **never
  takes a live claim**, never touches the holder's fence or epoch, and the holder's commits keep failing under the
  replacement's claim exactly as before. A crashed old server therefore still refuses the join until its TTL
  (`sessions.ttl-seconds`, 45 s by default) lapses, which is why the ceiling is well below it.
- The retries are queued by one daemon timer thread (`SourbyCraft-AWF-profile-handoff`) and run on the profile I/O
  lane; no region or global tick thread waits, and other players' steps keep moving. Meanwhile the player stands on
  the new server behind the Build 53 login gate (item events cancelled, nothing captured or saved) with one
  action bar, "Loading your profile...".
- The wait ends with the Build 55 refusal when the window elapses, when the player leaves (the join went stale) or
  when the engine closes (shutdown releases the join's gate). A retried attempt that fails (storage, Redis) ends the
  wait with that error, exactly as a first attempt would.
- The old server releases `Q` **after** its quit save settled on its lane: committed, or failed (the save is logged,
  the session marker stays open and `Q` is still released). The admitted join then restores the **last committed
  head** of the active profile, as every Build 55 authoritative login does: the quit state when that save committed,
  otherwise the previous snapshot (progress since it is lost, as it is today after a failed quit save).
- **Frozen while waiting.** The waiting player stands on the join's vanilla playerdata, which the login gate keeps out
  of every save but which could still change the world for up to the window. Besides the gate's item, experience and
  interaction cancellations (`SwitchFreeze`), `HandoffFreeze` cancels, for a waiting player: all damage to them and
  dealt by them (including their projectiles), so no death and no death drops; every command (with a message);
  opening an inventory; movement to another block (the move is cancelled, look changes pass); portal use. Each
  handler runs on the thread that owns the player and reads one concurrent set. Lifted when the join is admitted or
  refused, and at quit.

`/perf multiprofile` counts the claim waits (started, admitted, timed out, left) and the p50/p95 of the admitted
waits. Not covered: a proxy that keeps the old connection open after the new one joined (Velocity does not), a
Redis outage during the wait (the attempt fails, the join is refused), and the co-op case above. Owner tests:
switch in both directions under load, kill the old server mid-switch (expect the refusal until the TTL), restart
Redis during a switch.

## Co-op across nodes (Build 56, `coop.cross-node`)

Build 56 code written and compiled by an agent; **not run, not tested by the owner** (owner cases TO-B56-01..06 in the
[Build 56 notes](../../releases/26.2-build-56-aurora-nexus.md)). Off by default; with the default every node behaves
as Build 55.1 (OD-1). The target shape is the WuretaMC network: a **spawn node** (lobby: no island worlds, profile
switching disabled) and an **island node** (SSB2 islands in AWF worlds, every switch and world operation), with the
members of one co-op on both at once, or together on the island.

### What "the node of a co-op" is

Since Build 53 the **node claim** (`session:<profile>` in `SessionFabric`, one per profile, shared by every member's
session on that node) has two meanings at once: it fences format-1 writes, and it marks the node that *plays* the
co-op (OD-1): a join, a switch and every co-op operation elsewhere are refused `WRONG_NODE`, whether or not the profile
has a bound world. On a format-2 store the first meaning is gone, because no gameplay write carries the node claim:

| Record | Written by | Fence at commit (Build 53–55.1, unchanged by Build 56) |
|---|---|---|
| member head + snapshot `R(profile, member, epoch)` | that member's save, quit, login initialisation, switch | that member's session claim `Q`, expected `A`, `M` (compared), `R` (`CoopWriteGuard.memberSave`) |
| shared record `S` (bank, personal subaccounts, collections) | bank/collection postings, interest | transaction claim `P`, actor `Q`, expected `A`, `M`, `S`, actor active on the profile (`mutateShared`) |
| metadata `M` (members, roles, label, hold, binding reference) | convert, invite, accept, leave, kick, label, lock, archive | `P`, actor `Q` (and the affected member's `Q` for a removal), expected `A`, `M`, invite/vote revision (intents) |
| account `A` (slots, aliases, active pointer) | switch, accept, removal repoint | `Q`, expected `A` |

`P` (`coop:profile-claim:<id>`) and `Q` (`coop:player-claim:<id>`) are strict: a different token is refused while the
holder's lease is live, on any node. Two nodes posting to one bank therefore serialise on `P` (one gets
`OWNERSHIP_CONFLICT`, writes nothing, and the caller retries); a posting planned against a shared record another node
advanced meanwhile is refused by the `S` CAS (`STALE_STATE`, nothing written, the caller retries). Per-member state
(snapshots, capture tickets, purse adapter data) is per `(member, epoch)` stream and never shared.

So with cross-node on, the node claim keeps only its remaining job: **it marks the node that hosts the profile's
bound worlds** (`ensureReady`, `prepareActive`, idle unload). The decision is per node, not per profile:

| `coop.cross-node` | `coop.world-host` | Role | Node claim | `WRONG_NODE` | Bound worlds |
|---|---|---|---|---|---|
| `false` (default) | ignored (`false` refused) | Build 55.1, OD-1 | taken on join/switch, one node per profile | join, switch, every co-op operation while another node holds the claim | prepared and hosted |
| `true` | `true` (default) | **world host** (island) | taken on join/switch, exactly as Build 55.1 | as Build 55.1: a second world host of the same co-op is refused | prepared and hosted |
| `true` | `false` | **satellite** (spawn) | **never taken** (format 2); the session is recorded without one (`satelliteSession`), so the quit, lockstep and shutdown captures still run | **never**: join, bank, collections, membership and label run under `P`/`Q`/CAS | **never** prepared, loaded or created: the world port is the no-op one, `prepareActive` returns at once |

Rules the runtime enforces at start (`ProfileRuntime.validateCrossNode`, before the fabric checks):
`cross-node: true` needs `mode: distributed` and `repository: redis` (the FILE repository compares revisions in one
process and is refused); `world-host: false` needs `switching.enabled: false` (a switch takes the node claim and
prepares the target's worlds) and `cross-node: true`. A refused combination means MultiProfile refuses to start (ERROR `AWF MultiProfile could not start: ...` and a WARN for the failed boot stage); the server keeps running **without MultiProfile**, on vanilla playerdata, as for every refused MultiProfile setting since Build 51.
A satellite on a format-1 store claims like any node (format 1 has no `Q`; `writeAuthority` needs the claim), so
the feature is effectively format 2 only.

**Locations on a satellite (isolation stays identical on both nodes).** The bound world, the member's bed and their
return point are on the world host. A satellite therefore applies every restore with the location category off (the
port is asked for no respawn point, so it neither sets nor clears one for a world that is not loaded there), and
every snapshot it publishes (lockstep save, quit, shutdown, adoption, salvage destination) carries the head's stored
respawn point and return point forward **unchanged**, never the satellite's (`withCarriedLocations`); a stream without
a head stores none. The same `isolation.*` keys must be set on both nodes: a category that is off on one node and on
on the other is not captured there and would be replaced by that node's vanilla value at the next save on the other.

### What happens where (WuretaMC shape)

- **A member joins the spawn node** with a co-op active: `Q` is claimed (with the Build 55.1 handoff wait), no node
  claim, the committed member head is restored, no world is prepared. The island's node claim is not consulted and not
  disturbed; the island's members keep playing. The member's saves, quit and shutdown captures publish their own stream
  under `Q`, with the island bed and return point carried forward (above). Same `isolation.*` on both nodes.
- **A member joins the island** while another member is on the spawn node: the island claims the node claim (nobody
  else holds it) and plays as Build 55.1. Two members on the island share the node's one claim as before.
- **Two island nodes** (two world hosts) and one co-op: the second is refused `WRONG_NODE` on join, switch and every
  co-op operation, exactly as Build 55.1. A co-op's bound world is hosted by one node; there is no routing.
- **Bank, collections, label from either node:** `P` + `Q` + CAS, as in the table. The spawn node skips the OD-1 node
  check (`requireProfileNode`) because it hosts no world; the island keeps it (against other world hosts).
- **Invite/accept:** an accept on the spawn node admits the member under `P` and the invitee's `Q` while the island
  plays the co-op; the member's first entry on either node initialises their stream.
- **Leave/kick of a member who plays the co-op on the other node:** refused `MEMBER_ONLINE` from either side. On the
  spawn node the member cannot be switched out (switching is off); from the island the member's `Q` is held on the
  spawn node. Once they quit (their quit published their head), a leave or kick repoints them (Build 53 `REPOINT`).
- **Operator lock** of a co-op while any member has it as their active profile (on this node, the other node or
  offline): refused `PROFILE_LOCKED` naming them, as an archive is (new in Build 56, on every node: a lock refuses
  every save of such a member, and a satellite holds no node claim an older check could see). Delete already refuses
  a co-op with other members.
- **Switching on the spawn node:** `switching.enabled: false` is required (else MultiProfile refuses to start). Every
  profile switch and SSB2 island action stays on the island. A pending switch journal of a member who joins the
  satellite is recovered there under the player's `Q` and the revision CAS without a node claim (`claimForRecovery`);
  the stale-fence comparison against the node claim's epoch is not made on a satellite (a Redis epoch that went back
  is an unsupported failover on every node).

### What stays refused or unchanged

- Two world hosts for one co-op (`WRONG_NODE`); loading a bound world on two nodes; any routing or live-state
  transfer (the join restores the last committed head, Build 53).
- The FILE repository, standalone mode, a format-1 store.
- A member's node claim on a world host still waits out the Build 55.1 handoff window only for claims another
  *world host* holds; a satellite never holds one, so a switch spawn → island never waits on the co-op's node claim.
- Switch cooldown stays per node; `/perf multiprofile` stays per node.
- No new record type, no store level change, no Lua script change: the fences above are the Build 53–55 ones.

## Known gaps

- A player's live state does not follow them between servers. With `login.authoritative: true` (Build 53) a
  join restores the committed state of the active profile from the store, so a player who moves between servers
  gets the last snapshot that was saved, not the other server's live state; progress after that snapshot, or in a
  capture that missed the shutdown deadline, is lost. With `false` a join keeps that server's vanilla playerdata
  (Build 52 behaviour).
- Shutdown capture (Build 53) is per node and bounded by `shutdown.capture-deadline-ms`; it is not a distributed
  handoff. With `coop.cross-node` off (default) a member plays a co-op only on the node holding its bound world
  (OD-1, `WRONG_NODE` elsewhere); Build 56 lifts that for satellite nodes (above), untested.
- No proxy or portal handoff transport: `RoutingFabric` has no transport and advertises an empty address. A
  proxy server switch is handled only on the joining side by the session handoff wait above (Build 55.1,
  `coordination.handoff-wait-ms`, off by default); nothing moves live state, and nothing routes players.
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
