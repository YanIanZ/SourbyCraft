# Owned command blocks on two regions: agent test-server evidence (2026-10-21)

> **Role:** the Build 48 "Owned command blocks" gate evidence ([release doc](../releases/26.2-build-48.md) row: "agent test-server run with command blocks in two regions, no ownership violation"). Design and contract: [command-blocks.md](../architecture/command-blocks.md). **Worker:** `claude-cmdblock` (re-run from HEAD, independent of the `opus-cmdblock-*` runs listed in that doc).
>
> Functional evidence only: shared Apple M2 machine (load average 10-12 during the runs), no real client, no soak, not a baseline, no performance claim.

## Verdict

**PASS for the stated run evidence** (command blocks in two regions, no ownership violation, switch-off shows them disabled). Not a qualification: the limits at the end stay open. The row's other items (unit tests, ledger row) were not re-run here; the ledger row exists (`inherited-guards.md` row 1). The default-on/off owner decision is a separate gate.

| Check | Result |
| --- | --- |
| Two distinct regions | `/perf region world 0 0` = `world #1 region #4`; `world 3000 3000` = `region #5`; `20 20` = #4; `3030 3030` = #5 (stable for the whole run) |
| Flag on, own-region work (entity + block + chain + repeating + minecart) | all ran, in both regions |
| Flag on, work aimed at the other region (19 foreign-aimed command types per direction: cases 10-24, 31, 32, chain link 4, 70; both directions) | 0 mutated anything: refused with a command error, or matched nothing; offline world scan after stop found none of the marker blocks/entities |
| Ownership violations in logs | 0 (no ownership/guard/thread-check/"deferred output" line, 0 `WARN` after boot, 0 exceptions from a command block) |
| Flag off | 64 command blocks placed and powered in both regions: 0 executed (SuccessCount 0, LastOutput empty, no score set, no entity or block change) |

## Build and configuration

