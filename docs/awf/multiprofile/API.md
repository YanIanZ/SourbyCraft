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
enable. A disabled feature registers nothing.

## Value types (`dev.iyanz.sourbycraft.api.profile`)

- `ProfileRequest(accountId, type, metadata)` — `type` is `[a-z0-9_-]{1,32}`.
- `ProfileInfo(id, accountId, type, metadata, createdAtMillis, revision, status, active, worlds)`.
- `ProfileWorldRef(world, role, loaded)` — `role` is `PRIMARY`, `NETHER`, `END`, `SHARED` or `AUXILIARY`.
- `ProfileSwitchResult(success, errorCode, message, profile, transitionId, durationMillis)`.
- `ProfileOwner(node, epoch, expiresAtMillis, distributed)`.
- `ProfileErrorCode` — `DISABLED`, `SWITCHING_DISABLED`, `PLAYER_OFFLINE`, `PROFILE_NOT_FOUND`,
  `NOT_OWNED`, `SLOT_LIMIT`, `PROFILE_LOCKED`, `SWITCH_IN_PROGRESS`, `STALE_STATE`,
  `OWNERSHIP_CONFLICT`, `WORLD_UNAVAILABLE`, `TIMEOUT`, `RECOVERY_REQUIRED`, `RECOVERED`, `IO_FAILURE`,
  `INVALID_REQUEST`, `INTERNAL_ERROR`.
- `ProfileOperationException` — unchecked, carries the code for methods other than `switchProfile`
  (which returns a result instead).

## Threading contract

Every method returns a `CompletableFuture`. Callbacks have no guaranteed thread. **Never** call
`join()`/`get()` on a region, entity or global-tick thread; attach `thenAccept`/`whenComplete` and use
`SourbyReply.run` when the result must be delivered to a command sender. The engine itself never joins
on a tick thread and never performs Redis I/O from one.

`switchProfile` is rejected with `SWITCH_IN_PROGRESS` while another switch for the same player runs.
Deleting the active profile returns `PROFILE_LOCKED`. A switch failure leaves the player on the
previous profile (code `RECOVERY_REQUIRED` until recovery ran) — it never throws.

## State adapters

```java
profiles.registerProfileStateAdapter(this, new ProfileStateAdapter() {
    public String id() { return "example:claims"; }
    public byte[] capture(Player player) { /* return null when nothing to store */ }
    public void apply(Player player, byte[] data) { /* restore */ }
    public Plugin plugin() { return ExamplePlugin.this; }
});
```

Adapters run inside the same entity task as the core capture/apply. Bytes are stored verbatim under
`id:version`; bump `version()` when the format changes. Limits: 2 MiB per adapter, 32 adapters per
profile. A failing adapter is logged and skipped.

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
not use profiles do not depend on Redis or on this API.

## Build 50 world-binding API (owner testing pending)

```java
var request = new ProfileWorldBindingRequest(profileId,
    Map.of("island_50", ProfileWorldRole.PRIMARY), "starter_template");
profiles.bindProfileWorlds(request).thenCompose(ignored -> profiles.getProfileWorlds(profileId));
```

Use `dev.iyanz.sourbycraft.api.profile` types. This replaces the entire binding (0–16 worlds),
without creating or deleting worlds. Empty map unbinds. Names are `[a-z0-9_-]{1,48}`.
Only an inactive, unclaimed, non-deleted profile can be rebound, outside an account transition.
World creation/loading happens on activation. Roles describe relationships, not access permissions;
the consumer must authorize player requests and enforce shared-world membership itself.
Admin commands: `/awf multiprofile bind <profile-uuid> <world> [template]` replaces the binding with
one PRIMARY world; `/awf multiprofile unbind <profile-uuid>` clears it. Both require
`sourbycraft.command.awf.multiprofile.admin` in addition to normal AWF permissions.

Distributed mode refuses startup in this candidate. Account/snapshot metadata operations serialize
in-process; this is not cross-node atomic fencing. Save revalidates account revision and the exact
ownership claim after capture; capture spanning A→B→A is rejected. Recovery holds the player's
transition reservation until its future completes. Changes require the owner regression cases in
[Tester Owner](../../tester%20owner.md).
