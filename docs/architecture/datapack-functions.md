# Datapack functions on the region-threaded server

> **Role:** contract for datapack functions, function tags, `/function`, `/return`, `/schedule` and the
> `#minecraft:load` / `#minecraft:tick` lifecycle on the region-threaded engine. **Status:** implemented
> (Build 48: Minecraft feature patches `0065-SourbyCraft-tick-scheduled-functions-on-the-global-tick.patch`
> and `0066-SourbyCraft-owned-datapack-functions-on-the-global-tick.patch`); unit tests and functional
> test-server runs only, not qualified, no performance claim. Formerly `scheduled-functions.md`.
>
> Code: `dev.iyanz.sourbycraft.world.{DatapackFunctions,FunctionLifecycle,ScheduledFunctionTicker}`.
> Entry point: [AURORA.md](AURORA.md). Related: [command blocks](command-blocks.md).

## What was inherited

The region-threading baseline turned datapack functions off in four places:

| Place | Inherited state | Now |
| --- | --- | --- |
| `ReloadableServerResources.listeners()` (engine baseline, `// Canvas - region threading`) | `functionLibrary` dropped: no `.mcfunction` file and no function tag ever loaded | restored by 0066 (feature-patch hunk; the baseline file is unchanged) |
| `Commands` (`// Folia - region threading - TODO`) | `/function`, `/return`, `/reload`, `/schedule` not registered | `/schedule` by 0065; `/function`, `/return` by 0066; `/reload` stays unregistered |
| `MinecraftServer.tickChildren` (`// TODO Purge functions`) | `getFunctions().tick()` commented out: `#minecraft:load` and `#minecraft:tick` never ran | stays commented out (`tickChildren` now runs per region); 0066 runs the tags from the global tick instead |
| `ServerLevel.tickTime` | per-level timer queue (scheduled functions) never drained | 0065 drains it from the global tick |

`inherited_guard_policy.py` does not detect these shapes (no constant `if`/throw), so none of them was in
the guard manifest; they are recorded in [inherited-guards.md](inherited-guards.md) section 3's "done
outside this table" note.

Restoring the library has knock-on effects that were dead code before: advancement `rewards.function`,
the `run_function` enchantment effect, `execute if function` and `debug function` now find their
functions. Advancement rewards run on the player's region thread with the player's source; the
enchantment effect runs on the thread that applies the enchantment, with the affected entity as source.
Both are the same "commands as that source on that thread" model as below; neither got a separate live run.

## Lifecycle: `#minecraft:load` and `#minecraft:tick`

- `ServerFunctionManager.postReload` (its constructor at boot, and `replaceLibrary`, which only a
  successful reload reaches) calls `DatapackFunctions.published()`: one new library generation.
- `RegionizedServer.globalTick` calls `DatapackFunctions.globalTick(server, runsGameElements)` once per
  server tick, after console input and before the worlds' global ticks (vanilla runs functions before
  levels tick). The helper asserts the global tick thread through Aurora's
  `RegionOwnership.ensureGlobalTickThread`; it names no region-scheduler class.
- `FunctionLifecycle` runs `#minecraft:load` once for each new generation, on the first tick that runs
  game elements, before `#minecraft:tick` in that tick. The generation is marked before the functions run
  (vanilla clears `postReload` first), so a throwing load function does not make load run again.
  A frozen global tick (`doesRunGameElements() == false`) defers both tags, as vanilla's
  `tickRateManager().runsNormally()` gate does.
- `#minecraft:tick` runs only when `aurora.world.tick-functions = true` (default `false`). When it is off
  the tick tag is not even looked up. The unchanged vanilla `ServerFunctionManager.tick()` is never
  called, because it would run the tick tag unconditionally.
- A failed reload never calls `replaceLibrary`, so the old library keeps answering and load does not run
  again. Today no reload is reachable at all (below), so in practice load runs once per boot.
- Both tags run with vanilla's game-loop source: the server source, GAMEMASTER permission, output
  suppressed (`say` still reaches the log and players). Each function is executed through vanilla's
  `ServerFunctionManager.execute`, which logs and swallows a failing function; the next one still runs.

