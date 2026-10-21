# Command blocks — SourbyCraft-owned region rules

> **Role:** design and contract for command blocks and command-block minecarts on the region-threaded engine. **Status:** implemented behind `aurora.world.command-blocks` (default `false`); functional evidence only, not qualified. Owner direction 2026-10-21: replace inherited Canvas/Folia guards with SourbyCraft-owned implementations.
>
> Code: `dev.iyanz.sourbycraft.commandblock.{CommandBlockPolicy,CommandBlockRegions}`; hooks in Minecraft feature patch `0055-SourbyCraft-owned-command-blocks-under-region-rules.patch`. Entry point: [AURORA.md](AURORA.md).

## What was inherited

The region-threading baseline disabled command blocks with unconditional guards:

| Place | Inherited guard |
| --- | --- |
| `CommandBlock.tick` | `if (true) return;` (Canvas): command blocks never execute |
| `CommandBlock.useWithoutItem` | `if (true) return FAIL;` (Canvas): GUI never opens |
| `BaseCommandBlock.performCommand` | `if (true) return false;` (Folia): also stops command-block minecarts |
| `MinecartCommandBlock.interact` | `if (true) return FAIL;` (Canvas) |
| `ServerGamePacketListenerImpl.handleSetCommandBlock` / `handleSetCommandMinecart` | `if (true)` + "not enabled with region threading" |
| `ServerLevel.isCommandBlockEnabled` | `return false;` (Canvas): ignores the `command_blocks_work` gamerule |
| `BaseCommandBlock.CloseableCommandBlockSource.sendSystemMessage` | `threadCheck()`, which throws off the owning region (kept) |

With `aurora.world.command-blocks = false` (the default) every one of these behaves exactly as before: the
`if (true)` became `if (!CommandBlockPolicy.enabled())`, and `isCommandBlockEnabled()` became
`CommandBlockPolicy.enabled() && gamerule command_blocks_work`.

## How commands already behave on this engine

Command blocks were the only command source the baseline turned off. Player commands already run on the
player's region thread and console commands on the global region, through the same Canvas/Folia-adapted
commands:

- block changes (`setblock`, `summon`, `particle`, `place`, `loot`, `playsound`) are queued to the region that owns
  the target position (`AbstractCommandExecution.executeAtPos[WithRadius]`); `fill`, `fillbiome` and `clone` load and
  run at the target chunks; `data ... block` does the same through `BlockDataAccessor`;
- entity actions (`kill`, `tp`, `scoreboard players ...` on entities, `effect`, ...) are scheduled on each target's
  own entity scheduler;
- global state (`time`, `gamerule`, `difficulty`, `worldborder`, objectives, teams, bossbars, `forceload`) runs
  on the global region (`executeOnGlobal`);
- entity selectors are **not** region-filtered: `@a`/`@e` see every region, and the predicate reads foreign
  entities' position and tags from the calling thread;
- any exception during execution is caught by Paper's `Commands.performCommand` and reported as
  `command.failed` plus an ERROR log line; it does not crash the region.

Not registered at all on this engine (for any source): `/function`, `/reload`, `/return`, `/tick`,
`/test`, `/item`, vanilla `/perf`. Score commands only accept entity holders: a fake player such as `#t` fails
with `Holder target "#t" must be an entity` (from the inherited `ScoreboardCommand` adaptation, any source).

Build 48 patch 0065 enables `/schedule` and routes its per-level timer queue access to the global tick;
see [scheduled functions](scheduled-functions.md) for its execution context and limits.

## Decision (owned behaviour when the switch is on)

A block tick already runs on the region thread that owns the block position, so the block-tick part is
region-local. The risk is the command. The rules, all in `CommandBlockPolicy`:

1. **Execute only on the owning region.** `performCommand` runs only when the current thread owns the block
   (`TickThread.isTickThreadFor(level, worldPosition)`) or the minecart (`isTickThreadFor(entity)`);
   unknown `BaseCommandBlock` subclasses never own. A chain (`executeChain`) stops at the first step the
   region does not own, before reading that block. The vanilla gamerule `command_blocks_work` (default `true`)
   still applies; 26.2 has no `enable-command-block` server property.
