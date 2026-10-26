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

## Backups

Back up `awf/profiles/` together with the AWF world folders, with the server stopped. Snapshots and
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
