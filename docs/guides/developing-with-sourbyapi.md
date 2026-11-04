# Developing plugins with SourbyAPI

Build 55. Code written and compiled by an agent; **not tested** (owner rule: the owner runs every test). This guide
describes what the code does; where a behaviour is configured or untested it says so. The API reference is the javadoc
(`sourbyapi-<build>-javadoc.jar` inside the `sourbyapi-<build>.zip` asset of every release) and, for MultiProfile, the [MultiProfile API](../awf/multiprofile/API.md).

[`sourbyapi`](../../sourbyapi/README.md) is the one artifact a plugin compiles against: the Paper API surface under the
SourbyCraft group id plus the `dev.iyanz.sourbycraft.api.*` packages. A working plugin that uses every service below is in
[`examples/sourbyapi-example/`](../../examples/sourbyapi-example/).

## 1. Get the dependency

Compile against it and **do not shade it**: the server provides every class at runtime.

From Build 55 each GitHub release `v26.2-r<N>` carries one extra asset next to the server jar, `sourbyapi-<N>.zip`
(for a hotfix `v26.2-r<N>.<M>`, `sourbyapi-<N>.<M>.zip`), holding:

| Member | Contents |
|---|---|
| `sourbyapi-<N>.jar` | the compiled API (Paper API surface plus `dev.iyanz.sourbycraft.api.*`) |
| `sourbyapi-<N>-sources.jar` | its sources |
| `sourbyapi-<N>-javadoc.jar` | its javadoc |
| `README.txt` | the `compileOnly` snippet below |

Download the zip from the release page, extract it, and put the binary jar in your project:

```kotlin
dependencies {
    compileOnly(files("libs/sourbyapi-55.jar"))
}
```

Build 54 and earlier releases have no such asset. It is a zip, not three loose jars, on purpose: the auto-updater of
Builds 51-54 takes the first release asset ending in `.jar` as the server jar, so a release attaches no `.jar` besides the
server jar (from Build 55 the updater also skips any `sourbyapi-` asset and prefers a `SourbyCraft-` name). The jars are
built from the release commit by the CI `build` job (`:sourbyapi:jar :sourbyapi:sourcesJar :sourbyapi:javadocJar`); the
zip is attached by the `release` job and attested like the server jar, but the independent rebuild job compares the server
jar only, so the zip's bytes are not claimed to be reproducible.

**Maven local alternative.** From a checkout of this repository, with the private toolchain set up
(`docs/development/PRIVATE-TOOLCHAIN.md`) and the patches applied:

```
./gradlew :sourbyapi:publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
dependencies { compileOnly("dev.iyanz.sourbycraft:sourbyapi:26.2-REL") }
```

The version is the channel version of the checkout (`26.2-REL`, `26.2-EXP`, or `<releaseVersion>-DEV`).

## 2. `plugin.yml`

```yaml
name: MyPlugin
main: com.example.MyPlugin
version: 1.0.0
api-version: '26.2'
folia-supported: true
```

- `folia-supported: true` says that every world, entity and player access in the plugin goes through the region, entity
  or global schedulers. The server then runs the plugin natively, with the thread-ownership rules of the region-threaded
  engine. SourbyCraft has no main thread: code that touches another region's chunk, block or entity from the wrong
  thread is a bug (what happens depends on `aurora.bridge.thread-checks` and the guard severity, see
  [aurora-plugin-bridge.md](../architecture/aurora-plugin-bridge.md); do not rely on it being tolerated).
- A plugin **without** the key is a legacy plugin. It is enabled only through the **Aurora Bridge**
  (`aurora.bridge.mode`, off by default, RESTART_REQUIRED), which routes its Bukkit scheduler tasks to the owning region
  but not its direct world access. See [aurora-plugin-bridge.md](../architecture/aurora-plugin-bridge.md) and
  [testing-legacy-plugins.md](testing-legacy-plugins.md). New code should declare the key and use `SourbyAPI.scheduler()`.
- The example plugin's `plugin.yml` is
  [`examples/sourbyapi-example/src/main/resources/plugin.yml`](../../examples/sourbyapi-example/src/main/resources/plugin.yml).

## 3. The entry point

`dev.iyanz.sourbycraft.api.SourbyAPI` reaches every service. Each accessor comes as a throwing form and a `find…` form.

