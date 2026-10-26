# Where Aurora's engine code lives

> **Role:** where engine code lives (`dev.iyanz.aurora.*` versus `dev.iyanz.sourbycraft.*`). **Status:** Active.
>
> Entry point: [docs/architecture/AURORA.md](AURORA.md).

Aurora is the engine; SourbyCraft is what surrounds it. The console says so: the logger tag
converter (`sourbycraft-server/src/log4jPlugins/java/dev/iyanz/sourbycraft/console/SourbyCraftLoggerConverter`)
folds logger names onto five tags, first matching prefix wins:

| Tag | Logger names |
| --- | --- |
| `SourbyCraft` | `dev.iyanz.sourbycraft.*` (and the owned `SourbyCraft/<Area>` loggers) |
| `Aurora` | `dev.iyanz.aurora.*`, the region-threading engine's `io.papermc.paper.threadedregions.*`, and a fixed list of engine classes that log by simple name (`TickRegionScheduler`, `WorldShutdownThread`, `ConfigurationProvider`, ...) |
| `Minecraft` | `net.minecraft.*`, `com.mojang.*`, the `Minecraft` logger and the root logger |
| `Paper` | every other `io.papermc.*`, `ca.spottedleaf.*`, `org.bukkit.craftbukkit.*`, `com.destroystokyo.paper.*`, `org.spigotmc.*`, and a fixed list of Paper classes that log by simple name (`ChunkTaskScheduler`, `MoonriseCommon`, ...) |
| `OSHI` | `oshi.*` |

`com.sk89q.*` and `ru.tehkode.*` print their simple class name; every other logger name, plugin
names included, prints unchanged ([AURORA-UX.md](../AURORA-UX.md) "Line shape"). The source tree
draws the same line between `dev.iyanz.aurora` and `dev.iyanz.sourbycraft`.

## The two trees

| Tree | Package | What belongs there |
| --- | --- | --- |
| both trees | `dev.iyanz.aurora.*` outside `engine` | engine code SourbyCraft wrote: a class a feature patch adds lands in `src/minecraft/java` (`dev.iyanz.aurora.level.SnapshotPathRegion`); a class written as owned source lives in `src/main/java` (`dev.iyanz.aurora.cpu.AuroraCpu`, `dev.iyanz.aurora.command.AuroraCommandErrors`) |
| both trees | `dev.iyanz.aurora.engine.*` | the region-threading engine inherited from Canvas (formerly `io.canvasmc.canvas`, renamed 2026-10-05): the engine-baseline classes materialized into `src/minecraft/java`, and the owned engine sources in `src/main/java` (`configuration`, `spark`, `util`, `world`, `threadedregions`, plus `GlobalConfiguration`, `WorldConfig` and `BroadcastPermission` at the package root); its plugin API moved to `dev.iyanz.sourbycraft.api` in `sourbyapi` in Build 48 (the old package was removed, no shim) |
| `sourbycraft-server/src/minecraft/java` | `net.minecraft.*`, `ca.spottedleaf.*` | upstream, and SourbyCraft's edits to it |
| `sourbycraft-server/src/main/java` | `dev.iyanz.sourbycraft.*` | everything outside the engine |

The distinction that matters is the first two rows. A patch that *edits* an upstream file stays in
that file's own package — the edit belongs where the code it changes lives, and moving it would
make every upstream diff unreadable. A patch that adds a *whole new class* has a choice, and
putting it in a vanilla package is the wrong one: it hides which lines of the engine SourbyCraft
wrote, and makes a new file read as though it came from above.

`dev.iyanz.aurora` is a separate root from `dev.iyanz.sourbycraft` on purpose. Same author,
different side of the boundary, and the logger classifies them differently — engine code speaks as
the engine.

## Current inhabitants

SourbyCraft-written classes outside the inherited `engine` package (checked 2026-10-25):

* `dev.iyanz.aurora.level.SnapshotPathRegion` (`src/minecraft/java`) — an immutable block
  snapshot so an A* solve can run off the region thread. Added by feature patch 0005, previously
  sitting in `net.minecraft.world.level` where nothing distinguished it from Mojang's own classes.
* `dev.iyanz.aurora.cpu.AuroraCpu` (`src/main/java`) — the Aurora CPU budget: resolves the region
  tick-thread default from `aurora.cpu.cores` or the processor count. Called from feature patch
  0002 while `GlobalConfiguration` loads, before the bootstrap has read any SourbyCraft file.
* `dev.iyanz.aurora.command.AuroraCommandErrors` (`src/main/java`) — the cross-region command
  failure (`CANNOT_DO_X_CROSS_REGION`) Minecraft patches report, so a vanilla class does not reach
  into the engine's plugin API.

SourbyCraft-written classes inside `dev.iyanz.aurora.engine` (`src/main/java`):

* `engine.threadedregions.WorldUnloadDrain` and `WorldUnloadCompletion` — what a world unload
  owes the work still queued on it (audit ENG-3), run by the engine's `WorldShutdownThread`
  (feature patch 0033) after the world's chunks are saved.
* `engine.threadedregions.scheduler.AuroraEdfScheduler` — the LeafPile-derived EDF backend.
  **Unselected:** the EDF patch that would construct it is parked (owner decision 2026-10-07;
  [AURORA-EDF-UPGRADE.md](../development/AURORA-EDF-UPGRADE.md)), so the running scheduler is
  still LeafPile's `EDFSchedulerThreadPool`.

The rest of `dev.iyanz.aurora.engine` is the inherited engine (former Canvas/Folia sources)
that SourbyCraft has owned since 2026-10-05.

## What does not move here

Engine *edits* — random-tick scratch buffers, the tick-metrics hook, the spawn-state guard, the
region tick-thread default — stay in the files they change. They are not separable classes; they
are changes to upstream behaviour, and the patch system already records exactly which lines are
SourbyCraft's.

Nor does anything that runs outside the engine. `dev.iyanz.sourbycraft.execution` holds the
execution contracts and the region system because those are SourbyCraft's account *of* the engine,
consumed from outside it. Aurora's engine package is for code that runs as the engine.