`aurora.world.tick-functions` is **LIVE**: `SourbyCraftConfig` publishes it at boot and at
`/sourbycraft reload`, and the global tick reads it every tick. Switching it on never re-runs
`#minecraft:load`. Off by default because the base never ran tick functions and a tick tag runs every
tick with console rights; `#minecraft:load` does not depend on the key (vanilla behaviour, one run per
library).

## `/function` and `/return`: execution model and the honest cross-region contract

`/function` does not move execution anywhere. The function's commands are queued into the invoking
command's own execution context and run on the same thread with the invoking source, modified as vanilla
does (output suppressed, permission capped at GAMEMASTER). `/return` only ends a function frame; it
touches no world state.

| Invoked by | Thread | Source | What each command in the function then does |
| --- | --- | --- | --- |
| Console, `#minecraft:load` / `#minecraft:tick`, `/schedule` timers | global tick | server source | console rules: global state inline; block changes queued to the owning region; entity actions on each entity's scheduler |
| A player | that player's region | the player | exactly what the player typing the same command would do |
| A command block (with `aurora.world.command-blocks = true`) | the block's region | the block | still inside the 0055 command-block scope, which is thread-local and spans the whole execution context: region-local selectors, block targets outside the region refused |
| Advancement reward / `run_function` enchantment | the entity's region | that player / entity | as for a player |

What this means for datapack authors (the honest contract, not vanilla semantics):

- A function is **not** an atomic transaction across regions. Queued region work (block changes, entity
  actions) may complete after the function has moved to its next line, or after it has returned; return
  values and `execute store` describe the command's immediate result (often "1 = queued"), not finished
  work.
- From the console path, entity selectors are not region-filtered and read foreign entities' state from
  the global thread, as console commands already do on this engine. Only the command-block scope filters.
- Commands and plugin code that need immediate region ownership keep failing under their existing
  guards. Known inherited failures: console `data get block` NPE in `BlockDataAccessor` (so
  `function … with block …` from the console path is expected to fail the same way); fake score holders
  such as `#t` rejected by every source ("must be an entity"), so counters need an entity holder or
  storage. Synchronous foreign-region block reads have not had a new audit here.
- Cross-region ordering, `with entity`/`with block` macro sources, and long-running tick tags under load
  have not been demonstrated live.

## `/schedule` (patch 0065)

`RegionizedServer.tickTime` calls `ScheduledFunctionTicker.tick(world)` once per world on the global
tick, inside `world.tickTime`, right after advancing game time; the per-region `ServerLevel.tickTime`
drain stays disabled and `MinecraftServer.getScheduledEvents()` keeps throwing. Each world keeps its own
persisted vanilla queue. `TimerQueue.tick` removes a due event before calling it; if the callback throws an
`Exception` the helper logs it (dimension, game time) and resumes at the same game time, so the failed event
is consumed once and later due events still run. Fatal JVM errors propagate. An overdue event (time skipped
past its deadline) fires on the next drain.

