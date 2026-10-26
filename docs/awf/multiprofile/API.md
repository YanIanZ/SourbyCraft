# API

## Obtaining the service

```java
AuroraProfiles profiles = Bukkit.getServicesManager().load(AuroraProfiles.class);
if (profiles == null || !profiles.isEnabled()) {
    // AWF MultiProfile is off on this server.
    return;
}
```

The service is registered by `ProfileRuntime` during the `awf multiprofile` boot stage, before plugins
enable. A disabled feature (or one that refused to start) registers nothing.

## Value types (`dev.iyanz.sourbycraft.api.profile`)

- `ProfileRequest(accountId, type, metadata)` — `type` is `[a-z0-9_-]{1,32}`; metadata at most 32
  entries, keys 1–64 characters, values up to 512.
- `ProfileInfo(id, accountId, type, metadata, createdAtMillis, revision, status, active, worlds)`.
- `ProfileWorldRef(world, role, loaded)` — `role` is `PRIMARY`, `NETHER`, `END`, `SHARED` or `AUXILIARY`.
- `ProfileSwitchResult(success, errorCode, message, profile, transitionId, durationMillis)`.
- `ProfileOwner(node, epoch, expiresAtMillis, distributed)`.
- `ProfileErrorCode` — `DISABLED`, `SWITCHING_DISABLED`, `PLAYER_OFFLINE`, `PROFILE_NOT_FOUND`,
  `NOT_OWNED`, `SLOT_LIMIT`, `PROFILE_LOCKED`, `SWITCH_IN_PROGRESS`, `STALE_STATE`,
  `OWNERSHIP_CONFLICT`, `WORLD_UNAVAILABLE`, `TIMEOUT`, `RECOVERY_REQUIRED`, `RECOVERED`, `IO_FAILURE`,
  `INVALID_REQUEST`, `STATE_TOO_LARGE`, `INTERNAL_ERROR`. `STATE_TOO_LARGE` was added in Build 50 PR #62
  before `INTERNAL_ERROR`; a plugin that persisted ordinals must map them again.
- `ProfileOperationException` — unchecked, carries the code for methods other than `switchProfile`
  (which returns a result instead).

Codes worth handling: an invalid type or metadata is `INVALID_REQUEST`; a state whose encoding exceeds
8 MiB is `STATE_TOO_LARGE` (nothing stored, size in the message); a switch whose rollback is still
pending is `RECOVERY_REQUIRED`; `saveProfile`, a switch, `deleteProfile`, `bindProfileWorlds` and
`setProfileLocked` are refused with `RECOVERY_REQUIRED` while a transition journal of the account is
pending; `IO_FAILURE` when the player's journal is unreadable, a write failed, or the Redis repository
refuses writes before its migration; `OWNERSHIP_CONFLICT` when another process holds the profile or this
node's claim no longer holds it; `INTERNAL_ERROR` when the profile I/O queue is full.

## Threading contract

Every method returns a `CompletableFuture`. Callbacks have no guaranteed thread. **Never** call
`join()`/`get()` on a region, entity or global-tick thread; attach `thenAccept`/`whenComplete` and use
`SourbyReply.run` when the result must be delivered to a command sender. The engine itself never joins
on a tick thread and never performs Redis I/O from one: repository, ownership and Fabric-publish work
runs inside the engine's profile I/O steps, which run one at a time on the storage lane (a bounded queue
of 4096 steps per server).

`switchProfile` is rejected with `SWITCH_IN_PROGRESS` while another switch, a recovery or a join's
return teleport runs for the same player. Deleting the active profile returns `PROFILE_LOCKED`. A
switch failure never throws: the result carries the code, and a failure after the target state reached
the player is rolled back, or reported as `RECOVERY_REQUIRED` while the rollback is pending.

## State adapters

```java
profiles.registerProfileStateAdapter(this, new ProfileStateAdapter() {
    public String id() { return "example:claims"; }
    public int version() { return 1; }
    public byte[] capture(Player player) { /* return null when nothing to store */ }
    public void apply(Player player, byte[] data) { /* restore */ }
    public Plugin plugin() { return ExamplePlugin.this; }
});
```

Adapters run inside the same entity task as the core capture/apply. Bytes are stored verbatim under
`id:version`; bump `version()` when the format changes. `version()` is read once, at registration.