| Accessor | Returns | Unavailable when |
|---|---|---|
| `isSourbyCraft()` | whether the server's brand is `sourbycraft:sourbycraft` | n/a |
| `build()` / `findBuild()` | `SourbyBuild` (build number, label, codename, API version, Minecraft version, commit) | not SourbyCraft |
| `worlds()` / `findWorlds()` | `AuroraWorlds` | the service did not start |
| `profiles()` / `findProfiles()` | `AuroraProfiles` | `aurora.awf.multiprofile.enabled` is false (the default) |
| `profileTypes()` / `findProfileTypes()` | `ProfileTypeRegistry` | MultiProfile is not running |
| `metrics()` / `findMetrics()` | `SourbyMetrics` | the metrics service did not start |
| `scheduler()` / `findScheduler()` (Build 55) | `AuroraScheduler` | not SourbyCraft, or a server older than Build 55 |

The throwing form raises `FeatureUnavailableException` (unchecked); its message names the feature and, where one exists,
the setting that enables it (`configKey()`). The `find…` form returns an `Optional`, empty on a server that is not
SourbyCraft. The accessors may be called from any thread. Typical use:

```java
@Override
public void onEnable() {
    if (!SourbyAPI.isSourbyCraft()) { getLogger().warning("not SourbyCraft"); getServer().getPluginManager().disablePlugin(this); return; }
    if (!SourbyAPI.build().atLeast(55)) { /* Build 55 added scheduler(), the AWF events and the profile events */ }

    AuroraScheduler scheduler = SourbyAPI.scheduler();                 // always present from Build 55
    SourbyAPI.findProfiles().ifPresent(p -> getLogger().info("MultiProfile on: " + p.isEnabled()));
    try {
        AuroraWorlds worlds = SourbyAPI.worlds();
    } catch (FeatureUnavailableException e) {
        getLogger().warning(e.getMessage());                            // names the cause
    }
}
```

Referencing `SourbyAPI` on a server that does not ship the class (plain Paper, with your plugin not shading it) fails with
`NoClassDefFoundError` before `isSourbyCraft()` can answer. A plugin that must also run on Paper keeps every SourbyAPI
reference in classes it only loads after a class-presence check of its own.

### Version checks

`SourbyAPI.build()` returns `SourbyBuild`: `buildNumber()` is a string (`55`, or `48.3` for a hotfix), `build()` its
whole-number part (`-1` when unparseable) and `atLeast(55)` the check you want. A hotfix `55.1` is at least 55. A feature
added in Build N is not there before it; the methods added later to an interface are `default` methods that fail with
`UnsupportedOperationException` on an older implementation, so a check by build number is the reliable guard.

## 4. Worlds and instances

`AuroraWorlds` manages Aurora World Fabric worlds: persistent worlds, frozen templates and copy-on-write clones. The full
reference is [developing-awf.md](developing-awf.md). From Build 54:

```java
AuroraWorlds worlds = SourbyAPI.worlds();
for (AwfWorldSummary w : worlds.listWorlds())                     // any thread; reads no storage
    getLogger().info(w.name() + " loaded=" + w.loaded() + " instance=" + w.deleteOnUnload());

worlds.createInstance("island_base", "isle_" + id)                // copy-on-write copy of a template
      .thenAccept(world -> /* a storage thread, not a region: schedule onward */ {});
```

- `createInstance(template, name)` makes a disposable instance, deleted (its folder, its own AWF stores and its entry;
  never the template) once it is unloaded. It is deleted after the next startup if the server stopped while it was loaded.
  It cannot be loaded again once unloaded. See the method's javadoc for the exact unload rules.
- Futures complete on a storage thread. **Never `join()`/`get()` an AWF future on a region, entity or global-tick thread.**
  Hand the result onward with the scheduler (section 7).
- A server that is not Build 54 or later fails `listWorlds`, `info` and `createInstance` with `UnsupportedOperationException`.

## 5. Profiles and the profile type registry

AWF MultiProfile (durable per-player profiles) is **off by default** (`aurora.awf.multiprofile.enabled: false`, RESTART_REQUIRED),
and co-op, the bank and salvage need `aurora.awf.multiprofile.coop.enabled: true` as well. Always ask first:

```java
Optional<AuroraProfiles> profiles = SourbyAPI.findProfiles();      // empty while MultiProfile is off
if (profiles.isEmpty() || !profiles.get().isEnabled()) return;

profiles.get().getActiveProfile(playerId)
    .thenCompose(active -> active.isPresent() ? profiles.get().getProfileDetails(active.get().id())
                                              : CompletableFuture.completedFuture(Optional.empty()))
    .thenAccept(details -> details.ifPresent(d -> log(d.name() + " type " + d.typeId())));
```

