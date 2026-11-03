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
them between servers, no quit snapshot at shutdown, no proxy handoff.

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

What Build 52 refuses on purpose (each with a reason code, nothing written): a member switching into a
co-op profile they do not own (`COOP_SWITCH_UNAVAILABLE`, Build 53); removing a membership that is the
member's active profile (`MEMBER_ONLINE`, Build 53; a member online in another profile can leave or be
kicked); owner transfer, the owner leaving and kicking the owner, and deleting a co-op profile that still
has other members (`OWNER_ACTION_REFUSED`); a membership change while another node holds the profile's
claim, i.e. plays its bound world (`WRONG_NODE`, OD-1); removing a member whose session is on another node
(`OWNERSHIP_CONFLICT`). A removed member's state is recorded for salvage but nothing is delivered (Build
54). A member added by an accepted invitation is listed and counts against the size cap and the account's
slot limit, but cannot play the profile yet.

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
| `/coop leave <profile>` | leave (not the owner; not while it is your active profile) |
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
| `MEMBER_ONLINE` on leave or kick | the co-op profile is the member's **active** profile; Build 52 does not remove an active membership | the member switches to another profile first (being online elsewhere is fine); enabled in Build 53 |
| `WRONG_NODE` on a membership change | another node holds the profile's claim, i.e. plays its bound world (OD-1) | run it on that node, or when nobody plays the profile |
| `OWNERSHIP_CONFLICT` on a kick | the target has a session on another node | run it on that node, or while the target is offline |
| kick fails after `vote passed and is recorded` | the vote committed, the removal did not | fix the cause named in the message, then approve the vote again (`/coop vote <vote> yes`) |
| startup: `store format marker ... cannot be read` / `schema 2` on Build 51 | the store holds co-op records (minimum build 52) | run Build 52 or newer; going back means restoring the pre-migration backup |
| `OWNER_ACTION_REFUSED` on delete | the co-op profile still has other members | they leave or are kicked first |
| `COOP_SWITCH_UNAVAILABLE` | a member tried to switch into a co-op profile they do not own | not available until Build 53 |
| `UNKNOWN_OUTCOME` on a membership call | the store did not confirm the write | do not repeat; list members/invitations and let recovery resolve the intent |

## Shutdown

`ProfileRuntime.stop(budget)` removes this node from the routing directory, drains the engine's queued
I/O before the storage lane is shut down, releases every session claim, unregisters the
`AuroraProfiles` service, and reports a timeout as incomplete. A heartbeat thread renews session claims
under both providers (Redis: every `sessions.heartbeat-seconds`; local: every 15 s for the 60 s lease),
together with the claims of running switches. The drain covers queued steps; a switch waiting on an
entity-thread task can outlive it, in which case its journal remains and is recovered at the next join.
A switch is never reported as saved because the process is exiting.

Feature patch 0081 closes SourbyCraft services, MultiProfile included, before the server saves and
removes players: players online at shutdown get **no quit snapshot** (logged once with the count), and
their active profile's live state stays in vanilla playerdata until a later save or switch captures it.