- Limits: 2 MiB per adapter and 6 MiB for all adapters of one snapshot (an adapter past either is
  skipped for that capture with a warning), at most 32 adapters, and an `id:version` key of at most 64
  characters.
- Registration throws `IllegalArgumentException` for a malformed id, a version below 1, a key over 64
  characters, an id another enabled plugin registered, or a 33rd id; `ProfileOperationException`
  (`INVALID_REQUEST`) when the registering plugin is not enabled. A plugin may replace its own adapter;
  disabling a plugin removes its adapters.
- Data stored under another version is not applied (one warning) and stays in the profile, within the
  limits, until the adapter stores data at its current version. Data of adapters not registered on this
  boot is carried into new snapshots. Data of an adapter registered at the stored version is not kept
  when it returns `null`, throws or exceeds a limit.
- A failing capture or apply is logged and skipped; it never fails the switch.

## Example: create and switch

```java
profiles.createProfile(ProfileRequest.of(player.getUniqueId(), "skyblock"))
    .thenCompose(created -> profiles.switchProfile(player.getUniqueId(), created.id()))
    .whenComplete((result, error) -> {
        if (error != null) return;
        if (!result.success()) {
            logger.warn("switch failed: {} {}", result.errorCode(), result.message());
        }
    });
```

## Compatibility

`AuroraWorlds` keeps its exact contract. `AuroraProfiles` is a new, separate service; plugins that do
not use profiles do not depend on Redis or on this API. The newer methods (`bindProfileWorlds`,
`setProfileLocked`, `setProfileSlotLimit`) are `default` methods that fail with
`UnsupportedOperationException` on other implementations.

## Build 50 world-binding API (owner testing pending)

The binding API and the bind/unbind commands landed in PR #61, after the `v26.2-r50` tag, so they are
absent from the published prerelease jar; the lock and slot methods landed in PR #62 (code written and
statically reviewed; not built; not tested by the owner).

```java
var request = new ProfileWorldBindingRequest(profileId,
    Map.of("island_50", ProfileWorldRole.PRIMARY), "starter_template");
profiles.bindProfileWorlds(request).thenCompose(ignored -> profiles.getProfileWorlds(profileId));
```

Use `dev.iyanz.sourbycraft.api.profile` types. This replaces the entire binding (0–16 worlds),
without creating or deleting worlds. Empty map unbinds. Names are `[a-z0-9_-]{1,48}`. Only an inactive
profile in status `ACTIVE` (not `LOCKED`, not deleted) that no process holds can be rebound, outside an
account transition and with no pending journal. Roles describe relationships, not access permissions;
the consumer must authorize player requests and enforce shared-world membership itself.

World creation and loading happen on activation and on the join of a player whose active profile has
bindings, never at boot; a missing world is created from the binding's template. With
`isolation.location` on:

- a profile without a stored location is entered at the spawn (block-centred) of its first PRIMARY
  world; when that spawn is not known the player stays where they are with a warning, and the switch
  still succeeds. The PRIMARY spawn is also the last fallback destination of a switch;
- on join, a player outside the bound worlds while one of them was unloaded is returned to the
  profile's last location in them, or to its PRIMARY spawn when none is stored, bounded by
  `switching.timeout-ms` and only while no journal is pending. That destination may be older than the
  last session when no quit snapshot was written (shutdown). A switch that starts before the join
  teleport takes its reservation wins; one started after it is refused with `SWITCH_IN_PROGRESS` until
  the teleport ends.

Admin commands: `/awf multiprofile bind <profile-uuid> <world> [template]` replaces the binding with
one PRIMARY world (names lower-cased); `/awf multiprofile unbind <profile-uuid>` clears it. Both require
`sourbycraft.command.awf.multiprofile.admin` in addition to normal AWF permissions.

Account and profile metadata writes compare the stored revision: within one process for the file
repository, atomically with the claim check for the Redis repository (EXPERIMENTAL; see
[DISTRIBUTED-OWNERSHIP.md](DISTRIBUTED-OWNERSHIP.md)). A save revalidates the account revision and the
exact claim checked before the capture, so a capture spanning A→B→A is rejected. Recovery holds the
player's transition reservation until its future completes. Owner regression cases are in
[Tester Owner](../../tester%20owner.md).
