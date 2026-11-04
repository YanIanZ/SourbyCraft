# PaperLib's version check on 26.x (EssentialsX teleports) (2026-10-22)

## Finding

Sourby Demo panel, v26.2-r48.2, Epic Skyblock, `aurora.bridge.mode = safe`. Two live failures, one cause:

1. A new player joined. EssentialsXSpawn's `NewPlayerTeleport` (a Bukkit `runTaskLater` from the join listener, bridged onto the global tick) called `AsyncTeleport.nowAsync` -> `PaperLib.getChunkAtAsync` -> `AsyncChunksSync` -> `CraftWorld#getChunkAt` -> `Thread failed main thread check: Async chunk retrieval ... region={god-tick} ... chunk_pos=[1, -1]`, and the bridge counted a region-ownership violation for Essentials (three new players -> quarantine).
2. A player walked into a CommandRegions region; its listener (move packet, the player's region thread) dispatched `/warp`; `Commandwarp` -> `AsyncTeleport.nowAsync` -> the same `AsyncChunksSync` -> `getChunkAt` for a chunk in another world -> the same refusal on `Folia Region Scheduler Thread #1`. Not a bridged task, so no violation was counted, but the warp failed.

Every EssentialsX teleport (spawn, warp, home, tpa, back) goes through this path whenever the target chunk is not owned by the calling thread.

## Root cause

EssentialsX 2.22.1 shades PaperLib 1.0.x (`io.papermc.lib`, relocated to `com.earth2me.essentials.paperlib`; PaperLib is archived upstream). From the jar's bytecode:

- `PaperLib.initialize()` finds `com.destroystokyo.paper.PaperConfig` and builds a `PaperEnvironment` (Paper is detected correctly).
- `Environment.<init>` parses `Bukkit.getVersion()` with `\(MC: (\d)\.(\d+)\.?(\d+?)?(?: Pre-Release )?(\d)?\)`. The major is one digit followed by a dot, so `"... (MC: 26.2)"` never matches; `minecraftVersion` stays 0.
- `PaperEnvironment` only installs `AsyncChunksPaper_13`/`_15` (`World#getChunkAtAsync`) and `AsyncTeleportPaper_13` (`Entity#teleportAsync`) when `isVersion(13, 1)` (or `isVersion(9)`); with 0 it keeps the `Environment` defaults `AsyncChunksSync` (`World#getChunkAt` on the calling thread) and `AsyncTeleportSync` (`Entity#teleport`).

`CraftServer#getVersion` is Paper's unchanged `"<version> (MC: <MC version>)"`, so **stock Paper 26.x parses the same 0** and PaperLib falls back there too; on Paper the synchronous load simply runs on the one main thread. On a region-threaded server a thread may not load or read a chunk it does not own, and the guard (correctly) refuses.

## Fix (paper feature patch "PaperLib version parse sees a 26.x server")

`dev.iyanz.sourbycraft.compat.PaperLibVersionAdapter`, called on every plugin class's bytes before they are defined (legacy plugins: `CraftMagicNumbers#processClass`, before Commodore; Paper plugins: `PaperClassloaderBytecodeModifier`). For a class named `.../environments/Environment` that contains PaperLib's exact regex constant, the one `Bukkit.getVersion()` call in its constructor that follows that constant is replaced by the constant `"(MC: 1.21.11)"` (same stack effect, no frames change). PaperLib then picks its Paper paths: `World#getChunkAtAsync` (the chunk system re-schedules the load onto the owning region and completes there) and `Entity#teleportAsync`. A class without the regex bytes is returned untouched after one byte scan; anything that does not parse or does not have exactly one such call is returned untouched. One INFO line per adapted class names the plugin.

What it does not change: `Bukkit.getVersion()`/`getBukkitVersion()`/`getMinecraftVersion()` for everyone else (the auto-updater, bStats, ViaVersion and other parsers see the same strings as before); the region-ownership guard in `CraftWorld#getChunkAt`; bridge task routing (owned by the bridge-routing work). 1.21.11 is the last 1.x release; every PaperLib gate is at or below 1.15.2, and its 1.15.2 reflective probe falls back cleanly if a method is missing.

Setting: always on; `-Dsourbycraft.paperlib.adapt=false` turns it off (JVM flag, RESTART_REQUIRED).

Remaining EssentialsX behaviour on the async path: `nowAsync`'s continuation (safety check, `getSafeDestination`, `PaperLib.teleportAsync`) runs on the region that owns the target chunk; with `force-disable-teleport-safety: true` and an unsafe target it calls `Player#teleport` there, which goes through the owned sync-teleport route (paper 0017). Where an untargeted legacy task such as `NewPlayerTeleport` runs (global tick) is a bridge-routing question and not changed here.

## Evidence

- `PaperLibVersionAdapterTest` (7): root cause (PaperLib's pattern does not match `(MC: 26.2)`), the adapted constructor parses minor 21 and `isVersion(13, 1)` is true, the unadapted constructor still calls `Bukkit.getVersion()`, unrelated classes / malformed bytes / kill switch return the same array, Paper-plugin bytecode modifier path. Fixture: `compat/fixture/paperlib/environments/Environment` (same constructor shape, written from the bytecode; no PaperLib code copied).
- Agent test server, `aurora.bridge.mode = safe`, EssentialsX + EssentialsXSpawn 2.22.1-dev+24 (panel jars) and a probe plugin that calls Essentials' relocated `PaperLib.getChunkAtAsync(world, x, z, true, false)` exactly as `nowAsync` does, (A) from a legacy `runTaskLater` (global tick, chunk [1, -1]) and (B) from a region task at [0, 0] for chunk [-166, -69]:
  - control (`-Dsourbycraft.paperlib.adapt=false`, run `opus-ess-paperlib-control-3`): `env=PaperEnvironment mcVersion=0 chunks=AsyncChunksSync teleport=AsyncTeleportSync`; both calls fail with `Thread failed main thread check: Async chunk retrieval` (A on `region={god-tick}`, B on `region={center=[0, 0]}`), matching the panel logs.
  - fix (run `opus-ess-paperlib-fix-2`): `PaperLib in Essentials (...Environment): version check adapted`; `env=PaperEnvironment mcVersion=21 chunks=AsyncChunksPaper_15 teleport=AsyncTeleportPaper_13`; A completes with `CraftChunk{x=1z=-1}`, B with `CraftChunk{x=-166z=-69}`, each on a region thread, no guard failure.
- Not done: a real player join/warp (needs a client); this is the owner's panel test.

## Status 2026-10-25 (PR #62)

The panel failures in *Finding* (a violation counted for Essentials, quarantine after three new players) happened on
v26.2-r48.2, whose ownership checks behave like today's `aurora.bridge.thread-checks = enforced`. Since Build 49,
with `aurora.bridge.mode = safe`, the default is `relaxed` (owner decision 2026-10-22): the same synchronous
`getChunkAt` on a thread that does not own the chunk would be counted as a relaxed ownership hit instead of refused,
and would never quarantine. With this adapter in place the path is not reached (PaperLib uses `getChunkAtAsync`).
Relaxed is unsafe and unqualified. PR #62 did not change the adapter.