2. **Region scope.** The dispatch runs inside a thread-local `CommandBlockPolicy.Scope`. Outside it nothing
   changes for players, console or plugins.
3. **Region-local selectors.** In a scope, `EntitySelector` keeps only entities the current region owns,
   checked *before* the selector's own predicate and before the bounding-box test of `dx/dy/dz` selectors,
   so a foreign entity's state is never read. Name and
   UUID lookups return nothing for a foreign entity. Within its region a command block's `@a`/`@e`/`@p`
   behave as vanilla; `@p` picks the nearest player *in this region*.
4. **Block targets outside the region fail cleanly.** In a scope, `executeAtPos[WithRadius]`, `fill`, `fillbiome`,
   `clone` (source and destination) and `data ... block` refuse a target whose chunks another region owns, with
   the command error `Command block: cannot <action> outside the region that owns the command block`
   (a normal Brigadier failure: success count 0, no stack trace). Players and console keep the engine's
   queue-to-owner routing; command blocks are narrowed because nobody watches the delayed result and
   comparator output would report success for work that has not happened.
   A command block never hands a mutation to a later callback: `fill` and `fillbiome` (which load chunks and
   mutate in a completion callback) require the whole footprint, `fill`'s 32-block physics buffer included,
   to be this region's and the mutated chunks to be loaded, then run the mutation now, on this thread, inside
   the scope; otherwise `cannot <action> outside the region…` or `cannot <action> in chunks that are not
   loaded`. `clone` checks source and destination with its 32-block buffer, so the engine's
   `loadOrRunAtChunksAsync` takes its run-now branch. `executeAtPos[WithRadius]` and `data … block` target
   owned chunks only, so they also run now.
5. **Global state keeps the engine's route.** Commands the engine already sends to the global region keep
   doing so (the console path). Their result is asynchronous, so a command block reports success when the
   work is queued, as console does.
6. **Late output goes to the log.** Output that reaches the command block after its command finished (source
   closed) or from another region's thread (a global-region command's callback) is not written into the block;
   it goes to `CommandBlockDiagnostics`, a thread-safe sink that logs a WARN line with the block's dimension
   and position (minecart: UUID), the command and the message, once per block and command (bounded at 4096
   keys, then counted only). A closed source still accepts failures when the switch is on so they reach the
   sink. Output from the owning thread while the command runs is recorded as vanilla does. Off, the
   inherited `threadCheck` runs unchanged.
7. **Conditional blocks.** `markConditionMet` reads the predecessor block, block entity and success count; a
   predecessor another region owns reads as "condition not met" without being touched.
8. **Edits.** The GUI opens (permission checks unchanged). `handleSetCommandBlock` refuses a block position the
   player's region does not own; `handleSetCommandMinecart` refuses a minecart another region owns.

Command-block minecarts follow the same rules (they share `performCommand`).

### Known gaps (not hidden)

- **Clean refusals** are exactly the hooked paths in rules 3, 4, 7 and 8 above: a command error (or "condition
  not met"), no exception.
- **Synchronous read paths not hooked and not audited yet**: `execute if|unless block[s]`, `execute store …
  block`, `loot … mine`, block predicates, `clone`'s `masked/filtered` reads are covered only through the
  footprint check above. What such a read of another region's block does from a command block's region
  thread depends on the engine's own checks at that call site (an ownership exception that Paper reports as
  `command.failed` with an ERROR log line, or an unsynchronised read); see Evidence for the one case run.
  These need a per-path audit before the switch can be called safe for arbitrary command-block content.
- Anything dispatched synchronously inside the scope (a plugin command run by a command block that calls
  `Bukkit.dispatchCommand`) inherits the region-local selectors and block-target refusal.
- LIVE switch caveat: while off, a repeating block's tick is dropped without rescheduling (inherited
  behaviour). After switching on it stays idle until re-armed: redstone change, re-placement, GUI save or
  chunk reload.