`/schedule function <id|#tag> <time> [append|replace]` and `/schedule clear <id>` mutate the queue through
`ScheduledFunctionTicker.onGlobal` (Aurora's `RegionOwnership.executeOnGlobalTick`): inline on the global
tick, else queued; a syntax error raised there is sent to the source. Suggestions also read the queue on the
global thread. Scheduling from a region therefore measures the delay from when the global thread handles the
request, and the command returns acceptance (`1`) with feedback later; synchronous result consumers do not
get the eventual tick or clear count. The timer runs vanilla `FunctionCallback`/`FunctionTagCallback` with
the game-loop source (no executing entity; dimension and position from the server's respawn point even when
the queue belongs to another world). Permission checks, zero-delay and macro refusals stay vanilla.

## `/reload` stays unregistered

`/reload` (and `Bukkit.reloadData()`, which `CraftServer#2` already refuses with an owned message) is not
restored. `MinecraftServer.reloadResources` uses the server itself as the executor for pack opening and for
the final swap, and `MinecraftServer.execute` refuses on this engine (`RegionRefusal.SERVER_QUEUE`), so the
inherited implementation cannot even start. More importantly, the swap replaces recipes, advancements, tags,
loot tables, functions and the command dispatcher that every region reads concurrently, and there is no
region-safe registry reload.

An owned reload would need, at least: (1) preparation on a background executor and the swap as a global-tick
task instead of the server executor; (2) a barrier so no region tick runs while registries, tags and recipes
are swapped (or immutable snapshots that regions pick up at a tick boundary); (3) per-player resend of
recipes/advancements/commands on each player's own region thread; (4) a rule for in-flight scheduled timers
and function frames that reference the old library; (5) `/datapack enable|disable` built on the same path
(its guard is KEEP-OWNED for the same reason). Until then: change packs, restart. The lifecycle above is
already generation-based, so a future owned reload that calls `replaceLibrary` gets `#minecraft:load` once
per successful publish without further changes.

## Regression coverage

- `FunctionLifecycleTest` (6): load once per generation and before tick, nothing before the first publish,
  no publish = no second load, tick tag never looked up while disabled, LIVE on/off, frozen ticks defer
  both tags, a throwing function is reported and the rest still run, cross-thread publish.
- `DatapackFunctionLibraryTest` (4): a real datapack directory through vanilla `ServerFunctionLibrary`
  (functions, `#minecraft:load`/`#minecraft:tick`, nested tag reference, malformed file dropped alone, tag
  with a missing function not built); without `/function` registered a function that calls another cannot
  load; nested call order with `return` ending the callee; boot and `replaceLibrary` each publish a
  generation through the real `ServerFunctionManager`, a failed reload publishes none and the old library
  still answers.
- `DatapackFunctionsPatchShapeTest` (4): the listener hunk, `/function` + `/return` registered and
  `/reload` not, one publish hook in `postReload`, one global-tick call after console input with the frozen
  gate, no `getFunctions().tick()` and no `MinecraftServer` hunk.
- `AuroraConfigTest#tickFunctionsAreOffByDefaultLiveAndOffOnATypo`.
- 0065: `ScheduledFunctionTickerTest` (5), `ScheduledFunctionPatchShapeTest` (2),
  `RegionOwnershipContractTest`.

## Evidence

Functional, shared machine, not certified. Jar `14ebc935e904…` built from worktree `opus-functions`
(base `e0198582` + this change, `git.dirty=true`), `scripts/agent_testserver.py`:

- `opus-functions-1` (fresh world, no datapack): `function fn:nothing` → `Unknown function fn:nothing`
  (registered); `reload` → `Unknown or incomplete command`. Clean stop (exit 0).
- Datapack `fn` added to that world (`hello` → says `fn-hello`, calls `fn:inner`, says `fn-hello-after`;
  `inner` says `fn-inner`, `return 1`, says `fn-never`; `#minecraft:load` → `fn:load` says `fn-load-ran`,
  creates objective `fnc`, forceloads chunk 0,0, summons a tagged marker; `#minecraft:tick` → `fn:tick`
  adds 1 to the marker's `fnc`; `bad.mcfunction` is not a command).
- `opus-functions-2 --world-from opus-functions-1` (key default off): `Found new data pack file/fn`,
  `Failed to load function fn:bad` (the others loaded), one `fn-load-ran` right after `Done`;
  `function fn:hello` → `fn-hello`, `fn-inner`, `fn-hello-after`, no `fn-never`;
  `schedule function fn:hello 20t` → `Scheduled function 'fn:hello' in 20 tick(s) at gametime 365`, the
  three lines again one second later; marker score `none is set` 8 s after boot (tick tag off); one
  console `function #minecraft:tick` → `Marker has 1`, still `1` 21 s after boot. Exactly 1 `fn-load-ran`, 0 `fn-never`; the only
  ERROR lines are the `fn:bad` parse failure. Clean stop.
- `opus-functions-3 --world-from opus-functions-1 --aurora-toml` (`tick-functions = true`): marker `2` at
  game time 242, `329` at game time 564 (about one per tick). LIVE: set `false` + `/sourbycraft reload` →
  `488`, still `488` 5 s later; set `true` + reload → `558` 3 s later; still exactly one `fn-load-ran`
  (no re-run on toggle). Clean stop, slots free.

Not done: real client, multi-region ordering, `with entity|block` macro sources, advancement-reward and
enchantment paths live, soak, performance.