- Jar: `build/libs/SourbyCraft-slim.jar`, SHA-256 `403e199f0aa4413909d53f598e566617d0784333564701ccc21685b1e0f64745`, 41,076,621 bytes, built 2026-10-21 22:43 with `AGENT_NAME=claude-cmdblock bash scripts/gradle_locked.sh slimServerJar` (`BUILD SUCCESSFUL in 1m 2s`; the harness's own `--rebuild-jar` refused because it still points at the removed scratchpad `gradle_locked.sh`, so the jar was built by hand with the repo wrapper).
- Tree at build: HEAD `0ea396aa` plus docs the PM committed during the build; `/ver` printed `Build 48-dirty - Aurora Nexus (DEV, 26.2@ce23739)`, patch hashes `Minecraft features: abaa8dccbd7a`, `Paper features: 6890c2073d69`. The harness recorded `git.head` `ce23739e` (runs 1 and 2: on-1, off-1) and `f402d5c2` (on-2, on-3), `tracked_changes: []`. `git diff 0ea396aa f402d5c` touches docs, `README.md`, `AGENTS.md` and `build-data/inherited-guards.json` only (no source or patch), so the jar's code is the same across those commits. The staged `0066` patch and datapack-function work that appeared in the tree later is NOT in this jar (run.json: `jar.older_than_head_commit` false for on-1/off-1, true for on-2/on-3 only because of the later docs commits).
- Config: `sourbycraft_config/aurora.toml` given with `--aurora-toml`:
  - on: `[aurora.world]` / `command-blocks = true` (key `aurora.world.command-blocks`; default `false`; LIVE per the doc, but not toggled at runtime here: a fresh boot per mode).
  - off: `command-blocks = false`.
  - `server.properties`: harness defaults (`view-distance=4`, seed 440044, `online-mode=false`). No `enable-command-block` key exists in 26.2 (confirmed: the generated file has none); the gate is the gamerule `command_blocks_work` = `true` (read back in the off run).
- Server: `scripts/agent_testserver.py start --name <run> --aurora-toml <file> --max-lifetime 3000 --view-distance 4`, slot 1, boot 10-22 s, every stop `exit_code 0`, `lock_released true`, `jvm_still_running false`.

## Runs

| Run | Flag | Purpose | Log |
| --- | --- | --- | --- |
| `claude-cmdblock-on-1` | on | main matrix, both regions, `drive.py` | `on-1/latest.log`, `on-1-transcript.txt`, `on-1-nbt.json`, `on-1-worldscan.json` |
| `claude-cmdblock-off-1` | off | same script, nothing may run | `off-1/...` |
| `claude-cmdblock-on-2` | on | `execute if/unless/store ... block` probes, `execute as @e` across regions, other dimension, minecarts, local fill/clone | `on-2/...` |
| `claude-cmdblock-on-3` | on | local `data modify/get block` control | `on-3/...` |

Raw material: `/Volumes/Ext/DevCache/agent-tmp/sourbycraft/cmdblock-2026-10-21/` (server logs, run.json, hardware.json, console transcripts, world dumps, `tools/` with the driver scripts and the offline NBT readers). Run directories: `/Volumes/Ext/Sourby/agent-testservers/claude-cmdblock-{on-1,off-1,on-2,on-3}/`.

## Method

Region A = forceloaded chunks -4..4 around spawn (`execute in minecraft:overworld run forceload add -64 -64 64 64`); region B = chunks 183..191 around block (3000,3000) (`forceload add 2936 2936 3064 3064`). Per region one armor stand with a tag (`near` in A, `far` in B) carries all scores (fake players do not work on this engine), plus a `victim_*` stand (tp/kill target), a `mover_*` stand and, from the block at region A (10..32,125,10), 19 impulse command blocks written with `setblock X 125 Z minecraft:command_block{Command:'...',auto:0b,TrackOutput:1b}` and armed with `setblock X 126 Z minecraft:redstone_block`. Region B got the mirrored set (coordinates +3000, targets pointing at A). Repeating blocks (`repeating_command_block`, `auto:1b`), a 6-link chain (`command_block[facing=east]` head, `chain_command_block` links, conditional ones with `conditional=true`) and late "count" blocks complete the set. The harness `cmd` brackets with `help` markers and returns nothing for commands whose answer arrives on a region thread (exit 1, "no output"), so those were sent with `--raw`.

Readback, three independent ways:

1. Console `scoreboard players get @e[tag=near,limit=1] <objective>` and `data get entity @e[tag=...] Pos` (`--raw`).
2. Offline, after `save-all flush` and `stop`: `tools/mcanbt.py` parses the anvil files and prints every command block's `SuccessCount`, `conditionMet` and `LastOutput`; `tools/worldscan.py` lists the block palette names per chunk and all tagged entities. This is the "data get ... SuccessCount/LastOutput" evidence: console `data get block` cannot be used (below).
3. Server log: all lines grepped.

Console `data get block ...` throws `NullPointerException: Cannot read field "capturedBlockEntities" because the return value of "Level.getCurrentWorldData()" is null` (`BlockDataAccessor` via `Level.getBlockEntity`, console route, inherited, reproduced with the flag off). Console `execute if block ...` always answers `Cannot test a block cross-region` (global thread). Both are not command-block paths; a command block's own `data get block` works (on-3).

## Results, flag on (run on-1, region A / region B symmetric)

SC = `SuccessCount`, LO = `LastOutput` read from the saved block entity. Every row held in both directions (A to B and B to A); the table shows A's block and the B mirror gave the same SC/LO text.

| # | Command (A) | Result |
| --- | --- | --- |
| 01 | `scoreboard players add @e[tag=near,limit=1] t 1` | SC 1; `near` t = 1 (B's `far` t = 1 from its own block) |
| 02 | `summon armor_stand 12 125 12 {Tags:["spawn_a"]}` | SC 1; stand present at (12.5,125,12.5) in the entity file |
| 03 | `tp @e[tag=victim_a,limit=1] 8 126 8` | SC 1; `data get entity` Pos `[8.5d, 126.0d, 8.5d]` |
| 04 | `setblock 14 125 30 emerald_block` | SC 1; emerald in the chunk palette (local) |
| 10 | `scoreboard players add @e[tag=far] x 1` (foreign selector) | SC 0; LO `argument.entity.notfound.entity`; `x` none on both stands |
| 11 | `execute as @e[tag=far] run scoreboard players add @s x 100` | SC 0, LO empty (no target); `x` none |
| 12 | `tp @e[tag=victim_b,limit=1] 0 150 0` | SC 0; LO entity not found; `victim_b` Pos unchanged `[3008.5d,126d,3008.5d]` (its own region's tp) |
| 13 | `kill @e[tag=victim_b]` | SC 0; LO entity not found; victim alive at end |
| 14 | `setblock 3000 125 3000 gold_block` | SC 0; LO `Command block: cannot target blocks outside the region that owns the command block` |
| 15 | `fill 2990 125 2990 3000 125 3000 lapis_block` | SC 0; LO `cannot fill blocks outside the region that owns the command block` |
| 16 | `summon armor_stand 3002 125 3002 {Tags:["leak"]}` | SC 0; LO `cannot target blocks outside ...` |
| 17 | `execute positioned 3000 125 3000 run setblock ~ ~ ~ diamond_block` | SC 0; LO `cannot target blocks outside ...` |
| 18 | `execute positioned 3000 125 3000 run summon armor_stand ~ ~ ~ {Tags:["leak"]}` | SC 0; LO `cannot target blocks outside ...` |
| 19 | `clone 3000 124 3000 3001 124 3001 5 130 5` | SC 0; LO `cannot clone from blocks outside ...` |
| 20 | `clone 5 130 5 6 130 6 3000 130 3000` | SC 0; LO `cannot clone to blocks outside ...` |
| 21 | `execute if block 3000 125 3000 air run scoreboard ... t 1000` | SC 0, LO empty; `t` stayed 1 (mechanism in on-2, E1-E3) |
| 22 | `data modify block 3010 125 3025 CustomName set value "leak"` | SC 0; LO `Target not in same region` (the engine's own message, not a 0055 string); chest has no CustomName in the saved chunk |
| 23 | `fillbiome 2990 125 2990 3000 125 3000 desert` | SC 0; LO `cannot fill biomes outside ...` |
| 24 | `execute positioned 3000 125 3000 as @e[tag=far,distance=..10] run scoreboard ... x 10000` | SC 0, LO empty; `x` none |
| 30 | repeating `scoreboard players add @e[tag=near,limit=1] clk 1` | ran every tick: `clk` 5227 to 5437 in 10 s in A (about 21/s), 4788 to 4986 in B (about 20/s); region TPS 20.0 |
| 31 | repeating `setblock 3000 126 3000 gold_block` | refused every tick (SC 0, LO `cannot target blocks outside ...`); no log line per tick |
| 32 | repeating `scoreboard players add @e[tag=far] x 1` | refused every tick (entity not found); `x` none |
| 40-41 | chain: head `chain 1`, link `chain 10`, conditional link `chain 100`, link `setblock <foreign> gold_block`, conditional link `chain 1000`, link `chain 10000` | `chain` = 10111 in both regions: links 1, 10, 100 ran, the foreign `setblock` link failed cleanly (SC 0), the conditional link after the failure was not run (`conditionMet` 0), the following unconditional link ran |
| 50 | `execute store result score @e[tag=near,limit=1] cnt if entity @e[type=armor_stand]` | `cnt` = 4 in A (near, victim_a, mover_a, spawn_a) and 4 in B: each block sees only its own region's four stands (vanilla semantics would count 8) |
| 51 | same with `@e` | `cnt2` = 130 in A, 75 in B (region-local entity counts) |
| 60 | `tp @e[tag=mover_a,limit=1] 3000 126 3000` (local entity to a position in the other region) | SC 1; `mover_a` ended at (3000.5,126,3000.5), saved in B's entity file `r.5.5`: the entity moved through the engine's entity-teleport route (it is an action on a stand A owned; nothing of B's was mutated). Not covered by the doc's block-target rule; recorded as observed behaviour |
| 70 | `execute store success score @e[tag=near,limit=1] succ run setblock 3001 125 3001 iron_block` | SC 0; `succ` = 0 stored; no iron block |

Offline world scan after stop (on-1): `minecraft:gold_block`, `lapis_block`, `diamond_block`, `iron_block` appear in no chunk palette of the overworld; `emerald_block` exactly in the two local chunks (0,1) and (188,189); no entity tagged `leak`; the console `execute if entity @e[tag=leak]` said `Test failed` twice (during and after the run); `far` and `near` score `x` none; positions of `near` `[5.5,125,5.5]`, `far` `[3005.5,125,3005.5]` unchanged.

## Results, extra probes (run on-2, flag on)

| Probe | Result |
| --- | --- |
| E1 `execute store result score ... r1 if block 3000 125 3000 air` | SC 0; LO `Cannot test a block cross-region`; `r1` = 0 |
| E2 same with `store success` | SC 0; LO `Cannot test a block cross-region`; `r2` = 0 |
| E3 `execute unless block 3000 125 3000 stone run ...` | SC 0, LO empty, `r3` none: a foreign read is neither true nor false, the condition fails closed (silently under `unless`) |
| E4 `execute if block 5 125 5 air run ...` (local control) | SC 1; `r4` = 1 |
| E5 `execute store result ... if entity @e[tag=far]` | LO `commands.execute.conditional.fail`; `r5` = 0 |
| E6 `execute as @e[type=armor_stand] run scoreboard players add @s tt 1` in A and `... tt 10` in B | `tt` = 1 on both A stands, 10 on both B stands (never 11): each block touched its own region's stands only |
| E7 `execute at @e[tag=far] run setblock ~ ~ ~ gold_block` | SC 0; no gold block anywhere |
| E8 `execute in minecraft:the_nether run setblock 0 125 0 stone` | SC 0; LO `argument.pos.unloaded` (the nether chunk was not loaded; ownership of a loaded foreign dimension was not exercised) |
| E11 local `fill 20 126 40 22 126 42 glass` | SC 1, `commands.fill.success` 9 |
| E12 local `clone 20 126 40 22 126 42 30 126 40` | SC 1, `commands.clone.success` 9 |
| E13 `execute positioned 3005 125 3005 as @e[distance=..5] run ...` (A, foreign position) | SC 0; no score added |
| Minecarts (activator rail powered by an adjacent redstone block, stone support under it; the first attempt had no support so the rails dropped and the minecarts never ran, redone) | `command_block_minecart` in A with `scoreboard players add @e[tag=near,limit=1] mc 1`: `mc` = 71 after about 8 s; in B with `... mcb 1`: 42. Minecart in A with `... @e[tag=far] mcx 1`: `mcx` none. Minecart in A with `setblock 3000 125 3000 gold_block`: no gold block in the world scan. All four minecarts alive. |
| (on-3) local `data modify block 10 125 25 CustomName set value "ok_local"` then `data get block 10 125 25 CustomName` | SC 1 each; saved chest `CustomName` = `ok_local`; the on-2 attempt at the same control failed `commands.data.block.invalid` only because its chest was never placed (setup error, repeated in on-3) |

## Results, flag off (run off-1, same script and objectives)

- `gamerule command_blocks_work` = true, so the switch alone is the gate.
- 64 command blocks (per region: 19 impulse, 3 repeating, 1 chain head + 5 chain links, 4 late impulse) placed and powered (redstone blocks) in both regions, chain heads armed. Reads at 22:54: `t`, `x`, `clk`, `chain`, `cnt`, `cnt2`, `succ` all `none is set` on both stands, `clk` still none 10 s later.
- All 64 saved command-block entities: `SuccessCount` 0, `LastOutput` absent (the chain links also `conditionMet` 0, never evaluated).
- Entities unchanged: `victim_a` `[6.5,125,5.5]`, `mover_a` `[7.5,125,5.5]`, `victim_b`/`mover_b` likewise; no `spawn_a`/`spawn_b` stand; no gold/lapis/diamond/iron/emerald block in the palette scan.
- Log: 0 exceptions from command blocks. The only error is the same console `data get block` NullPointerException as with the switch on.

## Log grep (all four runs)

`ERROR|Exception|Caused by`: on-1 4 lines and off-1 2 lines, all the single console `data get block` stack trace (2 traces on-1, 1 off-1) on a `Folia Region Scheduler Thread`; on-2, on-3: 0. Case-insensitive `violation|guard|thread check|not owned|wrong thread|deferred output`: 0 lines in every run. `WARN` after boot: 0 (the once-per-block `CommandBlockDiagnostics` sink never fired: no late or foreign-thread output occurred in this scenario). 0 region crashes; region TPS 20.0 throughout; stops clean.

## Limits and findings (not hidden)

- Not exercised: real-client GUI and edit packets (`handleSetCommandBlock` / `handleSetCommandMinecart` ownership checks), minecart edit, soak, a runtime toggle of the LIVE switch (including the idle repeating block after switching on), a chain or `fill` straddling a region border, a loaded foreign dimension, `forceload`-style global-route output (covered by the earlier `opus-cmdblock-on-5` run, not repeated), plugin `dispatchCommand` from a command block.
- `execute if|unless|store ... block` on a foreign position (earlier "mechanism not audited"): now observed: `if`/`store` fail with the engine's `Cannot test a block cross-region` (LastOutput red text), `unless` fails silently; none of them reads the foreign block as present or absent. Still a read path owned by the engine's check, not by 0055.
- A command block can move an entity it owns to a position in another region (`tp @e[tag=mover_a] 3000 126 3000`, case 60); the doc's decision list (block targets refused, selectors region-local) does not mention that entity route. Not a mutation of foreign state, but worth one sentence in `command-blocks.md`.
- A foreign-position `data modify block` is refused by the engine with `Target not in same region`, not by a 0055 string (still clean).
- Harness: `agent_testserver.py --rebuild-jar` is broken (points at the removed scratchpad `gradle_locked.sh`); `cmd` without `--raw` returns exit 1 for commands whose output is logged by a region thread.
- Inherited, outside this change: console `data get block` NPE; console `execute if block` always "cross-region".

**Status 2026-10-25 (PR #62 docs pass).** Follow-ups landed after this run, which used a jar without them: MC
feature patch 0069 (data-command threading, in every tag since `v26.2-r48`) builds the console `data … block`
accessor without touching the block entity and hands the command to the owning region, so the console NPE above is
gone in the code (not re-run). A region caller (player, command block) now gets 0069's own error for a foreign block
(`Cannot access block data outside the owning region; …`) instead of the engine's `Target not in same region` seen
in case 22. The case-60 entity route and the E1-E3 observations are now in
[command-blocks.md](../architecture/command-blocks.md). PR #62 changed no command-block code.

## Reproduce

```sh
cd /Volumes/Ext/Sourby/SourbyCraft
D=/Volumes/Ext/DevCache/agent-tmp/sourbycraft/cmdblock-2026-10-21
AGENT_NAME=<you> bash scripts/gradle_locked.sh slimServerJar
python3 scripts/agent_testserver.py start --name cb-on --aurora-toml $D/aurora-on.toml --max-lifetime 3000 --view-distance 4
python3 $D/tools/drive.py cb-on out.txt          # on-1/off-1 matrix (main matrix; chain-head arm uses z+16)
python3 $D/tools/drive2.py cb-on out2.txt        # on-2 probes
python3 scripts/agent_testserver.py stop --name cb-on
python3 -I $D/tools/mcanbt.py /Volumes/Ext/Sourby/agent-testservers/cb-on/world/dimensions/minecraft/overworld
python3 -I $D/tools/worldscan.py /Volumes/Ext/Sourby/agent-testservers/cb-on/world/dimensions/minecraft/overworld
```

`drive.py` as run in on-1 had the chain-head redstone at the wrong z (typo); the chain was armed by hand afterwards (`setblock 20 126 16` / `3020 126 3016 redstone_block`, transcript "SUPPLEMENT 1") and the script was fixed before off-1. The minecart retry and the `gamerule command_block_output false` supplements are in the transcripts as noted.
