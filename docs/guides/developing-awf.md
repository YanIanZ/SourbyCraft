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

How `unload(name, true)` ends in the Build 50 candidate (PR #62; code written and statically reviewed;
not built; not tested by the owner): for a world AWF manages, the world is first saved durably (not
once the server is stopping; its shutdown saves the world). If that
save fails, the future completes exceptionally (an `IOException` naming the world) and the world stays
loaded for a retry. `UnloadResult.FAILED` can mean the world was unloaded but its final commit could not be
confirmed: check `isLoaded` and the server log before treating the data as saved (`/awf unload` says so in
its reply).

Export/conversion also reserve their normalized output path; template creation/deletion has
exclusive access while clone creation shares read access. Admission covers operations through
this service on this server. Direct Bukkit/filesystem changes and other servers remain subject
to their own ownership and backend rules. Cancelling a future does not abort engine work or
release admission early.

## Threads and bridged plugins

Use future continuations. Completion callbacks have no guaranteed thread; schedule block work
through the region scheduler and player/entity work through that entity's scheduler. A bridged
plugin can use this API, but bridge admission does not make arbitrary world/NMS calls safe.
Never wait with `join()`/`get()` from an entity or ordinary region task. On the global region
thread, creation/loading/cloning futures can complete on a later global tick (since 2026-10-13):
`join()`/`get()` on an unfinished one there throws `IllegalStateException`, because the tick
that would finish it is the one blocked. Continue with `thenAccept`/`whenComplete` instead.

Bridge `safe` mode is opt-in and RESTART_REQUIRED. At I/O saturation, a bridged one-shot async task
is cancelled and diagnosed, and a repeating one skips that run and stays scheduled; an AWF operation
rejected by the existing I/O executor fails its future. No extra plugin executors are needed for AWF
lifecycle calls.

## Import and portability

Use `importSlime` for SourbyCraft's supported Slime v12/v13 format, `convertSlime` for conversion
without creating a world, and `exportWorld`/`importWorld` for portable `.awf` world files. Export
requires an unloaded world. This is format conversion and a SourbyCraft API; it is not a
drop-in implementation of every SWM/ASP plugin API or file format.

World data in the Build 50 candidate (code written and statically reviewed; not built; not tested by
the owner):

- `WorldProperties.Spawn` is validated: x and z within ±29 999 984, y from -2032 to below 2032, all
  finite, else `IllegalArgumentException`. A registry or template entry written earlier with such a
  spawn loses only that spawn, with a WARN naming the world on each read (the registry is read twice per
  boot, templates on every read); the next registry write removes it from
  `sourbycraft_config/aurora-worlds.json`. An entry with invalid save bounds (min above max) makes the
  registry unreadable: the `AuroraWorlds` service fails its boot stage with an `IOException` naming the file
  and is not registered.
- A Slime import or conversion drops an out-of-world spawn and lists `properties.spawnX/Y/Z/Yaw` in its
  WARN of values it did not keep. An `.awf` file exported by an earlier build (up to `v26.2-r50`, which
  did not range-check spawns) with such a spawn imports without that spawn and one WARN naming the new
  world and the file's recorded source; a spawn that is not numbers, or save bounds with min above max,
  still refuse the whole import.
- `importWorld` restores the file's `plugin-data` into the world's persistent data container before the
  future completes, after `WorldLoadEvent` has fired (a listener on that event does not see it yet);
  malformed `plugin-data` fails the import. `importSlime` restores the Slime world's `BukkitValues` the
  same way.

Multi-plugin admission has functional regression coverage. Real-plugin gameplay, many loaded
Minecraft worlds, power-loss recovery and throughput still need their separate qualification
workloads. See [AWF architecture](../architecture/aurora-world-fabric.md) and
[bridge architecture](../architecture/aurora-plugin-bridge.md).