`ProfileDetails.typeId()` is the effective type: a built-in (`classic`, `ironman`, `stranded`, `bingo`) or a registered id.
Every call returns a `CompletableFuture` that completes on a storage thread and fails with a `ProfileOperationException`
whose `code()` is a `ProfileErrorCode`; persist the code's name, not its ordinal. The complete method list, error codes and
the persistence rules are in the [MultiProfile API](../awf/multiprofile/API.md).

### Profile types

Every profile has a type id fixed at creation; the `ProfileTypeRegistry` (`SourbyAPI.profileTypes()`, also a Bukkit service
and `AuroraProfiles.typeRegistry()`) maps it to a definition with action rules. The server enforces the engine-owned action
ids itself (`bank.deposit`, `bank.withdraw`, `bank.personal`, `bank.transfer`, `collections.contribute`, `coop.convert`,
`coop.invite`, `coop.accept`, `salvage.claim`: refused with `ACTION_FORBIDDEN_BY_TYPE`, nothing written); any other id is
yours, and you check it with `allows`. The table of which engine method checks which id, the lifecycle (a deny is never
silently lifted when your plugin is absent) and the unknown-type policy are in [PROFILE-TYPES.md](../awf/multiprofile/PROFILE-TYPES.md).

An "ironman" type that denies the bank (the owner's example; the built-in `ironman` denies only `trade`, `auction` and
`bazaar`, so this replaces it, it does not merge):

```java
SourbyAPI.profileTypes().register(this, ProfileTypeDefinition.builder("ironman")
    .displayName("Ironman")
    .description("No trading, auction house, bazaar or bank.")
    .deny("trade").deny("auction").deny("bazaar")
    .deny(ProfileTypeRegistry.BANK_DEPOSIT)
    .deny(ProfileTypeRegistry.BANK_WITHDRAW)
    .deny("island-bank")                       // your own id: the server never checks it
    .build());

// Your own action, before opening an island bank:
boolean ok = SourbyAPI.profileTypes().allows(details.typeId(), "island-bank");   // no I/O
profiles.allows(profileId, "island-bank").thenAccept(allowed -> { /* async, reads the profile */ });
```

- `register` takes effect immediately and writes the definition on the registry's own thread, never the caller's. A
  registration can be refused (an id owned by another plugin: `INVALID_REQUEST`; a built-in id while
  `types.registry.allow-override-builtins` is false: `TYPE_NOT_ALLOWED`), so do not swallow the exception.
- The registry exists only while MultiProfile runs. If your plugin enables first, register when the Bukkit
  `ServiceRegisterEvent` for `ProfileTypeRegistry` fires (the example does).
- A profile whose type has no definition is answered by `types.unknown-type-policy` (default `deny`).

A consumer that keeps its types in a file maps each entry to one `register` call. The server never reads such a file; this
is the layout the SSB-AuroraProfiles addon uses and is shown so operators can see how rules map to action ids:

```yaml
# plugins/MyPlugin/types.yml  (read by MyPlugin, not by the server)
types:
  classic:
    display-name: "Classic"
  ironman:
    display-name: "Ironman"
    description: "No trading, auction house, bazaar or bank."
    rules:
      trade: deny
      auction: deny
      bazaar: deny
      bank.deposit: deny
      bank.withdraw: deny
      island-bank: deny
```

### Bank, collections, salvage and the retry contract

These are default methods on `AuroraProfiles` (`bank`, `deposit`, `withdraw`, `depositPersonal`, `withdrawPersonal`, `transfer`,
`applyInterest`, `collections`, `contribute`, `salvage`, `claimSalvage`, `bankReceipt`). They need co-op enabled, a co-op
profile and a format-2 store, and the actor must be a member whose active profile is the co-op. Amounts are `long` minor
units. **The server moves no purse money:** the plugin changes the purse itself and then calls `deposit` or `withdraw`
(owner decision OD-4). The plugin must authorise player requests itself.

Every ledger method has an overload that takes your own **operation id**: any UUID you have never used before, kept until
the outcome is known.

```java
UUID op = UUID.randomUUID();                       // store it with the pending purse debit
profiles.deposit(actor, coopProfile, amount, op)
    .whenComplete((bank, failure) -> { /* see below */ });
```

- The server looks the id up **first** (the permanent receipt on the profile). A known id with the same request is answered
  from the receipt: nothing is posted twice and no `ProfileBankTransactionEvent` fires again, even if the balance, the cap or
  the interest interval changed since. A known id with a different request fails with `INVALID_REQUEST`.
