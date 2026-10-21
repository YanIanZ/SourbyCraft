# Scheduled functions on the global tick

Build 48 feature patch
`0065-SourbyCraft-tick-scheduled-functions-on-the-global-tick.patch` restores the
per-level vanilla timer drain through `world/ScheduledFunctionTicker`. Code and
regression tests are provided. Integration (integ48, 2026-10-21) applied the patch, compiled
and ran the full suites green, and booted it; see *Integration result* below. Datapack
functions themselves are still not loaded on this branch, so the end-to-end path is NOT
verified. This is not a qualification or performance claim.

## Timer ownership and failure isolation

The region-threading baseline disabled the timer drain in `ServerLevel.tickTime`.
That method runs per region and must remain disabled for scheduled events.
`RegionizedServer.tickTime` now calls the owned helper once per world's global
tick, inside `world.tickTime`, immediately after advancing game time. It uses the
level's current game time. The existing game-element freeze and unloading-world
checks still apply. A time advance that skips past a deadline drains overdue events.

`ServerLevel.scheduledEvents` is already public; no new accessor is needed.
`MinecraftServer.getScheduledEvents()` continues to throw. Each world keeps its
own persisted vanilla queue; this change adds no executor, timer or region task.

The helper asserts global-thread ownership through the Aurora contract
(`RegionOwnership.ensureGlobalTickThread`; completions are handed over with
`RegionOwnership.executeOnGlobalTick`), so the helper names no region-scheduler class;
only the ledgered adapter `MoonriseRegionOwnership` does. Vanilla `TimerQueue.tick` removes a
due event before invoking its callback. If that callback throws an `Exception`,
the helper logs the exception with dimension and game time, then resumes the
queue at that same game time. The failed event is consumed and later due events
still run. Fatal JVM errors are not swallowed. Errors already caught by the
vanilla function executor retain its own logging behaviour. A tag callback keeps
vanilla function-manager execution semantics.

## Command registration and queue access

This branch also had `ScheduleCommand.register` commented out. Patch 0065 enables
`/schedule function <id|#tag> <time> [append|replace]` and `/schedule clear <id>`.
Both mutations go through `ScheduledFunctionTicker.onGlobal` (Aurora's
`RegionOwnership.executeOnGlobalTick`), the same contract as the engine's
`AbstractCommandExecution.executeOnGlobal` that console/global-state commands use:
inline on the global tick, else queued; a syntax error raised there is sent to the source. Clear-command completions also read the queue
on the global thread. Scheduling from a region therefore measures the delay when
the global thread handles the request, not when the player originally typed it.
The route returns acceptance (`1`), with completion feedback later; synchronous
command-result consumers do not receive the eventual timer value or clear count.
Permission checks, zero-delay and macro refusals remain vanilla.

## Function execution context and limits

The timer runs vanilla `FunctionCallback` / `FunctionTagCallback`, which use
`ServerFunctionManager.getGameLoopSender()`: the server command source with
GAMEMASTER permissions and suppressed output. It has no executing entity and
inherits neither the scheduling player's location nor command-block ownership.
The source's dimension and position come from the server's respawn dimension and
respawn position, even when the queue belongs to another world. Use explicit
`execute in` / positioning when the existing command paths support it.

This is the console command threading model, not unrestricted vanilla world access:

- Global operations such as `say`, `time`, `gamerule`, `difficulty` and objectives
  use their existing command paths. Block mutations such as `setblock` / `fill`
  and entity operations such as teleport retain the engine's owner routing.
- Queued region work may complete after the function advances to its next line.
  A function is not an atomic transaction across regions; return values and
  `execute store` must not be assumed to describe completed asynchronous work.
- Commands and plugin code requiring immediate region ownership still refuse or
  fail under their existing guards. The inherited `data get block` console path
  has a documented failure; synchronous foreign-region block reads and selectors
  have not received a new safety audit here. See [command blocks](command-blocks.md).
- `/function`, `/reload` and `/return` registration remain disabled on this branch.
  In particular, function files containing an unregistered `function` or `return`
  command cannot be assumed to load. This change does not restore `minecraft:tick`
  or `minecraft:load` tag execution; it drains explicitly scheduled functions/tags.
- Inherited scoreboard commands still reject fake-player holders such as `#t`.

These routing/refusal statements come from source inspection and existing
command-block documentation. Scheduled-function execution and cross-region
ordering have not been demonstrated in a live server run for this change.

## Regression coverage and remaining validation

`ScheduledFunctionTickerTest` drives the real vanilla `TimerQueue`: exact due
time, future deadlines, exceptions with later events, repeated invocation at the
same game time, and overdue events. `ScheduledFunctionPatchShapeTest` pins the
single global hook, command registration and global queue routing, and protects
the disabled per-region path and throwing server accessor.

The PM must run patch application, Java compilation, both new test classes and a
boot/datapack probe (including append/replace/clear, function tags, save/restart,
freeze/unfreeze and multi-region execution). No Gradle command was run here.

Local non-Gradle validation (2026-10-21):

```text
patch_policy: OK (82 patches under 3 patch trees)
baseline_policy: OK (726 baseline files match build-data/engine-baseline.sha256)
```

Materialized Minecraft commit: `1f840837b00cde884c814d2a1babd9f35669e02b`
(only the three 0065 integration files; earlier uncommitted work was excluded).

## Integration result (integ48, 2026-10-21)

- Applied with every Build 48 patch; `ScheduledFunctionPatchShapeTest` 2/0,
  `ScheduledFunctionTickerTest` 5/0, `RegionOwnershipContractTest` 2/0 (global-tick contract
  and `onGlobal`). `/schedule` mutations now go through `ScheduledFunctionTicker.onGlobal`
  instead of naming the engine's `AbstractCommandExecution` from the patch.
- Boot (agent test server, functional only): `/schedule` is registered (`schedule function
  minecraft:nonexistent 20t` -> `Unknown function`; `schedule clear integ48:hello` -> `No
  schedules with ID`). A world datapack `file/integ48` was enabled, but `schedule function
  integ48:hello 20t` -> `Unknown function integ48:hello`.
- Cause, from code: the engine baseline
  (`minecraft-patches/sources/net/minecraft/server/ReloadableServerResources.java.patch`,
  `// Canvas - region threading`) returns only recipes and advancements from `listeners()`,
  so `ServerFunctionLibrary` never loads any `.mcfunction` or function tag. Until that is
  owned and restored, `/schedule function` can only refuse; no scheduled function can fire.
  This guard shape is not detected by `inherited_guard_policy.py` (no constant `if`/throw).
