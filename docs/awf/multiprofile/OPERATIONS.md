# Operations

## Enabling

1. `aurora.awf.multiprofile.enabled: true` in `sourbycraft_config/aurora.yml`.
2. Keep `mode: standalone` unless you have a qualified multi-node deployment.
3. Restart. The startup log line names the mode, node, directory and ownership provider.

For distributed mode additionally: `aurora.awf.redis.fabric.enabled: true`,
`coordination.provider: redis`, a reachable Redis with TLS/ACL as for the AWF Redis backend, then
`mode: distributed`. The server refuses to start MultiProfile (and logs why) if the fabric is off or
Redis is unreachable; it does not silently fall back to local ownership.

Ensure the profile worlds exist as AWF worlds, or set a template on the binding through the consumer
that creates profiles (`ProfileWorldBinding`, currently internal; a public binding API is deferred).

## Commands

| Command | Permission | Purpose |
|---|---|---|
| `/awf multiprofile status` | `sourbycraft.command.awf.multiprofile` | mode, node, ownership, isolation, pending transitions |
| `/awf multiprofile list <player\|uuid>` | same | profiles, revisions, active marker |
| `/awf multiprofile create <player> [type]` | `...multiprofile.admin` | create a profile for testing or administration |
| `/awf multiprofile switch <player> <profile-uuid>` | `...multiprofile.admin` | switch a player; reports the reason, duration and journal id |
| `/awf multiprofile inspect <profile-uuid>` | same | account, type, worlds, metadata |
| `/awf multiprofile recover <transition-uuid>` | `...multiprofile.admin` | roll forward or back an incomplete switch |
| `/awf redis status` | `sourbycraft.command.awf.redis` | reachability, version, pools, counters |
| `/awf redis pools` | same | per-workload size and use |
| `/awf redis events` | same | stream length, retention, publish/consume/retry/dead-letter |
| `/awf redis sessions <profile-uuid>` | same | session holder |
| `/awf redis ownership <profile-uuid>` | same | coordination claim |

## Backups

Back up `awf/profiles/` together with the AWF world folders. Snapshots are checksummed but not
encrypted; treat them as player data. Restoring a snapshot directory without the matching profile
record is safe (unused files) but restoring an old profile record with a newer snapshot is refused by
the `STALE_STATE` rule.

## Troubleshooting

| Symptom | Cause | Action |
|---|---|---|
| `AWF MultiProfile is disabled` in `/awf` | `enabled: false` or the config could not be read | check the startup warning naming the file/key |
| switch fails with `OWNERSHIP_CONFLICT` | another node holds the profile, or the fence expired | wait for the TTL or run recovery; check `/awf redis sessions <id>` |
| `RECOVERY_REQUIRED` | a previous transition is not finished | `/awf multiprofile status` then `recover <id>` |
| `STALE_STATE` | a snapshot is older than the profile revision | do not force it; investigate who wrote the profile |
| `WORLD_UNAVAILABLE` | the world is missing and the binding has no template, or a lifecycle operation is running | create the world through AWF, or retry |
| Idle worlds never unload | players inside, or `idle-unload-seconds: 0` | expected: unload refuses with players present |
| Shutdown logs "profile I/O still queued" | the drain budget elapsed | transitions stay in the journal; they are recoverable |

## Shutdown

`ProfileRuntime.stop(budget)` drains the engine's queued I/O on the Storage lane before that lane is
shut down, releases every session claim (the switch's adopted claim included), unregisters the
`AuroraProfiles` service, and reports a timeout as incomplete. A heartbeat thread renews session
claims at `sessions.heartbeat-seconds` when Redis ownership is used. The drain covers tasks
queued on the lane; a switch that is waiting on an entity-thread task can outlive it, in which case
its journal remains and is recovered at the next start/join. A switch is never reported as saved
because the process is exiting.