- A failure with `UNKNOWN_OUTCOME` (and `RECOVERY_REQUIRED` for a transfer awaiting recovery) means the operation may or may
  not have committed. **Do not repeat it with a new id and do not refund the purse yet.** Read `bankReceipt(null, profile,
  op)` (its `committed()` says what was decided; empty means still undecided or aborted, so read `bank()` as well) or
  retry the same id later.
- Refund a purse debit only after `bankReceipt` shows no committed operation for the id. The refusal of a first attempt
  (for example `ACTION_FORBIDDEN_BY_TYPE`) writes nothing, so there is nothing to find and the refund is right.

[`BankRetry`](../../examples/sourbyapi-example/src/main/java/dev/iyanz/sourbycraft/example/BankRetry.java) is this pattern as
code. `claimSalvage(claimant, salvageId)` delivers a salvage record exactly once into the claimant's committed state; the
claimant must be online here with the co-op active, and what does not fit stays in the record. Co-op membership (invite,
accept, leave, kick vote) is in the [MultiProfile API](../awf/multiprofile/API.md) and
[COOP-DESIGN.md](../awf/multiprofile/COOP-DESIGN.md).

## 6. Scheduler facade

`SourbyAPI.scheduler()` returns `AuroraScheduler`, a thin layer over the server's public region-threading schedulers
(`Bukkit.getRegionScheduler()`, `Entity.getScheduler()`, the global region scheduler and the async scheduler). It adds no
queue or thread of its own, and every method may be called from any thread. Every call returns a `TaskHandle`
(`cancel()`, `isCancelled()`, `owner()`).

| Run on | Methods | Use for |
|---|---|---|
| the region owning a `Location` or chunk | `runAt`, `runAtLater`, `runAtTimer` | blocks and state tied to a place |
| an `Entity`, wherever it is | `runFor`, `runForLater`, `runForTimer` (optional `retired` callback) | players and entities; follows them across regions and worlds |
| the global region | `runGlobal`, `runGlobalLater`, `runGlobalTimer` | world-independent state; never chunks, blocks or entities |
| no region | `runAsync`, `runAsyncLater`, `runAsyncTimer` (wall-clock `Duration`) | blocking I/O; never world state |

`isOwnedByCurrentRegion(Location|Entity)` and `isGlobalTickThread()` say whether the calling thread may touch the thing now.

```java
AuroraScheduler scheduler = SourbyAPI.scheduler();

scheduler.runAt(plugin, block.getLocation(), () -> block.setType(Material.GOLD_BLOCK));

scheduler.runForTimer(plugin, player, task -> {                    // follows the player; stops if the player is removed
    player.sendMessage("tick");
    if (done()) task.cancel();                                     // a repeating task receives its own handle
}, 20L, 20L, () -> log("player left"));                            // retired callback

scheduler.runAsync(plugin, () -> {
    String rank = database.loadRank(playerId);                     // blocking work, off every region
    scheduler.runFor(plugin, player, () -> player.sendMessage("Rank: " + rank));   // back to the player's region
});
```

- Delays and periods are in ticks of the region (or global region) that runs the task; regions tick independently. A
  delay of `0` is the next tick; a negative delay or a period below 1 throws `IllegalArgumentException`. A task never runs on
  the calling thread before the method returns.
- The plugin must be enabled (otherwise `IllegalPluginAccessException`); every task of a plugin is cancelled when it is
  disabled. For an entity task whose entity was already removed nothing is scheduled and the handle is cancelled.
- A callback that throws is logged by the underlying scheduler; a repeating task is scheduled again.

## 7. Events

Build 55 adds two families of Bukkit events. Register listeners the usual way (`registerEvents`). All of them are
**asynchronous** (`isAsynchronous()` is true), **not cancellable** and **not transactions**: each is fired *after* the
outcome it reports is final, so a handler reports what happened and cannot change it.

**Thread.** An event is fired from a SourbyCraft thread that is never a region, entity, global-tick or network thread. A
handler must not touch world, block, entity or player state directly; schedule that with `SourbyAPI.scheduler()` (for
example `runFor(plugin, player, ...)`), and return quickly. Reading the event, logging and `Bukkit.getPlayer(uuid)` are fine.

