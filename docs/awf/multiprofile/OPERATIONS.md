# Operations

Build 50, PR #62 (2026-10-25): code written and statically reviewed; not built; not tested by the
owner. The prerelease tag `v26.2-r50` (5f5a167) predates PR #61 and PR #62: the bind/unbind, lock,
unlock, slots, owner, reclaim and migrate-repository commands and the Redis repository are not in any
published build (publication route: owner decision PM-011). `repository: redis` and
`mode: distributed` are EXPERIMENTAL and not qualified; `mode: distributed` with the file repository is
refused. Evidence: [readiness](../../releases/BUILD-50-READINESS.md),
[PM matrix](../../releases/BUILD-50-PM-MATRIX.md) and [Tester Owner](../../tester%20owner.md).

## Enabling

1. `aurora.awf.multiprofile.enabled: true` in `sourbycraft_config/aurora.yml`.
2. Keep `mode: standalone` and `repository: file` (the defaults) unless you are testing the
   experimental shared repository.
3. Restart. The startup log line names the mode, node, repository and ownership provider.

For the shared Redis repository (EXPERIMENTAL; see [DISTRIBUTED-OWNERSHIP.md](DISTRIBUTED-OWNERSHIP.md)):
`aurora.awf.redis.fabric.enabled: true` with `services.sessions: true`, `coordination.provider: redis`,
`coordination.fencing-enabled: true`, `repository: redis`, then `mode: distributed` for several servers.
Redis must be a reachable 7.0+ primary (7.2+ for `WAITAOF`), not a cluster and not a replica endpoint,
with `appendonly yes` unless `redis.require-aof: false`, and an ACL user with the commands listed in
[REDIS-FABRIC.md](REDIS-FABRIC.md#redis-acl-for-multiprofile). The server refuses to start MultiProfile
(and logs why) when the fabric, `services.sessions`, the provider or fencing setting, the Redis version,
role, cluster mode or the AOF requirement is not met; it does not fall back to local ownership. An ACL
that lacks a command other than `INFO` is not detected at startup: the first operation that needs the
command fails. Every such start logs a WARN naming the open gaps: a player's live state does not follow
them between servers, no proxy handoff; the Build 53 quit capture at shutdown is per node and bounded by `shutdown.capture-deadline-ms`.

Profile worlds must exist as AWF worlds, or the profile's binding must name a template to create them
from: bind with `AuroraProfiles.bindProfileWorlds` ([API.md](API.md#build-50-world-binding-api-owner-testing-pending))
or `/awf multiprofile bind`.

## Commands

Every `/awf` subcommand needs `sourbycraft.command.awf`. `sourbycraft.command.awf.multiprofile.admin`
is checked on its own: `sourbycraft.command.awf.admin` does not imply it, and as an undeclared node it
is granted to operators by default.

| Command | Permission | Purpose |
|---|---|---|
| `/awf multiprofile status` | `sourbycraft.command.awf.multiprofile` | mode, node, ownership, repository (see below), switching, isolation, idle unload, last idle sweep, pending transitions with their failure text, failed journals, unreadable journals |
| `/awf multiprofile list <player\|uuid>` | same | profiles, revisions, status, active marker, worlds |
| `/awf multiprofile inspect <profile-uuid>` | same | account, type, revision and status, worlds, metadata |
| `/awf multiprofile owner <profile-uuid>` | same | the ownership provider's holder: node, epoch, expiry (`session fabric` with Redis, `this process` locally) |
| `/awf multiprofile create <player> [type]` | `...multiprofile.admin` | create a profile for testing or administration |
| `/awf multiprofile switch <player> <profile-uuid>` | `...multiprofile.admin` | switch a player; reports the reason, duration and journal id |
| `/awf multiprofile bind <profile-uuid> <world> [template]` | `...multiprofile.admin` | replace the binding with one PRIMARY world (names lower-cased) |
| `/awf multiprofile unbind <profile-uuid>` | `...multiprofile.admin` | clear the binding; no world is deleted |
| `/awf multiprofile lock\|unlock <profile-uuid>` | `...multiprofile.admin` | administrative hold of an inactive profile (switching to it is refused) |
| `/awf multiprofile slots <player> <n>` | `...multiprofile.admin` | the account's slot limit, 1..`max-slots`; profiles above a lowered limit are kept |
| `/awf multiprofile recover <transition-uuid>` | `...multiprofile.admin` | resolve an incomplete switch; on a terminal (`FAILED`) journal, remove it |
| `/awf multiprofile session <player> reset-marker` | `...multiprofile.admin` | Build 53: write a closed session marker without provenance, so the player's next authoritative login restores the committed head and adopts no vanilla playerdata (the way out of a join refused by an adoption that cannot be stored; progress held only in vanilla playerdata since the last committed capture is dropped, nothing is duplicated) |
| `/awf multiprofile session <player> clear-refusal` | `...multiprofile.admin` | Build 53: lift the freeze of a player whose save was refused in the current session (a new session clears it on its own) |
| `/awf multiprofile reclaim <profile-uuid> <epoch>` | `...multiprofile.admin` | take over and release the claim of a holder that is gone, only at exactly that epoch (Redis provider) |
| `/awf multiprofile migrate-repository confirm` | `...multiprofile.admin` | one-way copy of the file repository into an empty Redis repository |
| `/awf redis status` | `sourbycraft.command.awf.redis` | reachability, version, pools, counters |
| `/awf redis pools` | same | per-workload size and use |
| `/awf redis events` | same | stream length, retention, publish/consume/retry/dead-letter |
| `/awf redis sessions <profile-uuid>` | same | session holder (the MultiProfile claim) |
| `/awf redis ownership <uuid>` | same | a `CoordinationFabric` claim (API-only; nothing in MultiProfile writes one) |

`/awf redis` diagnostics run on the administrative I/O executor, not the storage lane, and may answer
`Busy: SourbyCraft I/O capacity reached; try again shortly` when it is full.

The `repository` status row is `file <directory>` for the file repository. For Redis it shows the key
namespace, the Redis version, connected replicas, `AOF on (appendfsync ...)` or `AOF OFF`, `WAITAOF on|off`,
the number of writes Redis applied but did not confirm on disk in time, and `WRITES REFUSED: <reason>`
while unmigrated profiles block writes. It reports what was probed at startup; nothing is probed again.

## Migrating to the Redis repository

1. Stop the server and back up `awf/profiles/`.
2. Set `repository: redis` (with the settings under [Enabling](#enabling)) and start with a whitelist.
   With an empty Redis repository and profiles in the directory, status shows `WRITES REFUSED`.
3. With nobody online, run `/awf multiprofile migrate-repository confirm`. It copies accounts,
   pre-profile archives, profiles, bindings, snapshots and readable journals, reads every record back
   through the Redis read path (checksums verified) and compares it, then marks the import finished,
   which lifts the refusal. The summary lists the counts and any unreadable journals it did not copy.
4. Open the server.

The migration is refused when the Redis repository already holds data (any commit or a finished
import), when players are online, or when another process holds the directory. A failed run leaves no
finished mark; writes stay refused and it can run again. The directory's records are not changed, so
it stays a copy to go back to; opening it does what any start of the file repository does
(`repository.lock` is rewritten with the holder and pid, missing subdirectories are created, and an
unreadable journal is set aside as `transitions/*.corrupt` and not copied). There is no export from
Redis back to files.

## Format 2, migration and rollback (Build 51)

Build 51 code written; not built by an agent; not tested by the owner. MultiProfile stays disabled by
default. Co-op membership is Build 52 and is described in [Enabling co-op](#enabling-co-op-build-52).

Build 51 writes the profile store in **record format 2** (file and Redis repositories), which carries
the profile's name and policy type. A store written by Build 50 or earlier is format 1.

- **One way.** Builds older than Build 51 have **no gate of their own**: they do not know the format
  marker, read format-2 records as corrupt (the FILE backend of Build 50 sets format-2 journals aside as
  `*.corrupt`) and write format-1 accounts beside the format-2 ones. The gate is in Build 51+ itself
  (`update/StoreDowngradeGuard`, owner decision OD-2): while the configured store is format 2
  (FILE: `<directory>/format.awfm` says format 2; Redis: the `mp:meta` hash says format 2, and a Redis
  that cannot be read at that point counts as format 2, fail closed), the auto-updater refuses to copy
  `<jar>.prev` back after a failed first boot (`RESTORE_FAILED` with the reason, the new jar stays in
  place), refuses to stage a release whose build number is below 51, and refuses to swap in a staged jar
  whose build number is below 51 or unreadable (ERROR naming the store and OD-2). Since Build 52 the bar
  is the store's required build rather than a fixed 51: 51 for a format-2 store, 52 once it holds a co-op
  record ([Store level](#enabling-co-op-build-52)), and, for a marker that cannot be read, 52 (fail closed).
  The Redis marker is checked only while MultiProfile is enabled with `repository: redis`. Mixing old and
  new writers on one store is unsupported. There is no downgrade exporter and no in-place downgrade.
- **Rollback = restore the backup.** Stop every server on the store, put the complete pre-migration
  backup (`migration.backup-dir`, written before anything is converted) back into an isolated store
  and start the older build against that. Everything written to the store after the migration is not in
  the backup and is lost on that path. This is the deployment-tooling rule of the plan (OD-2); outside the
  Build 51+ updater, keeping an old binary away from a format-2 store (a manual jar swap, a container
  recreated from an older image) is the operator's responsibility, because the old build has no gate.
- **Procedure.** (1) Stop all servers using the store. (2) Set `migration.mode: "dry-run"`, start,
  read the report in the log, stop. (3) Set `migration.backup-dir` to a path outside `awf/profiles`
  and `migration.mode: "apply"`, start. (4) Set `migration.mode` back to `"off"` once the store is
  format 2; a format-2 store is not migrated again.
- **Names and types after migration.** Each profile of a format-1 store receives a name (unique per
  account, stable across restarts) and the policy type `classic`; the consumer namespace
  (`ProfileInfo.type`) is unchanged.
- While a store is still format 1 and `migration.mode` is `off`, operations that need format 2 fail
  with `MIGRATION_REQUIRED`; an unsupported format fails with `STORE_FORMAT_REFUSED`.
- Migration needs a free store: an unresolved transition journal of the old build is a reason to
  refuse, not to guess. Resolve it first ([Troubleshooting](#troubleshooting)).
- **Every writer stopped.** The migration is offline on both repositories. The file repository enforces
  it with its exclusive directory lock. The Redis repository has **no migration lock**: before the
  backup, and again before resuming an interrupted migration, it refuses when another process still holds
  a live session, profile-transaction or player-session claim in the fabric (the holder's node is named);
  a node that holds no claim at that moment is not detected, so stopping every node that uses the Redis
  profile store is part of the procedure, not optional. Each Redis migration write re-checks atomically
  that the store marker still says the migration is in progress.
- **Redis backup.** With `repository: redis` the pre-migration backup is an export of every key of the
  profile namespace into `migration.backup-dir/multiprofile-redis-format1-<utc time>-<random>/keys/`
  (one file per key; hash, set, sorted-set and string contents, length-prefixed) with a `backup.manifest`
  (SHA-256 and size per file, last line `complete`), written and re-read before anything is converted. A
  resumed migration requires that complete manifest. Restoring it into an isolated Redis is the
  operator's step with Redis tooling; this build does not load the export back.
- A reached store bound during co-op operations (pending intents per account, profile or store; permanent
  receipts of a resource; a full index) is `CAPACITY_EXCEEDED`: pending operations resolve through
  recovery (`/awf multiprofile format` lists them); retry afterwards.

## Enabling co-op (Build 52)

Build 52 code written and compiled by an agent; not tested by the owner. Co-op is off by default. To turn
it on:

1. `aurora.awf.multiprofile.enabled: true` and a **format-2** store. A store still in format 1 refuses
   every co-op operation with `MIGRATION_REQUIRED`; run the migration first (previous section).
2. `aurora.awf.multiprofile.coop.enabled: true`, then **restart**: every `coop.*` key is RESTART_REQUIRED
   ([CONFIGURATION.md](CONFIGURATION.md#build-52-co-op-membership)). With `enabled: false` the co-op key is
   ignored with a WARN.
3. Decide `coop.allowed-types` (default classic only) and `coop.max-members` (default 5, at most 8) before
   players convert profiles. Conversion is one way, and narrowing `allowed-types` or lowering `max-members`
   later changes nothing for co-ops that already exist.

Turning `coop.enabled` back to `false` and restarting does not delete anything: co-op profiles, memberships,
open invitations and votes stay in the store and stay readable, and every co-op operation is refused with
`COOP_DISABLED`. A format-2 store (and the co-op records in it) is refused by builds older than Build 51;
rolling back means restoring the pre-migration backup, which loses everything written since.

**Store level (Build 52).** Before the first co-op record is written (the first conversion, normally), the
store marker is raised once, durably, to payload schema 2 and minimum build 52: `format.awfm` gains a
4-byte `minimumBuild` trailer, the Redis `meta` hash gets `schema 2` and a `minimumBuild 52` field, and the
startup log says `the profile store now requires Build 52 or newer`. `/awf multiprofile format` shows it
(`requires Build 52`). From then on Build 51 refuses the store at startup (FILE: it cannot parse the
trailer; Redis: it refuses schema 2, and a Build 51 node still running against the same Redis has its next
write refused), and the auto-updater refuses to install or restore a jar below Build 52
([AUTO-UPDATER.md](../../development/AUTO-UPDATER.md)). There is no in-place way back: restore the
pre-migration backup (it predates every co-op record) into an isolated store. A store that never held a
co-op record keeps the Build 51 marker and stays openable by Build 51.

What Build 52 refused and Build 53 allows (needs authoritative login: `login.authoritative` `auto` with co-op enabled, or `true`; each below still answers with a reason
code and writes nothing when refused): a member switching into a co-op profile (`COOP_SWITCH_UNAVAILABLE` in
Build 52; Build 53 refuses a non-member with `NOT_A_MEMBER` and a node that does not hold the bound world with
`WRONG_NODE`, OD-1: no cross-node routing); removing a membership that is the member's active profile (`MEMBER_ONLINE`
in Build 52; Build 53 captures and switches the member out first, see
[PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md#removing-an-online-member-build-53)).

What Build 53 still refuses on purpose: owner transfer, the owner leaving and kicking the owner, and deleting a
co-op profile that still has other members (`OWNER_ACTION_REFUSED`); a membership change while another node
holds the profile's claim, i.e. plays its bound world (`WRONG_NODE`, OD-1); removing a member whose session is on
another node (`OWNERSHIP_CONFLICT` or `MEMBER_ONLINE`). A removed member's state is recorded for salvage; Build 53
delivers nothing, Build 54 delivers it on a claim (`/coop salvage claim`, `AuroraProfiles.claimSalvage`; the first
claim raises the store to minimum build 54, see [PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md#salvage-exactly-once-od-3)).

**Build 53 settings.** `login.authoritative`, `shutdown.capture-deadline-ms` and `adapters.required` are
RESTART_REQUIRED ([CONFIGURATION.md](CONFIGURATION.md#build-53-authoritative-login-shutdown-capture-adapters)). They
apply to every profile, not only co-op. They are read at startup; none of them changes the store format. Authoritative
login writes per-account session markers (`sessions/<uuid>.awfp` in the file repository, `<fabric prefix>mp:session:<uuid>` keys in Redis) outside the
store format; a non-owner member's first switch into a co-op raises the store to minimum build 53 (Build 52 then refuses
it, like Build 51 refuses a co-op store).

**Build 54 store level.** The first salvage claim, and the first profile created of a plugin-registered type or with
a name from a type's own pool, raise the store to minimum build 54; Build 53 then refuses it at startup (rollback =
restore a backup taken before). So does the registration of a built-in type override that denies an action, and the
first bank change that writes a Build 54 trailing section into a shared record (the archived totals after a removed
membership's money is folded, or an interest posting's timestamp). Plain bank changes and transfers on a record
without that section, and profile type definitions themselves (`types/` directory, Redis `mp:types`), do not raise it. Bank and salvage receipts are permanent (never pruned; FILE `receipts/<resource>/permanent/`,
Redis `preceipts:` hashes); a resource with 1,000,000 of them refuses further financial and delivery operations
(`CAPACITY_EXCEEDED`). The bank policy keys are in [CONFIGURATION.md](CONFIGURATION.md#build-54-co-op-bank-policy).

### Commands (Build 52)

Players: `/coop` (permission `sourbycraft.coop.use`, granted to everyone by default; a permissions plugin
can remove it). The command exists only while `coop.enabled` is true (RESTART_REQUIRED); with co-op off nothing
is registered. At boot SourbyCraft registers `/sourbycraft:coop`; the bare `/coop` is claimed after every plugin
enabled (`ServerLoadEvent` STARTUP) and only when no plugin registered one, otherwise the holder is logged at INFO
and `/sourbycraft:coop` stays the way in. A `profile` is the UUID or the name of one of your profiles (default: your active
profile); an `invite` is its UUID (for `accept`/`decline` also a prefix of it, or nothing when you have exactly
one invitation); `cancel` and `vote` take the full UUID the replies print. `info` and `members` show a co-op to
its members only.

| Command | Does |
|---|---|
| `/coop [info [profile]]` | label, members and cap; the open kick vote |
| `/coop convert [profile]` | one-way conversion of your solo profile |
| `/coop invite <player> [profile]` | invite (owner, manager); the player is an online name, a cached name or a UUID |
| `/coop invites` | your open invitations |
| `/coop accept [invite]`, `/coop decline <invite>` | answer an invitation |
| `/coop cancel <invite>` | cancel an invitation (owner any, manager its own) |
| `/coop leave <profile>` | leave (not the owner; from Build 53 an online member is switched out first) |
| `/coop kick <player> [profile]`, `/coop vote <vote> <yes\|no>` | kick votes |
| `/coop members [profile]` | members |
| `/coop label <text...>` / `/coop label --clear` | the shared label of your active co-op profile (owner) |

Operators: `/awf multiprofile coop ...` acts on behalf of a named player. `info <profile>`,
`members <profile> [cursor]`, `invites <profile> [cursor]` and `invites for <player> [cursor]` need
`sourbycraft.command.awf.multiprofile`; every change also needs `sourbycraft.command.awf.admin`:
`convert <owner> <profile>`, `invite <actor> <profile> <invitee>`, `accept|decline <player> <invite>`,
`cancel <actor> <invite>`, `leave <player> <profile>`, `kick <actor> <profile> <target>`,
`vote <voter> <vote> <yes|no>`, `label <actor> <profile> <text...|--clear>`. Both forms run on the
profile I/O lane; the reply arrives on the sender's own scheduler (a player's entity scheduler) and names
the error code with a hint. Changes made by an operator are checked against the named player's role like
any other call.

A membership intent that a crash left PREPARED or COMMITTED is resolved at the next start or the player's
next login ([PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md#co-op-membership-build-52)). `/awf multiprofile
format` lists pending intents.

## Build 55: audit, archive, cooldown, guards, metrics

Review fixes folded into this build (b55-review): the audit log caps a daily file at 256 MiB (entries beyond it stay in
the ring and count as dropped) and a `close()` no longer drops writes already handed to the lane; a detail formatter
that throws never fails the audited operation; the switch guards run on their own two-thread lane with a 64-deep queue
and a hung guard is interrupted at the budget (never the shared 64-permit virtual executor AWF world operations use).

Build 55 code written; compiled by an agent; not tested by the owner (the owner runs every test, benchmark and
server since 2026-10-22). Nothing here changes a Build 51–54 safety property: every new check runs before a capture,
every new record is written after the durable outcome, and no new record type enters the profile store format.

### Audit log (Build 55)

`audit.enabled` (default on). An **immutable entry is recorded after the durable outcome** of: every switch
(`switch.committed` with the transition id and duration; `switch.refused` for an admission refusal — cooldown,
archived, lock, type rule, not a member…; `switch.vetoed` for a guard veto; `switch.failed` with the error code),
`profile.created`, `profile.deleted`, `profile.locked`/`profile.unlocked`, `profile.archived`/`profile.unarchived`,
the co-op membership operations (`coop.convert`, `coop.invite`, `coop.accept`, `coop.decline`, `coop.cancel`,
`coop.leave`, `coop.kick.open`, `coop.kick.vote`, `coop.kick.executed`, `coop.label`, each also as `<kind>.refused`
with the code when the call failed), every bank posting (`bank.deposit`, `bank.withdraw`, `bank.deposit-personal`,
`bank.withdraw-personal`, `bank.transfer`, `bank.contribute`, `bank.interest`, recorded when the ledger event fires,
i.e. after the commit) and `salvage.delivered`. An entry carries the node-local sequence, the time, the kind, the
profile, the actor, the operation or transition id, OK or the error code and a short detail.

Where: the newest 4096 entries in memory (`/awf multiprofile audit <profile> [cursor]`, `AuroraProfiles.audit`,
newest first, pages of 32), and the retained log as append-only daily files `<directory>/audit/<yyyy-MM-dd>.log`,
one tab-separated line per entry, kept `audit.retention-days` (older files deleted when a new day's file opens).
Both are **node-local**: each node records what it committed; with `repository: redis` the files still go below the
configured directory of each node.

How: `record` is non-blocking on every thread (it appends to the ring and offers one write step to the audit's own
single-slot queue on the storage lane; that queue is separate from the profile step queue, so it never delays or
reorders profile steps and holds at most one lane slot). **Audit failure never changes an outcome**: a full queue
drops the entry and counts it, a failed file write is counted and logged at most once a minute; the counters are
rows of `/perf multiprofile` and `/awf multiprofile status`. The audit log is **not** the recovery journal: the
store's intents and transitions are; nothing reads the audit log back during recovery.

### Archived (read-only) profiles

`/awf multiprofile archive <profile>` (admin) or `AuroraProfiles.archiveProfile`: the profile keeps every record
(snapshots, member streams, bank, collections, salvage, binding) and stays listed with status `ARCHIVED`
(`ProfileInfo.status()`; `ProfileDetails.archivedAtMillis()`), but it cannot be switched into, converted, joined,
labelled, locked or used for bank, collection or salvage **mutations** (`PROFILE_ARCHIVED`); reads keep working, and a
member may still **leave** an archived co-op (PM decision, review P1-2: a read-only profile must not stay stuck in a
member's account; the leave records the salvage as usual). Refused for an account's active profile (switch away first),
for a tombstone, and for a co-op while any member has it as their active profile (`PROFILE_LOCKED`, naming them) or plays
it in an admitted session on this node (`MEMBER_ONLINE`) — review P1-3: an archived profile refuses every save, so a member
left inside it would lose their quit save and a restored older head would duplicate items. `unarchive` restores the
status the profile had before the archive: `ACTIVE`, or `LOCKED` when an operator lock preceded the archive (marker
`aurora:archived-from-locked`, review P3-14); `unlock` on an archived profile is refused (use `unarchive`); `delete` still
works on it. Stored as status `LOCKED` plus the metadata marker `aurora:archived=<store clock>`, so a Build 54 server
reads an archived profile as a locked one — no store level is raised. The two markers are written by the server only:
a create request (`ProfileRequest` metadata) that carries `aurora:archived` or `aurora:archived-from-locked` is refused
`INVALID_REQUEST` (review N4), so no plugin can create a profile that reads as archived while its status is `ACTIVE`.
Where the archive refusal comes relative to a type rule depends on the action (a switch checks the archive first; the
co-op, bank and salvage mutations check the type first, so an archived profile whose type denies the action answers
`ACTION_FORBIDDEN_BY_TYPE`): see [PROFILE-TYPES.md](PROFILE-TYPES.md#rule-ids). Owner decision (HYPIXEL-PARITY §6.4): a seasonal profile (type flag
`seasonal`, built-in `bingo`) is archived read-only at the end of its event, never deleted; the server archives
nothing on its own — the consumer that runs the event calls `archiveProfile`, or an operator does.

### Switch cooldown and guards

`switching.cooldown-seconds` (default `0` = off; parity 90) refuses a switch for that long after the player's last
**committed** switch (`SWITCH_COOLDOWN`); refused, vetoed, failed or rolled-back switches start none. In memory on
this node (restart clears it); `/awf multiprofile cooldown <player>` shows it, `... reset` (admin) forgets it.

Switch guards (`AuroraProfiles.registerSwitchGuard`, at most 16): consumer hooks (combat, dungeon, open trade) that
may veto a switch. They run after admission, membership and type checks, after the target world is ready and
**before the player is frozen or captured**, off every game thread with one 2 s budget for all; a veto, an exception
or the budget running out refuses the switch with `SWITCH_VETOED` (WARN naming the plugin for the latter two),
leaves the player exactly as they were and consumes no cooldown. After the hooks the run re-checks its reservation,
the player's presence and the deadline, then fires `ProfilePreSwitchEvent`. A guard that calls `switchProfile` for the
same player is refused `SWITCH_IN_PROGRESS` by the running switch's reservation. A disabled plugin's guards are
removed with its adapters.

### Type rules at every engine action boundary

Build 55 adds the engine-owned rules `profile.switch` (checked on the target at every switch admission),
`coop.kick` (opening a kick vote and casting a ballot, review P3-9) and `coop.label` (the shared label); with the Build 54
rules, every engine mutation that a type could sensibly deny now passes `ProfileTypes.require` before anything is written
([PROFILE-TYPES.md](PROFILE-TYPES.md#rule-ids)). Reads, leave, decline and cancel stay ungated. `profile.switch` is the
one rule that does **not** gate a profile whose type has no definition on the server (review P2-7): Build 54 stores hold
profiles with free-form `aurora:type` ids that were always playable, and `types.unknown-type-policy: deny` must not lock
their owners out; every other engine action keeps the deny policy for unknown types. The move-out switch a leave or
kick makes for a member (evacuation) is not the player's own switch: it skips the cooldown, the guards and the target's
`profile.switch` rule, and does not start a cooldown (review P3-8). The three Build 55 ids are allowed unless a
definition names them in its `rules`; its `defaultRule` does not decide them (review N2), so a Build 54 type with a deny
default rule stays playable and keeps its kick votes and labels after the upgrade.

### `/perf multiprofile`

In-memory rows, no store I/O: committed switches with latency p50/p95/p99 (last 1024 commits on this node), switches
failed, refused by cooldown, vetoed and guard timeouts; the profile I/O queue (waiting/running/peak, partitions, queue
wait p50/p95/p99 over the last 1024 steps); outstanding salvage records and the oldest one's age, open kick votes and
the oldest's age, as this node saw them (listings, claims, votes it ran; ages against this node's clock); audit
recorded/dropped/write failures; profile events dropped. Build 55.1 adds the session handoff rows
(`coordination.handoff-wait-ms`): claim waits started / admitted / timed out / left / failed (a join can wait twice:
session claim, then node claim), and the p50/p95 of the admitted waits (last 1024). Everything resets at restart.

### Commands (Build 55)

| Command | Permission | Does |
|---|---|---|
| `/awf multiprofile audit <profile> [cursor]` | `...multiprofile` | the in-memory audit entries about a profile, newest first |
| `/awf multiprofile archive <profile>` / `unarchive <profile>` | `...multiprofile.admin` | read-only archive and its reversal |
| `/awf multiprofile cooldown <player> [reset]` | `...multiprofile` (`reset`: `.admin`) | the player's switch cooldown; forget it |
| `/perf multiprofile` | `/perf` | the metrics above |

## Backups

Before the Build 51 format-2 migration the backup in `migration.backup-dir` is the only rollback
(see above). Back up `awf/profiles/` together with the AWF world folders, with the server stopped. Snapshots and
`accounts/<player>.legacy*.awfp` pre-profile archives are checksummed but not encrypted; treat them as
player data. Restore the directory as a whole (records, snapshots and journals from the same backup):
the switch refuses only a snapshot older than its profile record (`STALE_STATE`), so an old profile
record restored next to newer snapshots is accepted without a warning. With `repository: redis` the
backup is Redis's own persistence (RDB/AOF) of the `<prefix>fabric:mp:*` and session keys.

## Troubleshooting

| Symptom | Cause | Action |
|---|---|---|
| `AWF MultiProfile is unavailable` in `/awf` | `enabled: false`, or MultiProfile refused to start | check the startup error naming the key or the Redis problem |
| startup: profile directory `is in use by ...` | another process holds `repository.lock` | one directory per server; stop the other server |
| switch or save fails with `OWNERSHIP_CONFLICT` | another process holds the profile, or this node's claim lapsed or was superseded | `/awf multiprofile owner <id>`; wait for the TTL, or `reclaim <id> <epoch>` when that holder is gone |
| `RECOVERY_REQUIRED` | a previous transition is not finished | `/awf multiprofile status` then `recover <id>`; a `RECOVERING` journal of an offline player resolves at their next join |
| `IO_FAILURE` naming an unreadable journal | the file repository moved it to `transitions/*.corrupt`, or a Redis journal cannot be decoded | inspect it; file: delete the `*.corrupt` file; Redis: run the `HDEL`/`SREM` commands printed in the error, plus `ZREM <prefix>fabric:mp:journal-failed <id>` |
| `IO_FAILURE: profile writes are refused` | Redis repository empty, directory still holds profiles | migrate (above), or move the directory away to start empty |
| `STALE_STATE` | a snapshot is older than the profile revision, or another operation changed the record first | do not force it; investigate who wrote the profile |
| `STATE_TOO_LARGE` | the encoded state exceeds 8 MiB | reduce the inventory; nothing was stored |
| `WORLD_UNAVAILABLE` | the world is missing and the binding has no template, or a lifecycle operation is running | create the world through AWF, or retry |
| WARN `the session claim of ... lapsed and was claimed again` | a renewal failed (expired lease, Redis outage) while the player was online | none; writes under the old claim are refused |
| WARN `... was taken over by ... and was dropped` | another process holds the player's profile | saves for that player are refused until their next join claims it again |
| Idle worlds never unload | players inside, `idle-unload-seconds: 0`, or the world is not an AWF world bound to a profile | expected: the sweep skips worlds with players without saving or warning, and never unloads the main world or a world no binding touched |
| Shutdown logs "profile I/O still queued" | the drain budget elapsed | transitions stay in the journal; they are recoverable |
| co-op call fails with `COOP_DISABLED` | `coop.enabled` is false (default), or `enabled: false` | set `coop.enabled: true` and restart; WARN at startup names the key when `enabled` is false |
| co-op call fails with `MIGRATION_REQUIRED` | the store is still format 1 | run the format-2 migration |
| `MEMBER_ONLINE` on leave or kick | the member's active profile is the co-op and they cannot be captured and switched out here (for example their session is on another node) | run it on the member's node, or when they are offline; the removal intent is retried, nothing was removed |
| `WRONG_NODE` on a membership change | another node holds the profile's claim, i.e. plays its bound world (OD-1) | run it on that node, or when nobody plays the profile |
| `OWNERSHIP_CONFLICT` on a kick | the target has a session on another node | run it on that node, or while the target is offline |
| kick fails after `vote passed and is recorded` | the vote committed, the removal did not | fix the cause named in the message, then approve the vote again (`/coop vote <vote> yes`) |
| startup: `store format marker ... cannot be read` / `schema 2` on Build 51 | the store holds co-op records (minimum build 52) | run Build 52 or newer; going back means restoring the pre-migration backup |
| `OWNER_ACTION_REFUSED` on delete | the co-op profile still has other members | they leave or are kicked first |
| `COOP_SWITCH_UNAVAILABLE` | Build 52 only: a member tried to switch into a co-op profile they do not own | Build 53 allows it; a non-member gets `NOT_A_MEMBER` |
| `NOT_A_MEMBER` on a switch into a co-op | the account is not a current member (a removed member included) | invite the player again; a rejoin is a new membership with an empty state |
| `PROFILE_LOCKED` on `/awf multiprofile lock` naming members (Build 56) | those members have the co-op as their active profile (on this node, another node or offline): a lock would refuse their saves. They switch away or leave, then lock |
| `MEMBER_ONLINE` on a leave or kick naming "a live session on another node" (Build 56) | the member plays the co-op on the other node; a satellite cannot switch them out (switching off) and the island cannot take their `Q`. Retry once they quit, or run the removal where they play after switching them out |
| ERROR `AWF MultiProfile could not start` naming `coop.cross-node` or `coop.world-host`, and the server runs without MultiProfile (Build 56) | `cross-node: true` needs `mode: distributed` + `repository: redis`; `world-host: false` needs `cross-node: true` and `switching.enabled: false`. The boot stage failed with a WARN and players are on vanilla playerdata until the keys are fixed and the server restarted; nothing was written to the store |
| `WRONG_NODE` on a switch or join into a co-op | the node does not hold the co-op's bound world (OD-1) | play it on the node that holds the world; nothing routes players between nodes |
| `HEAD_UNREADABLE` at join | authoritative login could not read the committed head of the player's active profile | the player stays out (kicked with a message) and nothing is loaded from vanilla data; check storage and `/awf multiprofile status`, fix the store or restore the backup, the next join retries |
| `RECOVERY_REQUIRED` / `OWNERSHIP_CONFLICT` / `IO_FAILURE` at join | a pending journal recovery did not resolve, the node claim or session claim was not granted, or the session marker could not be written | the join is refused rather than played on vanilla playerdata; resolve the journal (`/awf multiprofile recover`), check the other node or the storage, the next join retries |
| `STATE_TOO_LARGE` / `ADAPTER_REQUIRED_FAILED` during play | a lockstep (autosave) or quit capture cannot be stored: the snapshot or its adapters exceed their bound, or a REQUIRED adapter failed | the player is frozen and disconnected; the WARN names the cause; at the next join the vanilla playerdata written at the quit is adopted only if it can be stored, else the join is refused with the same code until the plugin or the data is fixed |
| `the session marker of <player> is unreadable` (WARN) | a corrupt `sessions/<uuid>.awfp` (or Redis value) | the join is not refused: the committed head is restored and no vanilla playerdata is adopted (the safe choice: nothing duplicated, progress held only in vanilla playerdata may be lost); the next admission replaces the record; `/awf multiprofile session <player> reset-marker` forces the same outcome |
| `joined into profile ... adopted the vanilla playerdata` (INFO) | the previous session was unclean (crash, kill, deadline, refused save) or this is the first authoritative login (upgrade), and the vanilla file was newer than the profile head | nothing to do; the vanilla state became the head's next snapshot. A following `the committed head is restored` INFO names why no adoption happened |
| `ADAPTER_REQUIRED_FAILED` on a switch | a REQUIRED adapter threw, exceeded its size bound or the snapshot budget | the switch was refused or rolled back; read the WARN naming the adapter id and fix the plugin; `adapters.required` lists the ids forced to REQUIRED |
| `ADAPTER_MISSING` on a switch or join | a REQUIRED adapter that holds data for the profile is not registered here | load or fix the plugin and retry; the data is kept untouched meanwhile. A node that by design runs without that plugin (a lobby without the island plugin's `economy` purse) lists the id in `adapters.preserve-unregistered` (Build 55.1): the data then passes through unchanged and the node with the plugin applies it again |
| `UNKNOWN_OUTCOME` on a membership call | the store did not confirm the write | do not repeat; list members/invitations and let recovery resolve the intent |
| `Your profile is active on <node>` on every proxy server switch | the previous server has not committed the quit save and released the player's session claim when the new server attempts it (Build 51–55: one attempt) | set `coordination.handoff-wait-ms: 5000` on every node and restart (Build 55.1); the join then waits for the release, never taking a live claim. `/perf multiprofile` shows the waits; `timed out` rising means the previous server's quit save takes longer than the window (Redis `WAITAOF`, storage) or that server is down (its claim lapses at the TTL) |

## Shutdown

`ProfileRuntime.stop(budget)` removes this node from the routing directory, drains the engine's queued
I/O before the storage lane is shut down, releases every session claim, unregisters the
`AuroraProfiles` service, and reports a timeout as incomplete. A heartbeat thread renews session claims
under both providers (Redis: every `sessions.heartbeat-seconds`; local: every 15 s for the 60 s lease),
together with the claims of running switches. The drain covers queued steps; a switch waiting on an
entity-thread task can outlive it, in which case its journal remains and is recovered at the next join.
A switch is never reported as saved because the process is exiting.

### Shutdown capture (Build 53)

Build 53 changes the order (PM-076, feature patch 0081): on `stop`, MultiProfile gates the online players
of an active profile, captures each through the ticketed quit capture and saves it, **then** the server removes
the players, then MultiProfile closes as above. Build 52 closed it first and wrote no quit snapshot.

The wait is bounded by `shutdown.capture-deadline-ms` (default 10000, 1000 to 60000, RESTART_REQUIRED). Stopping the
server therefore takes up to that long longer when captures are slow; give a service manager or panel a stop
timeout larger than the deadline plus the existing drain budget, or it may kill the process mid-save.

When the deadline passes:

- players already saved are as after a normal quit;
- players not saved keep their last committed snapshot and any pending journal or intent; the log names each such
  player and the profile; progress since their last snapshot is lost;
- shutdown continues without waiting further; the pending intents are recovered at the next start or the player's
  next join, and authoritative login does not load vanilla playerdata over them;
- nothing is reported as saved that was not.

A crash, `kill -9` or a stop timeout that kills the process skips this entirely: only the last lockstep (autosave) or
quit snapshot exists. The next authoritative login finds the session marker still open and restores that snapshot, or
adopts the vanilla playerdata when the server wrote it later than the snapshot and it belongs to the active profile
([PROFILE-LIFECYCLE.md](PROFILE-LIFECYCLE.md#authoritative-login-build-53)).
