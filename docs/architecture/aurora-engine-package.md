# Where Aurora's engine code lives

Aurora is the engine; SourbyCraft is what surrounds it. The console already says so — a line from
`net.minecraft`, `io.papermc.paper` or `io.canvasmc` prints as **Aurora Engine**, a line from
`dev.iyanz.sourbycraft` prints as **SourbyCraft**. The source tree now says so too.

## The two trees

| Tree | Package | What belongs there |
| --- | --- | --- |
| `sourbycraft-server/src/minecraft/java` | `dev.iyanz.aurora.*` | engine code SourbyCraft wrote |
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

* `dev.iyanz.aurora.level.SnapshotPathRegion` — an immutable block snapshot so an A* solve can run
  off the region thread. Added by feature patch 0005, previously sitting in
  `net.minecraft.world.level` where nothing distinguished it from Mojang's own classes.

## What does not move here

Engine *edits* — random-tick scratch buffers, the tick-metrics hook, the spawn-state guard, the
region tick-thread default — stay in the files they change. They are not separable classes; they
are changes to upstream behaviour, and the patch system already records exactly which lines are
SourbyCraft's.

Nor does anything that runs outside the engine. `dev.iyanz.sourbycraft.execution` holds the
execution contracts and the region system because those are SourbyCraft's account *of* the engine,
consumed from outside it. Aurora's engine package is for code that runs as the engine.
