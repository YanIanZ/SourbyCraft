# Developing plugins with Aurora World Fabric

AWF provides persistent worlds and frozen templates with copy-on-write clones through one
server service. These creation modes use the existing file/Redis storage implementation.

## Getting the service

Depend on SourbyCraft's `sourbyapi` artifact and obtain the service after it is registered:

```java
AuroraWorlds worlds = Bukkit.getServicesManager().load(AuroraWorlds.class);
if (worlds == null) {
    throw new IllegalStateException("AuroraWorlds is unavailable");
}
```

Import `dev.iyanz.sourbycraft.api.world.AuroraWorlds`, `WorldRequest`,
`WorldOperationBusyException` and `UnloadResult`. Names are lower-case `[a-z0-9_-]{1,48}`;
storage-folder names such as `region`, `entities` and `poi` are reserved. Allocate names under
your plugin's prefix, for example `arena_match_42`, to avoid accidental name collisions.

## Persistent worlds

```java
CompletableFuture<World> survival = worlds.create(
    WorldRequest.persistent("survival_main")
        .withSeed(20261006L)
        .withAutoload(true)
);

CompletableFuture<World> empty = worlds.create(
    WorldRequest.persistent("island_source").withGenerator("void")
);
```

Persistent requests also accept `withEnvironment`, `withType` and `withGenerator("Plugin:id")`.
Requests are immutable; modifying one produces another request. A named generator can be
resolved again after restart. Existing `create(WorldCreator, generator, autoload)` calls still
work. The request API uses explicit `minecraft:<name>` keys, matching managed-world identity.

## Templates and clones

Prepare a source world, remove its players, then unload it with saving enabled. Only publish
the template after unload succeeds:

```java
worlds.unload("island_source", true).thenCompose(result -> {
    if (result != UnloadResult.SUCCESS) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("Source unload failed: " + result));
    }
    return worlds.saveTemplate("island_source", "island_base");
});

// After template publication completes:
CompletableFuture<World> island = worlds.create(
    WorldRequest.fromTemplate("island_base", "arena_match_42")
        .withAutoload(false)
);
```

A clone inherits the template's seed, environment, world type and generator. Only changed
chunks belong to the instance; readers can share the frozen template. Several plugins can
create different instances concurrently. Both creation modes are persistent: `autoload(false)`
does not discard data on unload. For a disposable match, explicitly unload and delete its
instance when finished. A template cannot be deleted while registered instances refer to it.

## Coordinating plugins

Lifecycle operations reserve their world until engine work and cleanup finish. A second
operation on the same world fails promptly with `WorldOperationBusyException`; it is not queued.
The exception exposes `resource()` and `activeOperation()`. Chain dependent work after the
first operation's completion, or arrange a deliberate bounded retry in your plugin. For
example, remove players, then unload and delete:

```java
worlds.unload("arena_match_42", true).thenCompose(result -> {
    if (result != UnloadResult.SUCCESS) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("Match unload failed: " + result));
    }
    return worlds.delete("arena_match_42");
});
```

Export/conversion also reserve their normalized output path; template creation/deletion has
exclusive access while clone creation shares read access. Admission covers operations through
this service on this server. Direct Bukkit/filesystem changes and other servers remain subject
to their own ownership and backend rules. Cancelling a future does not abort engine work or
release admission early.

## Threads and bridged plugins

Use future continuations. Completion callbacks have no guaranteed thread; schedule block work
through the region scheduler and player/entity work through that entity's scheduler. A bridged
plugin can use this API, but bridge admission does not make arbitrary world/NMS calls safe.
Never wait with `join()`/`get()` from an entity or ordinary region task. Existing global-thread
creation/loading compatibility can complete synchronously and wait for I/O preparation; prefer
the asynchronous flow to avoid stalling the global tick.

Bridge `safe` mode is opt-in and RESTART_REQUIRED. At I/O saturation, a bridged async task is
cancelled and diagnosed; an AWF operation rejected by the existing I/O executor fails its future.
No extra plugin executors are needed for AWF lifecycle calls.

## Import and portability

Use `importSlime` for SourbyCraft's supported Slime v12/v13 format, `convertSlime` for conversion
without creating a world, and `exportWorld`/`importWorld` for portable `.awf` world files. Export
requires an unloaded world. This is format conversion and a SourbyCraft API; it is not a
drop-in implementation of every SWM/ASP plugin API or file format.

Multi-plugin admission has functional regression coverage. Real-plugin gameplay, many loaded
Minecraft worlds, power-loss recovery and throughput still need their separate qualification
workloads. See [AWF architecture](../architecture/aurora-world-fabric.md) and
[bridge architecture](../architecture/aurora-plugin-bridge.md).