- No soak, no real-client run, no performance claim. Functional runs only.

## Inherited-guards ledger row

| Guard | Inherited from | Owned replacement | Switch | Status |
| --- | --- | --- | --- | --- |
| Command blocks / command-block minecarts never execute (`ServerLevel.isCommandBlockEnabled`, `CommandBlock.tick`, `useWithoutItem`, `BaseCommandBlock.performCommand`, `MinecartCommandBlock.interact`, `handleSetCommandBlock`, `handleSetCommandMinecart`) | Canvas + Folia | `CommandBlockPolicy` (MC 0055): execute on owning region only, region-local selectors (ownership before bbox) and block targets in a command-block scope, deferred fill/fillbiome/clone run now on the owning region or are refused, foreign conditional predecessor = not met, late/foreign output to a once-per-block log sink, edits ownership-checked | `aurora.world.command-blocks` (LIVE, default off = inherited) | replaced behind default-off switch; functional evidence only |

## Evidence

Functional, shared machine, not certified (jar `6a6b2823…`, worktree build at `c0ab0cc4` + this change):

- `opus-cmdblock-on-4` (flag on): repeating block in forceloaded chunk 0,0 running
  `scoreboard players add @e[type=armor_stand,tag=clk,limit=1] clk 1` → `[@: Added 1 to [clk] for Armor Stand (now 2318)]`,
  console `Armor Stand has 5264 [clk]` then `has 5671 [clk]` 20 s later (about 20/s). Same-region chain
  (impulse `setblock 8 125 4 stone` → conditional `say localok` → `say localdone`) printed both. Cross-region
  chains (target region around chunk 187,187, forceloaded): `setblock 3000 125 3000`, `fill 2990..3000`, and
  `scoreboard players add @e[tag=far]` each printed only the unconditional `*done` line, never the conditional
  `*ok` line; the far armor stand's score stayed `none is set`. 0 `ERROR`/`Exception` lines in the log. Clean
  stop (exit 0).
- `opus-cmdblock-off-1` (flag off, same jar): `command_blocks_work` = `true`, the same repeating block and an
  impulse `say` block never ran (score `none is set`, no `[@]` line). Clean stop.
- Review round (Codex findings 1-5), jar `4b11ec6e…`, `opus-cmdblock-on-5` (flag on, `command_block_output` off,
  forceload -64..64 and 3000,3000): clock `Armor Stand has 1001128 [clk]` then `1001169`; `localok/localdone`;
  cross-region `setblock`, `@e[tag=far]`, `fill`, and the `dx/dy/dz` box selector `@e[tag=far,x=2990,…,dx=20,…]`
  printed only `*done`; local `fill 8 126 4 10 126 6` (32-block buffer inside the region) printed
  `localfillok/localfilldone`; a local box selector added 1000000 to the near stand; the far stand stayed
  `none is set`. `execute if block 3000 125 3000 minecraft:air` (unhooked read path) printed only `crossreaddone`
  and logged nothing: it did not succeed, mechanism not audited. `forceload remove 9000 9000` (global-region
  route) produced `[SourbyCraft] Command block at minecraft:overworld 8, 120, 18 running 'forceload remove 9000
  9000': deferred output: No chunks were removed from force loading (logged once per command block and command)`.
  0 ERROR/Exception lines. `opus-cmdblock-off-2` (flag off, same script): nothing ran, 0 errors. Both stops exit 0.
- Console `scoreboard players add #t clk 1` fails with `Holder target "#t" must be an entity` (inherited, all
  sources); console `data get block ...` throws an inherited NPE in `BlockDataAccessor` on the global thread
  (unrelated to this change; noted for the inherited-guards ledger).

See also the coordination entry `### opus-cmdblock — task_ab3bda9af0b220f4` for the test-server run names, jar
hash and log lines. Unit tests: `CommandBlockPolicyTest`, `CommandBlockPatchShapeTest`,
`AuroraConfigTest` (command-block cases).
