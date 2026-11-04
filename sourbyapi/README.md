# sourbyapi

SourbyCraft's plugin API artifact — `dev.iyanz.sourbycraft:sourbyapi`. This is the module every
SourbyCraft plugin (and any Bukkit/Paper plugin running on SourbyCraft) compiles against.

## What this actually is

**`sourbyapi` republishes the Paper API surface under the SourbyCraft group id, plus
SourbyCraft's own `dev.iyanz.sourbycraft.api.*` packages: the region-threading API additions
(formerly Canvas's API, moved here in Build 48), the read-only metrics contract, the Aurora World
Fabric world and MultiProfile services, and the Aurora Bridge scheduler markers.** It is not a
reimplementation of the upstream APIs. Its `build.gradle.kts` (materialized from Paper's
`paper-api/build.gradle.kts` by paperweight — see `sourbyapi/build.gradle.kts.patch` and the
`upstreams.paper { ... }` block in the root `build.gradle.kts`) points its Gradle source sets at:

- `../paper-api/src/main/java` — upstream Paper's public API (`org.bukkit.*`, `io.papermc.paper.*`)
- `src/main/java` — the SourbyCraft-owned API packages listed under *SourbyCraft API packages*

Upstream classes retain their existing `org.bukkit` and `io.papermc.paper` package names.
`paper-patches/files/` holds the changes to the *upstream Paper API sources* that used to come
from Canvas (region-threading API hooks, javadoc); they apply before the sources land in
`paper-api/`. SourbyCraft's own edits to those sources are the feature patches in
`paper-patches/features/` (*API feature patches* below).

What SourbyCraft *does* change is the packaging:

- **Group id**: published as `dev.iyanz.sourbycraft:sourbyapi` instead of `io.papermc.paper:paper-api`.
- **Automatic-Module-Name**: still `org.bukkit` — deliberately, so the module name plugins see on the
  module path is unchanged.
- **Capability aliases**: upstream `paper-api/build.gradle.kts` declares Gradle module
  capabilities for `io.papermc.paper:paper-mojangapi`, `com.destroystokyo.paper:paper-api`,
  `org.spigotmc:spigot-api` and `org.bukkit:bukkit`, so the jar can substitute for those
  coordinates in a dependency graph. That block is inherited, not added by
  `build.gradle.kts.patch`, and cannot be checked in this tree before `applyAllPatches`
  materializes the build file; confirm it in the generated `.module` metadata of a published jar.
- **POM licence**: `GPL-3.0-only` (`build.gradle.kts.patch`; it read `GPL-3.0` before PR #62).

In short: **this is the branded upstream API surface plus SourbyCraft's own API packages.** A plugin
built against `sourbyapi` compiles against the exact same `org.bukkit`/`io.papermc.paper` classes it
would against upstream Paper and may also use the SourbyCraft services below.

## SourbyCraft API packages

| Package | Contents | Documentation |
| --- | --- | --- |
| `dev.iyanz.sourbycraft.api` | `Unsupported` (marks upstream API members the region-threaded server does not support); since Build 54 `SourbyAPI` (the one entry point to every service below), `SourbyBuild`, `FeatureUnavailableException` | *Getting started* below; *Breaking change in Build 48* below |
| `dev.iyanz.sourbycraft.api.event`, `.event.world` | Region-threading async events (teleport, portal, respawn, player save, end credits, world unload) | *Breaking change in Build 48* below |
| `dev.iyanz.sourbycraft.api.region` | `RegionThreadingTickManager`, `RegionTickData`, `WorldRegionizer` | *Breaking change in Build 48* below |
| `dev.iyanz.sourbycraft.api.simd` | `SIMDChecker`, `SIMDDetection`, `VectorMapPalette` | *Breaking change in Build 48* below |
| `dev.iyanz.sourbycraft.api.metrics` | `SourbyMetrics` and its immutable snapshots: the read-only metrics service | *Consuming it* below |
| `dev.iyanz.sourbycraft.api.world` | `AuroraWorlds` (Aurora World Fabric worlds and templates at runtime), `WorldRequest`, `WorldProperties`, `UnloadResult`, `WorldOperationBusyException`; since Build 54 `AwfWorldSummary`, `AwfWorldInfo`, `AwfBackendKind`, `AwfPersistenceMode`; also `WorldUnloadResult` from the former Canvas API | [developing-awf.md](../docs/guides/developing-awf.md), [aurora-world-fabric.md](../docs/architecture/aurora-world-fabric.md) |
| `dev.iyanz.sourbycraft.api.profile` | `AuroraProfiles` (AWF MultiProfile: durable player profiles), its requests, results, error codes, `ProfileStateAdapter`, since Build 51 `ProfileDetails` and `ProfileType`, since Build 52 the `Coop*` types, three async `Coop*Event`s and the co-op methods, and since Build 53 the adapter contract (`ProfileAdapterDescriptor`, `ProfileDataCriticality`, `ProfileDataScope`) | [MultiProfile API](../docs/awf/multiprofile/API.md) |
| `dev.iyanz.sourbycraft.api.profile` (co-op bank and salvage, Build 54) | `AuroraProfiles` bank, collections and salvage methods, `ProfileBankInfo`, `ProfileCurrency`, `ProfileBankAccount`, `ProfileCollectionInfo`, `ProfileSalvageInfo`, `SalvageDeliveryResult`, `ProfileBankTransactionEvent`, `ProfileSalvageDeliveredEvent` | [MultiProfile API](../docs/awf/multiprofile/API.md#co-op-bank-collections-and-salvage-build-54) |
| `dev.iyanz.sourbycraft.api.profile` (profile types, Build 54) | `ProfileTypeRegistry` (a Bukkit service, registered only while MultiProfile runs; also `AuroraProfiles.typeRegistry()` and `SourbyAPI.profileTypes()`), `ProfileTypeDefinition`, `ProfileRule`, `ProfileRuleSet`, `ProfileNaming`, `ProfileTypeSource` | [PROFILE-TYPES.md](../docs/awf/multiprofile/PROFILE-TYPES.md) |
| `dev.iyanz.sourbycraft.api.scheduler` | `RegionTask`, `EntityTask`: markers that name the owning chunk or entity of a sync Bukkit scheduler callback for the Aurora Bridge; since Build 55 `AuroraScheduler` (`SourbyAPI.scheduler()`) and its `TaskHandle` | [aurora-plugin-bridge.md](../docs/architecture/aurora-plugin-bridge.md), [developing-with-sourbyapi.md](../docs/guides/developing-with-sourbyapi.md) |

`AuroraWorlds` and `AuroraProfiles` are obtained from Bukkit's `ServicesManager` (since Build 54 also
through `SourbyAPI`, below); both are optional (a server without the feature has no registration, and
`AuroraProfiles.isEnabled()` can be false).

## Getting started

Since Build 54 a plugin reaches every SourbyCraft service through one class,
`dev.iyanz.sourbycraft.api.SourbyAPI`.

**Dependency.** Compile against `sourbyapi` and do not shade it: the server provides it at runtime.

```kotlin
dependencies {
    // From Build 55 every v26.2-r<N> release carries sourbyapi-<N>.zip (Build 54 and earlier attach none) with
    // sourbyapi-<N>.jar, -sources.jar, -javadoc.jar and a README.txt: extract it and put the first jar in your project.
    compileOnly(files("libs/sourbyapi-55.jar"))
    // Alternatively build it from this repository with ./gradlew :sourbyapi:publishToMavenLocal
    // (needs the private toolchain, see "Artifact coordinates") and use
    // compileOnly("dev.iyanz.sourbycraft:sourbyapi:26.2-REL") with mavenLocal().
}
```

**`plugin.yml`.** Declare `folia-supported: true` (and `api-version: '26.2'`) once the plugin does its
world, entity and player work through the region and entity schedulers; such a plugin runs natively. A plugin
without the key is a legacy plugin: it is enabled only through the Aurora Bridge (`aurora.bridge.mode`, off by
default, RESTART_REQUIRED), which routes its scheduler tasks but not its direct world access
([aurora-plugin-bridge.md](../docs/architecture/aurora-plugin-bridge.md)).

**Example.**

```java
public final class IslandPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        if (!SourbyAPI.isSourbyCraft()) return;                       // plain Paper: nothing to do
        getLogger().info("on " + SourbyAPI.build().buildNumber());      // e.g. "on 54"
        AuroraWorlds worlds = SourbyAPI.worlds();                     // throws FeatureUnavailableException if absent
        worlds.createInstance("island_base", "isle_demo")             // deleted again once unloaded
            .thenAccept(world -> getLogger().info("ready: " + world.getName()));
        SourbyAPI.findProfiles().ifPresent(profiles ->                // empty while MultiProfile is off
            getLogger().info("MultiProfile is on"));
    }
}
```

| Accessor | Returns | Unavailable when |
| --- | --- | --- |
| `isSourbyCraft()` | whether the server's brand is `sourbycraft:sourbycraft` | — |
| `build()` / `findBuild()` | `SourbyBuild` (build number and label, codename, API version, Minecraft version, commit), from the jar's `META-INF/sourbycraft-build.properties` | not SourbyCraft |
| `worlds()` / `findWorlds()` | `AuroraWorlds` | the service did not start (an unreadable `sourbycraft_config/aurora-worlds.json` stops it) |
| `profiles()` / `findProfiles()` | `AuroraProfiles`, only while `isEnabled()` | `aurora.awf.multiprofile.enabled` is false (the default) or its start failed |
| `profileTypes()` / `findProfileTypes()` | `ProfileTypeRegistry` ([PROFILE-TYPES.md](../docs/awf/multiprofile/PROFILE-TYPES.md)) | MultiProfile is not running (`aurora.awf.multiprofile.enabled`) |
| `metrics()` / `findMetrics()` | `SourbyMetrics` | the metrics service did not start |
| `scheduler()` / `findScheduler()` (Build 55) | `AuroraScheduler`, the region/entity/global/async scheduler facade | not SourbyCraft, or a server older than Build 55 |

The throwing form raises `FeatureUnavailableException` (unchecked), whose message and `configKey()` name the
setting that enables the feature where one does. On a server that is not SourbyCraft every `find…` method returns
empty. The accessors may be called from any thread; lookups go to Bukkit's thread-safe `ServicesManager` and are
cached until a service is registered or unregistered. A service's own methods keep their own thread rules (for
`AuroraWorlds`: never join its futures on a region, entity or global-tick thread).

The developer guide is [developing-with-sourbyapi.md](../docs/guides/developing-with-sourbyapi.md) (dependency,
`plugin.yml`, worlds, profiles and types, the bank retry contract, the scheduler facade and events), with a compilable
example plugin in [`examples/sourbyapi-example/`](../examples/sourbyapi-example/). Per service see also
[developing-awf.md](../docs/guides/developing-awf.md) and the [MultiProfile API](../docs/awf/multiprofile/API.md).

### API changes in Build 55

Code written and compiled by an agent; not tested by the owner. Binary and source compatible: nothing existing was
removed or changed. Written for this section by the developer-docs agent from the sources; the operations additions
(audit log, switch guard) are described in the [MultiProfile API](../docs/awf/multiprofile/API.md).

- New `SourbyAPI.scheduler()` / `findScheduler()` and `dev.iyanz.sourbycraft.api.scheduler.AuroraScheduler` and
  `TaskHandle`: run on the region of a `Location` or chunk (`runAt*`), on an entity's scheduler (`runFor*`, optional
  `retired` callback), on the global region (`runGlobal*`) or off every region (`runAsync*`), delayed and repeating,
  with a cancellable handle and `isOwnedByCurrentRegion`. A layer over the server's public region-threading schedulers
  ([developing-with-sourbyapi.md](../docs/guides/developing-with-sourbyapi.md#6-scheduler-facade)).
- New asynchronous, non-cancellable events fired after the durable outcome: `api.world.event` (`AwfWorldCreatedEvent`,
  `AwfWorldLoadedEvent`, `AwfWorldUnloadedEvent`, `AwfWorldDeletedEvent`, `AwfWorldSavedEvent`, `AwfInstanceDisposedEvent`,
  base `AwfWorldEvent`) and `api.profile.event` (`ProfilePreSwitchEvent`, `ProfileSwitchedEvent`, `ProfileCreatedEvent`,
  `ProfileDeletedEvent`); see their package descriptions and the guide's *Events* section.
- Distribution: from Build 55 each `v26.2-r<N>` release carries one asset, `sourbyapi-<N>.zip`, holding
  `sourbyapi-<N>.jar`, `-sources.jar`, `-javadoc.jar` (built without external `-link` targets since Build 55, so types
  from Guava, Adventure and the other libraries render unlinked and a link host outage cannot fail a push) and a `README.txt` (a hotfix `v26.2-r<N>.<M>` names it
  `sourbyapi-<N>.<M>.zip`). A zip, not loose jars: the auto-updater of Builds 51-54 takes the first `.jar` asset as the
  server jar.
- `SourbyUpdater` (Build 55) picks the server jar by name: never an asset starting with `sourbyapi-`, a `SourbyCraft-`
  name first; a release with a single `.jar` asset selects what it always did.

### API changes in Build 54

Code written; compiled by an agent; not tested by the owner. Binary and source compatible: no existing signature
changed or was removed; the additions to `AuroraWorlds` are `default` methods.

- New in `dev.iyanz.sourbycraft.api`: `SourbyAPI`, `SourbyBuild`, `FeatureUnavailableException`. `SourbyAPI`
  also has `bindServiceEvents(Plugin)`, marked `@ApiStatus.Internal`: the server calls it once at startup.
- New in `api.world`: `AwfWorldSummary`, `AwfWorldInfo`, `AwfBackendKind`, `AwfPersistenceMode`. New `AuroraWorlds`
  methods: `listWorlds()` (no storage access), `info(name)` (asynchronous: backend, store format, size on the FILE
  backend, uncommitted chunks, last commit time), `listTemplates()` (asynchronous `templates()`),
  `isAwfWorld(World)`, `createInstance(template, name)` (a template instance deleted once it is unloaded; see its
  javadoc). On an implementation compiled before Build 54, `listWorlds`, `info` and `createInstance` fail with
  `UnsupportedOperationException`.
- A `createInstance` world is stored in `sourbycraft_config/aurora-worlds.json` with `"deleteOnUnload": true`; every
  other entry is written exactly as before. A build before 54 ignores the field and keeps such a world as an
  ordinary instance.
- `api.profile` gains the profile type registry: `ProfileTypeRegistry`, `ProfileTypeDefinition`, `ProfileRule`,
  `ProfileRuleSet`, `ProfileNaming`, `ProfileTypeSource`; see
  [PROFILE-TYPES.md](../docs/awf/multiprofile/PROFILE-TYPES.md) and the
  [MultiProfile API](../docs/awf/multiprofile/API.md).
- `api.profile` gains the co-op bank, collections and salvage (all `default` methods on `AuroraProfiles`, failing
  with `UnsupportedOperationException` on an older implementation; they need `coop.enabled: true`):
  `bank(viewer, profile)`, `deposit`, `withdraw`, `depositPersonal`, `withdrawPersonal`,
  `transfer(actor, profile, ProfileBankAccount from, ProfileBankAccount to, amount)`, `applyInterest`,
  `collections(viewer, profile)`, `contribute(actor, profile, collection, amount)`, `salvage(viewer, profile, cursor)`
  and `claimSalvage(claimant, salvageId)`. New records `ProfileBankInfo`, `ProfileCurrency`, `ProfileCollectionInfo`
  (with `Contribution`), `ProfileSalvageInfo`, `SalvageDeliveryResult`; enum `ProfileBankAccount` (`SHARED`,
  `PERSONAL`; the purse is not an account, owner decision OD-4); asynchronous events `ProfileBankTransactionEvent`
  and `ProfileSalvageDeliveredEvent` (fired after the durable commit). Amounts are `long` minor units. The
  type-registry action ids gain `collections.contribute`. `ProfileErrorCode` appends `ACTION_FORBIDDEN_BY_TYPE`,
  `TYPE_UNKNOWN` and `PURSE_TRANSFER_UNSUPPORTED`. See
  [MultiProfile API](../docs/awf/multiprofile/API.md#co-op-bank-collections-and-salvage-build-54).

### API changes in Build 53

Code written; compiled by an agent; not tested by the owner. Binary and source compatible: no existing signature
changed or was removed. `ProfileErrorCode` only gains constants at the end (`ADAPTER_REQUIRED_FAILED`,
`ADAPTER_MISSING`, `HEAD_UNREADABLE`); persist the name, not the ordinal. New types in `api.profile`: the enums
`ProfileDataCriticality` (`REQUIRED`, `BEST_EFFORT`) and `ProfileDataScope` (`ACCOUNT_WIDE`, `PROFILE_SCOPED`,
`UNSUPPORTED`) and the record `ProfileAdapterDescriptor(id, version, scope, criticality, maxBytes)`.
`ProfileStateAdapter` gains `default ProfileAdapterDescriptor descriptor()`, which returns `BEST_EFFORT` /
`PROFILE_SCOPED` with the adapter's own id, version and the 2 MiB cap, so an adapter compiled against Build 52
links and keeps its behaviour (one change: when a BEST_EFFORT capture fails, the bytes already stored for the
adapter at that version are now preserved instead of dropped). `REQUIRED` means the capture is durable before the
target profile is active, the apply is verified, a failure refuses the switch or is journaled for recovery, a
missing adapter refuses it, and a skip is never success. No new method on `AuroraProfiles`. Behaviour of existing
methods: `switchProfile` accepts a co-op the player is a member of; `leave` and `voteKick` accept a member whose
active profile is the co-op (`MEMBER_ONLINE` only when they cannot be switched out here, or their session is live on
another node); `COOP_SWITCH_UNAVAILABLE` is returned only while authoritative login does not apply; `saveProfile` on a
format-2 store saves every member playing the profile on the node. `ProfileStateAdapter.migrate(int, byte[])` is a new
`default` method (binary compatible; default: unsupported) for data stored under an older adapter version.
`CoopMemberLeaveEvent.Reason` is unchanged. Configuration: `aurora.awf.multiprofile.login.authoritative`
(`auto` | `true` | `false`, default `auto` = authoritative when co-op is enabled), `shutdown.capture-deadline-ms`,
`adapters.required` (all RESTART_REQUIRED). Not in Build 53: salvage delivery, shared bank and the purse (Build 54); audit log, switch
guard, type rules (Build 55); owner transfer (unscheduled). See
[MultiProfile API](../docs/awf/multiprofile/API.md#adapter-contract-build-53).

### API changes in Build 52

Code written and compiled by an agent; not tested by the owner. Binary compatible: no existing signature
changed; `ProfileDetails` is unchanged; `ProfileErrorCode` only gains constants at the end
(`COOP_DISABLED` ... `COOP_SWITCH_UNAVAILABLE`, 18 in all). New types in `api.profile`: `CoopRole`,
`CoopMemberInfo`, `CoopInviteInfo`, `CoopInviteState`, `CoopKickVoteInfo`, `CoopKickVoteState`,
`CoopDetails`, `CoopPage`, and the asynchronous Bukkit events `CoopMemberJoinEvent`,
`CoopMemberLeaveEvent`, `CoopInviteEvent`. New `default` methods on `AuroraProfiles` (they fail with
`UnsupportedOperationException` on an older implementation): `getCoopDetails`, `convertToCoop`,
`invite`, `acceptInvite`, `declineInvite`, `cancelInvite`, `listInvitesFor`, `listInvites`, `leave`,
`startKickVote`, `voteKick`, `getOpenKickVote`, `listMembers`, `setCoopLabel`. All are refused with
`COOP_DISABLED` unless `aurora.awf.multiprofile.coop.enabled` is true (default false). Not in Build 52 (the
first two arrived in Build 53, see above): a member switching into a co-op profile they do not own, removing a
member whose active profile is the co-op profile, owner transfer, salvage delivery (Build 54). See
[MultiProfile API](../docs/awf/multiprofile/API.md#co-op-membership-build-52).

### API changes in Build 51

Code written and compiled by an agent; not tested by the owner. Binary compatible: `ProfileInfo` is
unchanged, `ProfileRequest` keeps its three-argument constructor and `of(accountId, type)` and gains
the optional components `profileType` and `name`, and `ProfileErrorCode` only gains constants at the
end (`NAME_TAKEN`, `NAME_INVALID`, `TYPE_NOT_ALLOWED`, `STORE_FORMAT_REFUSED`, `MIGRATION_REQUIRED`).
New: the enum `ProfileType` (`CLASSIC`, `IRONMAN`, `STRANDED`, `BINGO`), the record `ProfileDetails`
(`ProfileInfo` plus the immutable name and policy type) and the `default` methods
`AuroraProfiles.getProfileDetails` / `listProfileDetails` (fail with `UnsupportedOperationException` on an
older implementation). The policy type is stored and returned; no gameplay rule is enforced for it in
this build. Co-op membership is Build 52 (next section). See
[MultiProfile API](../docs/awf/multiprofile/API.md#names-and-policy-types-build-51).

### API changes in PR #62 (Build 50 candidate)

Not in any published build: the `v26.2-r50` prerelease tag predates PR #61 and PR #62, and how
this work is published is an open owner decision. Code written and statically reviewed; not built;
not tested by the owner; no API test was added.

- `AuroraProfiles.setProfileLocked(UUID profileId, boolean locked)` puts an inactive profile on
  administrative hold (still listed, switching to it is refused) or releases it; the active profile
  cannot be locked, and the call is refused during an account transition or while a transition
  journal is pending. `AuroraProfiles.setProfileSlotLimit(UUID accountId, int slots)` sets how many
  profiles an account may own, from 1 to the server's `max-slots`; profiles above a lowered limit
  are kept. Both are `default` methods whose default implementation completes exceptionally with
  `UnsupportedOperationException`, so a service implementation built against the older interface
  still links; SourbyCraft's own service implements them.
- `AuroraProfiles.registerProfileStateAdapter` documents its refusals: `IllegalArgumentException`
  for a malformed id, an `id:version` key over 64 characters, a version below 1, an id another
  enabled plugin holds, or a 33rd adapter; `ProfileOperationException(INVALID_REQUEST)` for a plugin
  that is not enabled. `ProfileStateAdapter` documents the limits (2 MiB per adapter, 6 MiB for all
  adapters of one snapshot, 32 adapters) and which stored data is carried forward.
- `ProfileErrorCode.STATE_TOO_LARGE`: a player state over the 8 MiB snapshot limit; nothing is
  stored. It was added before `INTERNAL_ERROR`, so that constant's ordinal changed; persist the
  name, not the ordinal.
- `WorldProperties.Spawn` rejects non-finite or out-of-world values with
  `IllegalArgumentException`: `x` and `z` from -29999984 to 29999984, `y` from -2032 up to but not
  including 2032, a finite yaw. A spawn stored before this change outside those limits is dropped
  with a WARN when `aurora-worlds.json` or a template's `template.json` is read; importing an
  `.awf` file whose recorded spawn is outside them still fails the whole import (open).
- `AuroraWorlds` javadoc: `importWorld` also applies the file's properties and merges recorded
  plugin data into the world's persistent data container before its future completes, and fails
  before creating anything when either is malformed; `unload(name, true)` first saves a world
  created through this API durably and fails, leaving it loaded, when that save fails; while the
  server is stopping that save is skipped and the engine normally answers `SERVER_STOPPING`;
  `UnloadResult.FAILED` can also mean the world was unloaded but its final AWF commit was not
  confirmed (`isLoaded` tells the two apart).

## Breaking change in Build 48: the Canvas API package was removed

Owner decision 2026-10-21: the region-threading plugin API that SourbyCraft inherited from Canvas
moved from `io.canvasmc.canvas` to `dev.iyanz.sourbycraft.api`, and the old package was **deleted**.
There is no deprecated shim and no bridge. Class names, members, event semantics, handler lists and
enum constants are unchanged; only the package moved.

What this means for a plugin:

- **Compiled against the old package:** it fails to link on Build 48 (`NoClassDefFoundError` /
  `NoSuchMethodError` as soon as a class touching `io.canvasmc` loads, which for a listener class is
  usually at enable). It has to be rebuilt.
- **Rebuilding:** change the imports per the table below and compile against `sourbyapi` Build 48.
  Nothing else changes. The Bukkit entry points keep their names (`World#getRegionizer()`,
  `Server#unloadWorldAsync(..., Consumer<WorldUnloadResult>)`, `Server#createRegionizedData`,
  `Server#getLocalRegionizedData`, `Server#getRegionThreadingTickRateManager()`); their signatures
  now name the new types.
- **Not affected:** the `canvas-supported` / `folia-supported` keys in `plugin.yml` /
  `paper-plugin.yml` are a descriptor contract, not a Java package, and are still read
  (`PluginDescriptionFile`). Plugins using only Bukkit/Paper/Folia API are unaffected.

| Old class (removed) | New class |
| --- | --- |
| `io.canvasmc.canvas.Unsupported` | `dev.iyanz.sourbycraft.api.Unsupported` |
| `io.canvasmc.canvas.WorldUnloadResult` | `dev.iyanz.sourbycraft.api.world.WorldUnloadResult` |
| `io.canvasmc.canvas.event.EntityPortalAsyncEvent` | `dev.iyanz.sourbycraft.api.event.EntityPortalAsyncEvent` |
| `io.canvasmc.canvas.event.EntityPostPortalAsyncEvent` | `dev.iyanz.sourbycraft.api.event.EntityPostPortalAsyncEvent` |
| `io.canvasmc.canvas.event.EntityTeleportAsyncEvent` (and nested `TeleportType`) | `dev.iyanz.sourbycraft.api.event.EntityTeleportAsyncEvent` (and nested `TeleportType`) |
| `io.canvasmc.canvas.event.EntityPostTeleportAsyncEvent` | `dev.iyanz.sourbycraft.api.event.EntityPostTeleportAsyncEvent` |
| `io.canvasmc.canvas.event.PlayerRespawnAsyncEvent` | `dev.iyanz.sourbycraft.api.event.PlayerRespawnAsyncEvent` |
| `io.canvasmc.canvas.event.PlayerPostRespawnAsyncEvent` | `dev.iyanz.sourbycraft.api.event.PlayerPostRespawnAsyncEvent` |
| `io.canvasmc.canvas.event.PlayerSaveEvent` | `dev.iyanz.sourbycraft.api.event.PlayerSaveEvent` |
| `io.canvasmc.canvas.event.PlayerViewEndCreditsEvent` | `dev.iyanz.sourbycraft.api.event.PlayerViewEndCreditsEvent` |
| `io.canvasmc.canvas.event.world.WorldUnloadAsyncEvent` | `dev.iyanz.sourbycraft.api.event.world.WorldUnloadAsyncEvent` |
| `io.canvasmc.canvas.region.RegionThreadingTickManager` (and nested `RegionHandle`) | `dev.iyanz.sourbycraft.api.region.RegionThreadingTickManager` (and nested `RegionHandle`) |
| `io.canvasmc.canvas.region.RegionTickData` (and nested `Frame`, `IRegionizedData`, `IRegionizedData.IRegionizedCallback`) | `dev.iyanz.sourbycraft.api.region.RegionTickData` (same nested types) |
| `io.canvasmc.canvas.region.WorldRegionizer` (and nested `ChunkRegion`, `ChunkRegion.State`) | `dev.iyanz.sourbycraft.api.region.WorldRegionizer` (same nested types) |
| `io.canvasmc.canvas.simd.SIMDChecker` | `dev.iyanz.sourbycraft.api.simd.SIMDChecker` |
| `io.canvasmc.canvas.simd.SIMDDetection` | `dev.iyanz.sourbycraft.api.simd.SIMDDetection` |
| `io.canvasmc.canvas.simd.VectorMapPalette` | `dev.iyanz.sourbycraft.api.simd.VectorMapPalette` |

A mechanical rewrite is enough: `sed -e 's/io\.canvasmc\.canvas\.WorldUnloadResult/dev.iyanz.sourbycraft.api.world.WorldUnloadResult/g' -e 's/io\.canvasmc\.canvas/dev.iyanz.sourbycraft.api/g'`
over the plugin's sources (`WorldUnloadResult` is the one class that also gained a sub-package).

Why no shim: the owner chose a clean break over a deprecation window. For the record, a shim would
not have been free either — `WorldRegionizer.ChunkRegion#getState()` and `RegionTickData.Frame` return
or take nested enums, and a Java enum cannot extend another, so binary compatibility for those members
would have needed differently named methods or wrapper objects.

How the server is wired: the engine baseline (inherited Canvas/Folia patches) may not name a
SourbyCraft package (`scripts/baseline_policy.py`), so it names these types by simple name and three
SourbyCraft feature patches add the imports (Minecraft, Paper server, and this module's first API
feature patch, `paper-patches/features/0001-…`). `scripts/test_independence_policy.py` fails on any
`io.canvasmc` string in this module's sources, the server sources or any patch tree.

## API feature patches

SourbyCraft's edits to the upstream Paper API sources, applied after the engine baseline in
`paper-patches/files/`:

| Patch | Change |
| --- | --- |
| `0001-SourbyCraft-plugin-API-types-from-dev.iyanz.sourbycraft.api` | Adds the `dev.iyanz.sourbycraft.api` imports the baseline names by simple name |
| `0002-SourbyCraft-BRAND_SOURBYCRAFT_ID-replaces-BRAND_CANVAS_ID` | `ServerBuildInfo.BRAND_SOURBYCRAFT_ID`; see the next section |
| `0003-SourbyCraft-ServerTickManager-is-supported` | Drops `@Unsupported` from `org.bukkit.ServerTickManager` and documents how the server-wide tick state applies to regions: it takes effect at the next global tick, each region observes it at the start of its own next tick, stepping and sprinting count global ticks, and a region's own pause/sprint stays separate |

## Breaking change in Build 48: `ServerBuildInfo.BRAND_CANVAS_ID` was removed

Same owner decision (2026-10-21): `io.papermc.paper.ServerBuildInfo.BRAND_CANVAS_ID`
(`Key.key("canvasmc", "canvas")`) is **deleted**, with no compatibility constant. Use
`ServerBuildInfo.BRAND_SOURBYCRAFT_ID` (`Key.key("sourbycraft", "sourbycraft")`), which is the
`Brand-Id` a SourbyCraft server jar declares; `ServerBuildInfo.buildInfo().brandId()` returns it,
and `isBrandCompatible` accepts it, `BRAND_PAPER_ID` and `BRAND_FOLIA_ID` (unchanged). A plugin
compiled against the old constant fails with `NoSuchFieldError` when that code runs; a plugin that
compared `brandId()` with the Canvas key already got `false` on SourbyCraft. Source: this module's
second API feature patch, `paper-patches/features/0002-…`; pinned by `BrandIdTest`.

## Artifact coordinates

```
group:    dev.iyanz.sourbycraft
artifact: sourbyapi
version:  <releaseVersion>-<CHANNEL>   e.g. 26.2-REL with -PsourbyChannel=REL; 26.2-DEV on branch 26.2
```

`version` is computed at configuration time from the current git branch unless
`-PsourbyChannel=REL` is passed (used by CI for official `26.2` releases; see the root
`settings.gradle.kts` and `.github/workflows/build.yml`), matching the same `REL`/`DEV`/`EXP` channel suffix reported by `/ver` and the
auto-updater. No public Maven repository publishes this module: JitPack cannot build it because
settings evaluation requires the private SourbyPatcher toolchain. External consumers build it with
`./gradlew :sourbyapi:publishToMavenLocal` on a machine that has the pinned private toolchain
(see `docs/development/PRIVATE-TOOLCHAIN.md`).

The runtime `Bukkit.getBukkitVersion()` compatibility value is separate from this artifact
coordinate: `apiVersioning.json` reports `<apiVersion>-R0.1-SNAPSHOT` (currently
`26.2-R0.1-SNAPSHOT`). Plugins that parse Bukkit's API version need that format; the SourbyCraft
build channel remains in the artifact version and `/ver`.

## Consuming it

**From inside this repo** (e.g. `test-plugin`), reference the Gradle project directly:

```kotlin
dependencies {
    implementation(project(":sourbyapi"))
}
```

**From an external plugin project**, depend on the Maven Local artifact:

```kotlin
dependencies {
    compileOnly("dev.iyanz.sourbycraft:sourbyapi:26.2-REL")
}
```

Look up the read-only metrics service through Bukkit's existing `ServicesManager`:

```java
ServicesManager services = Bukkit.getServicesManager();
SourbyMetrics metrics = services.load(SourbyMetrics.class);
```

`SourbyMetrics` is the intentional process-wide read-only accessor. `snapshot().window(...)`
aggregates spatial region generations, while `snapshot().globalWindow(...)` returns the separately
measured global scheduler and does not affect active-region counts. Snapshots and their window values
are immutable and may be read safely from any thread.

### `api-version` in your plugin descriptor

The Bukkit API version a plugin declares in `paper-plugin.yml` / `plugin.yml` comes from the
`apiVersion` Gradle property in the root `gradle.properties`, currently:

```
apiVersion=26.2
```

so a plugin's descriptor should declare:

```yaml
api-version: '26.2'
```

(`test-plugin/src/main/resources/paper-plugin.yml` does exactly this, substituting
`${api_version}` at build time from the same property.)

## Directory contents

- `build.gradle.kts` / `build.gradle.kts.patch` — the paperweight-materialized build script + the
  patch that produced it from Paper's `paper-api/build.gradle.kts`.
- `paper-patches/` — patches applied to the upstream `paper-api` sources before they land in the
  sibling `paper-api/` project that this module's source set reads from: `files/` (engine
  baseline) and `features/` (*API feature patches*).
- `src/main/java/dev/iyanz/sourbycraft/api/{event,region,simd}`, `api/Unsupported.java`,
  `api/world/WorldUnloadResult.java` — region-threading API additions (formerly Canvas's API; see
  *Breaking change in Build 48*).
- `src/main/java/dev/iyanz/sourbycraft/api/metrics/` — SourbyCraft's public read-only metrics API.
- `src/main/java/dev/iyanz/sourbycraft/api/world/` — `AuroraWorlds` and its types.
- `src/main/java/dev/iyanz/sourbycraft/api/profile/` — `AuroraProfiles` (AWF MultiProfile) and its types, including
  the Build 54 profile type registry.
- `src/main/java/dev/iyanz/sourbycraft/api/SourbyAPI.java`, `SourbyBuild.java`, `FeatureUnavailableException.java` —
  the Build 54 entry point.
- `src/main/java/dev/iyanz/sourbycraft/api/scheduler/` — `RegionTask` and `EntityTask`; since Build 55 `AuroraScheduler` and `TaskHandle`.
- `src/test/` — API contract tests.