| Package | Event | Fired when |
|---|---|---|
| `api.world.event` | `AwfWorldCreatedEvent` (`getOrigin()`, `getTemplate()`, `isDisposable()`) | a world was created through `AuroraWorlds` and is loaded |
| | `AwfWorldLoadedEvent` (`isCreated()`) | a managed world finished loading |
| | `AwfWorldUnloadedEvent` (`getResult()`) | a managed world was unloaded through `AuroraWorlds.unload` |
| | `AwfWorldDeletedEvent` (`getTemplate()`, `isDisposable()`) | a managed world's folder, stores and entry are gone |
| | `AwfWorldSavedEvent` (`getCommittedAt()`) | a save through `AuroraWorlds` committed durably |
| | `AwfInstanceDisposedEvent` (`getTemplate()`) | a disposable instance is deleted for good, after its `AwfWorldDeletedEvent` |
| `api.profile.event` | `ProfilePreSwitchEvent` | a switch was admitted (type rules, guards and cooldown passed) and is about to change state; informational, the switch does not wait |
| | `ProfileSwitchedEvent` (`getFromProfileId()`, `getProfile()`, `getResult()`) | a switch committed |
| | `ProfileCreatedEvent` (`getProfile()`) | a profile was created and is durable |
| | `ProfileDeletedEvent` (`getProfileId()`, `getAccountId()`) | a profile deletion is durable |

All world events extend `AwfWorldEvent` (`getWorldName()`; the event carries a name, not a `World`, which may already be
gone). Details that matter:

- **World events** are fired only for operations made through `AuroraWorlds` (a plugin, the `/awf` command, the server's own
  autoload and disposable-instance cleanup). A creation fires `AwfWorldCreatedEvent` and then `AwfWorldLoadedEvent` with `isCreated()` true; an ordinary load or
  autoload fires `AwfWorldLoadedEvent` with `isCreated()` false; a deletion of a disposable instance fires `AwfWorldDeletedEvent` and then
  `AwfInstanceDisposedEvent`; `AwfWorldUnloadedEvent` fires on a successful unload and on a failed one that left the world gone. `Bukkit.unloadWorld` and the engine's periodic autosave and shutdown save fire
  none. They come from one dedicated daemon thread (`SourbyCraft-AWF-world-events`), in the order the outcomes were reached, through a
  **bounded queue (1024): when it is full an event is dropped, counted and logged** instead of slowing the world operation.
- **Profile events** are fired only while MultiProfile runs, from a thread of the profile service, once, by the node that made
  the change. `ProfileSwitchedEvent` never fires for a refused or failed switch or one whose outcome is `UNKNOWN_OUTCOME`.
  `ProfilePreSwitchEvent` may be followed by no `ProfileSwitchedEvent` (the switch can still fail), and a handler may run after
  the switch has finished.
- **No event is the source of truth.** A crash between an outcome and its event loses the event. Use `AuroraWorlds`
  (`list`, `isLoaded`, `exists`) and `AuroraProfiles` to reconcile.

```java
@EventHandler
public void onSwitched(ProfileSwitchedEvent event) {
    Player player = Bukkit.getPlayer(event.getPlayerId());          // lookup by id: fine on any thread
    if (player == null) return;
    String text = "Now playing profile " + event.getProfile().id();
    SourbyAPI.scheduler().runFor(plugin, player, () -> player.sendMessage(text), null);   // touch the player on its region
}
```

The earlier co-op and bank events (`Coop*Event`, `ProfileBankTransactionEvent`, `ProfileSalvageDeliveredEvent`) follow the
same rules.

## 8. Checklist

- `plugin.yml`: `folia-supported: true`, `api-version: '26.2'`.
- Guard on `SourbyAPI.isSourbyCraft()` and `SourbyAPI.build().atLeast(N)` for the Build you need.
- Optional services: `find…` or catch `FeatureUnavailableException`. MultiProfile and co-op are off by default.
- Never join a SourbyAPI future on a region, entity or global thread; hop with `SourbyAPI.scheduler()`.
- A bank call carries your own operation id; on `UNKNOWN_OUTCOME` read `bankReceipt` before any refund.
- Do not shade `sourbyapi`.

The example plugin ([`examples/sourbyapi-example/`](../../examples/sourbyapi-example/)) uses `SourbyAPI.build()`,
`worlds().createInstance` and `listWorlds`, `profiles().isEnabled()` and `getProfileDetails`, `profileTypes().register` (the
ironman definition above), `scheduler().runAt/runFor/runAsync`, one AWF event and one profile event. It is opt-in like
`test-plugin`: `./gradlew -PincludeTestPlugin=true :sourbyapi-example:jar` (CI builds it with the other opt-in projects; it
is not booted by CI).
