# SuperiorSkyblock2 2026.3 adapter against the real jar — 2026-10-22

Build 48 gate "Bridge fixes on a server", item "SSB2 2026.3 adapter with the real jar"
([26.2-build-48.md](../releases/26.2-build-48.md), [SUPERIORSKYBLOCK-BRIDGE.md](SUPERIORSKYBLOCK-BRIDGE.md)).
Agent `opus-gate48-ssb2`, worktree `sc-worktrees/opus-gate48-ssb2`, base commit `6176d517` (clean tree, no code change).

**Update (same day, agent `opus-harness-modules`, section 5): with the SSB-AuroraWorlds module installed through the new
harness option `--plugin-file`, the unported 2026.3 jar enables in bridge `safe` mode, and the one owner handoff the
adapter can produce happens with 0 rejected operations, 0 violations and no quarantine, also with
`sync-route = "global"`. This is headless startup evidence only; island operations with players are still owner-only.**
The first verdict follows unchanged, for the history.

**Verdict (first runs, without the module): BLOCKED on a server, not by the bridge.** The unit-level accept path passes against the real jar
classes again (4/4). On a server the unported 2026.3 jar never reaches the code the adapter handles: its
`onEnable` dies with `NoSuchMethodError` on `LevelTicks.<init>(LongPredicate)` (an NMS signature this
region-threaded server does not have), and SuperiorSkyblock2 then calls `Bukkit.shutdown()`. `SpawnIsland`
is never constructed, so the spawn callback the adapter routes is never scheduled. The owner's Folia-ported jar fails
the same way. Both need the SSB-AuroraWorlds module, which skips that NMS call (section 3), and the agent harness cannot
install a module jar (section 4). The gate item stays open.
The adapter is neither confirmed nor refuted on a server.

## Jars (local, not downloaded)

| Jar | SHA-256 | Source |
| --- | --- | --- |
| `/Volumes/Ext/Sourby/ssb/jars/SuperiorSkyblock2-2026.3.jar` ("upstream", unported) | `007bee17a589ba2ee74b603a9bc0cc2d900182997b71ca4657b08b84c64ff597` | Local build of upstream SuperiorSkyblock2 commit `868bebf` (`jars/SOURCE-COMMIT.txt`, `ssb/ssb-build.log`). This is not a BG-Software release download. `plugin.yml`: version `2026.3`, no `folia-supported`. |
| `/Volumes/Ext/Sourby/ssb/jars/folia/SuperiorSkyblock2-2026.3.jar` (owner's Folia port) | `19e559cb15a7710809cd4328618a10ddf3c77fcd298630116eaacdc68fb4f35a` | Local build of `~/Sourby/ssb/SSB2` branch `folia`, commit `5818885`. `plugin.yml`: `folia-supported: true`. |

Both are the same files the 2026-10-20 unit run used (hashes unchanged).

## 1. Env-gated unit test against the real jar

```sh
SOURBY_TREE=$PWD AGENT_NAME=opus-gate48-ssb2 \
SOURBY_SSB2_JAR=/Volumes/Ext/Sourby/ssb/jars/SuperiorSkyblock2-2026.3.jar \
SOURBY_SSB2_FOLIA_JAR=/Volumes/Ext/Sourby/ssb/jars/folia/SuperiorSkyblock2-2026.3.jar \
/Volumes/Ext/Sourby/SourbyCraft/scripts/gradle_locked.sh :sourbycraft-server:test \
  --tests 'dev.iyanz.SourbyCraftTestSuite' --tests 'dev.iyanz.sourbycraft.bridge.*'
```

(The server test task only includes `*TestSuite` classes, so a plain `--tests ...SuperiorSpawnRealJarTest`
finds nothing; naming the suite plus the package filter works.) BUILD SUCCESSFUL, 4 m 23 s.

| Class | tests | skipped | failures | errors |
| --- | --- | --- | --- | --- |
| `SuperiorSpawnRealJarTest` | 4 | 0 | 0 | 0 |
| `BridgeReviewTest` | 11 | 0 | 0 | 0 |
| `BridgeTargetRoutingTest` | 9 | 0 | 0 | 0 |
| whole `dev.iyanz.sourbycraft.bridge` package | 120 | 0 | 0 | 0 |

`SuperiorSpawnRealJarTest` cases: `upstreamJarSpawnCallbackResolvesToTheSpawnChunk`,
`upstreamJarSpawnWithoutWorldIsRefused`, `upstreamJarStaticSettingsCallbackIsNotInspected`,
`foliaPortedJarCallbackIsNotTheAuditedShape`. The unit result of 2026-10-20 holds on `6176d517`.

## 2. Agent test server with the real unported jar

Jar: `slimServerJar` from this worktree, SHA-256 `dbefd9d05d1c4c9315631fbfbd8d7636a696d6b5be4b2ec7de76f6ef3b0c0b25`
(`run.json`: git head `6176d517`, dirty false, `older_than_head_commit` false). `/ver` banner: `SourbyCraft Build 48`.
`aurora.toml` = `docs/config-examples/aurora.toml` with only `[aurora.bridge] mode = "safe"` (RESTART_REQUIRED);
every other harness default unchanged (`minecraft:normal`, seed 440044, view 4 / sim 5, 2048 MiB, no clients).

```sh
python3 scripts/agent_testserver.py start --name opus-gate48-ssb2-1 --max-lifetime 1500 \
  --aurora-toml <scratch>/aurora-safe.toml \
  --config-file plugins/SuperiorSkyblock2-2026.3.jar=/Volumes/Ext/Sourby/ssb/jars/SuperiorSkyblock2-2026.3.jar
# -> agent_testserver: server is not running (status exited)
```

Run dir `/Volumes/Ext/Sourby/agent-testservers/opus-gate48-ssb2-1/` (slot 2; `run.json`: status `exited`,
exit code 0, stop reason "jvm exited by itself"). Raw excerpts from `logs/latest.log`:

```
[06:18:36] [ServerMain/WARN]: [SourbyCraft] Aurora Bridge admitted SuperiorSkyblock2 (no folia-supported/canvas-supported). Its scheduler tasks are routed by ownership; it is not qualified for region threading.
[06:18:43] [Server thread/WARN]: [SourbyCraft] 1 plugin(s) do not declare folia-supported or canvas-supported and are loaded through the Aurora Bridge (aurora.bridge.mode = safe): SuperiorSkyblock2 [legacy-scheduler]
[06:18:57] [Server thread/INFO]: [SuperiorSkyblock2] Enabling SuperiorSkyblock2 v2026.3
[06:18:57] [Server thread/ERROR]: [SuperiorSkyblock2] An unexpected error occurred while enabling the plugin:
[06:18:57] [Server thread/ERROR]: [SuperiorSkyblock2] java.lang.NoSuchMethodError: 'void net.minecraft.world.ticks.LevelTicks.<init>(java.util.function.LongPredicate)'
	at com.bgsoftware.superiorskyblock.nms.v26_2.world.BlockLevelTicksTracker.<init>(BlockLevelTicksTracker.java:28)
	at com.bgsoftware.superiorskyblock.nms.v26_2.NMSWorldImpl.listenBlockStateChanges(NMSWorldImpl.java:142)
	at ...external.worlds.DefaultWorldLoadListener.onWorldLoad(DefaultWorldLoadListener.java:44)
	at ...external.worlds.WorldsProvider_Default.prepareWorlds(WorldsProvider_Default.java:58)
	at ...SuperiorSkyblockPlugin.onEnable(SuperiorSkyblockPlugin.java:251)
[06:18:57] [Region shutdown thread/INFO]: Stopping server
[06:18:58] [Server thread/ERROR]: [ChunkHolderManager] Exception when flushing regions in world 'minecraft:overworld'
java.nio.channels.ClosedChannelException  (same for minecraft:the_nether)
[06:18:59] [Server thread/INFO]: Done (24.031s)! For help, type "help"
[06:18:59] [Thread-4/ERROR]: [SuperiorSkyblock2] Detected crash. SuperiorSkyblock will attempt to save data...
```

No bridge violation, quarantine or guard line was logged (`rg "violation|quarantin|GuardViolation"`: none).
`/plugins` and `/perf plugins` could not be captured: the JVM had exited before the harness's console check.

### Why the adapter path is unreachable

- On this branch `LevelTicks` has only `LevelTicks(LongPredicate, ServerLevel, boolean)`. The extra
  parameters come from the region-threading engine baseline (former Folia), not from a SourbyCraft feature patch.
  The jar's `nms/v26_2/world/BlockLevelTicksTracker` calls `super(serverLevel::isPositionTickingWithEntitiesLoaded)`.
  This is binary NMS incompatibility, so the bridge cannot route or adapt around it.
- `WorldsProvider_Default` always sets `LISTEN_BLOCK_CHANGES` for SuperiorSkyblock's own worlds (source `868bebf`,
  `WorldsProvider_Default.java:37`). No plugin setting avoids it without changing the workload.
- `SpawnIsland` is created later: `onEnable` -> `reloadPlugin(STARTUP)` -> `GridManagerImpl` ->
  `BukkitExecutor.sync(this::updateSpawn)` -> `new SpawnIsland()` (`GridManagerImpl.java:141-146`). `prepareWorlds`
  fails first (`SuperiorSkyblockPlugin.java:251`; `NoSuchMethodError` is not the `RuntimeException` that block catches), and the outer
  `catch (Throwable)` at the end of `onEnable` logs "An unexpected error occurred while enabling the plugin" and calls `Bukkit.shutdown()`.

So acceptance cases 1, 5 and 6 of SUPERIORSKYBLOCK-BRIDGE.md cannot be run with this jar on Build 48.
Island create/teleport/delete was not attempted: the plugin never enabled. Even with an enabled plugin, SSB2 has
no console command that creates an island. Creation needs a player, and real players are owner-only.

## Findings

1. **SSB2 2026.3 (unported and Folia port) does not enable on SourbyCraft without the SSB-AuroraWorlds module.** This is not a bridge defect. With
   `mode = "off"` it is refused at admission. With `safe` it is admitted, then breaks on NMS. The
   hash-pinned adapter (`SuperiorSpawnTaskOwner`) can only fire with the module installed. Options for the
   owner: (a) keep the adapter and run section 4 (module installed) to close the gate item, or (b) remove the adapter and patch 0010's SSB2-specific part, since the Folia port
   (`folia-supported: true`) bypasses the bridge entirely. No code change was made here. This is an owner/PM decision.
2. **Engine: plugin-initiated `Bukkit.shutdown()` during startup races the server thread** (separate from SSB2).
   The region shutdown thread halts chunk I/O while the server thread continues `initServer` ->
   `saveEverything`. Result: 2 `ClosedChannelException` flush errors (overworld, nether) and a `Done (` line logged
   after "Stopping server". The JVM still exited 0 and every world reported "All chunks are saved". The run does not
   show data loss, but world integrity was not checked. Not in `bridge/**`, so it is not fixed here. Proposed fix
   (engine, needs its own test; not verified against Paper's behaviour): in `DedicatedServer.initServer`, skip the
   final `this.saveEverything(false, true, true)` (`DedicatedServer.java:391`) and return `false` when a stop was
   requested during `enablePlugins(POSTWORLD)`, so `runServer` does not log `Done (`.

## Not covered

- The adapter's server path: real `SpawnIsland` constructor, `BukkitExecutor`, bridge dispatch to the owning
  region, the actual biome read, another world, an initially unloaded spawn chunk, island subclass, missing
  resource, cancel/disable/merge lifecycle (cases 1, 4-rest, 5, 6).
- Any island operation (create/teleport/delete), `/plugins`, `/perf plugins` telemetry with SSB2 enabled.
- Real players, multi-player concurrency, panel/VPS: owner-only. No performance figures were taken; these are
  functional runs on a shared laptop (another agent's server held the other slot).

## 3. Contrast run: the owner's Folia-ported jar (same server jar, same settings)

`opus-gate48-ssb2-2`, slot 2, plugin `jars/folia/SuperiorSkyblock2-2026.3.jar` (`19e559cb…`). There is no "Aurora Bridge
admitted" line, which is expected for `folia-supported: true`. It fails the same way:

```
[06:21:41] [Server thread/INFO]: [SuperiorSkyblock2] Enabling SuperiorSkyblock2 v2026.3
[06:21:42] [Server thread/ERROR]: [SuperiorSkyblock2] java.lang.NoSuchMethodError: 'void net.minecraft.world.ticks.LevelTicks.<init>(java.util.function.LongPredicate)'
[06:21:42] [Region shutdown thread/INFO]: Stopping server
[06:21:42] [Server thread/INFO]: Done (17.791s)! For help, type "help"
```

`run.json`: exited, exit 0, "jvm exited by itself". The port did not change `NMS/v26_2/.../BlockLevelTicksTracker`.
The Sourby Demo verification of 2026-10-06 (`SSB2/FOLIA.md`) used the **SSB-AuroraWorlds** module. Its
`AuroraIslandWorlds.LOAD_FLAGS` omits `LISTEN_BLOCK_CHANGES` for exactly this `NoSuchMethodError`
(`/Volumes/Ext/Sourby/SSB-AuroraWorlds/src/main/java/dev/iyanz/ssbaurora/AuroraIslandWorlds.java:40-46`, README "Known
limits"). **Both SSB2 jars therefore need that module to enable on SourbyCraft.** Without it, neither can use the
default worlds provider.

## 4. Next step attempted: unported jar plus the SSB-AuroraWorlds module — refused by the harness

(Resolved in section 5: option (a) was added as `--plugin-file`.)

This is the deployment shape the module README documents (bridge `safe`, module jar in
`plugins/SuperiorSkyblock2/modules/`, `spawn.location` moved to an existing world). It would reach `new SpawnIsland()`
and so the adapter. Module jar: `/Volumes/Ext/Sourby/SSB-AuroraWorlds/build/libs/SSB-AuroraWorlds.jar`, SHA-256
`d0b26b47f63df6f7eca05fc1e59c51c439b8e1c523fb1388d79306b9ed6d4721` (local build, 2026-10-13). The prepared
`config.yml` changed one line, `spawn.location: world, -17.5, 100, 40.25, 0, 0`, a non-origin spawn with negative X.

```
agent_testserver: config file destination 'plugins/SuperiorSkyblock2/modules/SSB-AuroraWorlds.jar': a jar may only go directly into plugins/
```

`agent_testserver.py config_files` only places jars directly in `plugins/`, and SSB2 loads modules only from
`plugins/SuperiorSkyblock2/modules/*.jar` (`ModulesManagerImpl.java:65,235`). Changing the harness's isolation rule is
outside this task, so the run was not made. Two ways to unblock it: (a) a harness option for a plugin-data
jar, `--plugin-data-jar plugins/<Plugin>/modules/<x>.jar=FILE`, with the same size, hash and no-symlink checks and a test in
`scripts/test_agent_testserver.py`; or (b) the owner runs it on a test server: boot, check that the log has no bridge
owner-resolution refusal or ownership violation for SuperiorSkyblock2 and that the
`/perf plugins` bridge row for SuperiorSkyblock2 has 0 violations. Then create, teleport and disband an island as a
player (owner-only).

## 5. Unported jar plus the SSB-AuroraWorlds module (agent `opus-harness-modules`)

Harness change (same worktree, uncommitted): `scripts/agent_testserver.py --plugin-file plugins/<Plugin>/<path>=FILE`
places a file inside a plugin's data folder before boot (bytes, regular file, no symlink, at most 8 MiB, read once;
destination must stay under `plugins/<Plugin>/`, no `..`, no absolute path; collisions with `--config-file` refused;
`run.json` `plugin_files` records dest, source, bytes, SHA-256). See [AGENT-TEST-SERVER.md](AGENT-TEST-SERVER.md).
Tests: `scripts/test_agent_testserver.py` 105 OK (6 new, written first and failing first); whole `scripts/test_*.py` 619 OK.

Inputs (both runs): server jar `build/libs/SourbyCraft-slim.jar` of this worktree, SHA-256
`dbefd9d05d1c4c9315631fbfbd8d7636a696d6b5be4b2ec7de76f6ef3b0c0b25` (`/ver`: `Build 48 — Aurora Nexus (DEV, HEAD@6176d51)`,
source `6176d517` clean); harness defaults otherwise (`minecraft:normal`, seed 440044, view 4 / sim 5, 2048 MiB, no clients).

| File | Destination | SHA-256 |
| --- | --- | --- |
| `ssb/jars/SuperiorSkyblock2-2026.3.jar` (unported, local build of `868bebf`) | `plugins/SuperiorSkyblock2-2026.3.jar` (`--config-file`) | `007bee17a589ba2ee74b603a9bc0cc2d900182997b71ca4657b08b84c64ff597` |
| `SSB-AuroraWorlds/build/libs/SSB-AuroraWorlds.jar` (existing local build 2026-10-13 23:54:22, newer than every source file; the repo has no commits, so no commit id) | `plugins/SuperiorSkyblock2/modules/SSB-AuroraWorlds.jar` (`--plugin-file`) | `d0b26b47f63df6f7eca05fc1e59c51c439b8e1c523fb1388d79306b9ed6d4721` |
| SSB2's own `config.yml` from the jar, one line changed: `spawn.location: world, -17.5, 100, 40.25, 0, 0` (non-origin, negative X) | `plugins/SuperiorSkyblock2/config.yml` (`--plugin-file`) | `1991e9ef1dc33a9a57bebc2024aeb3d1f1d499600be0f2a49c8198a5c3b7968c` |
| `aurora.toml` = `docs/config-examples/aurora.toml` with `[aurora.bridge] mode = "safe"` (run 1) | `sourbycraft_config/aurora.toml` | `f8fa3e8b0b1f082925aa617d8d06968b487a8714cf142a31f84fbe0edfd175dc` |
| same, plus `sync-route = "global"` (run 2) | `sourbycraft_config/aurora.toml` | `b37546f93b610cba9ac9da7c5d68a34af690812011bc90325f412e7cb375eaf8` |

### Run `opus-harness-modules-1` (bridge safe, sync-route caller-region)

Slot 2, Done 56.4 s (cold, shared laptop; not a timing), `stop` exit 0, lock released. The plugin enables and so does the
module; `prepareWorlds` no longer reaches `LevelTicks(LongPredicate)` because the module is the worlds provider:

```
[06:34:58] [ServerMain/WARN]: [SourbyCraft] Aurora Bridge admitted SuperiorSkyblock2 (no folia-supported/canvas-supported). ...
[06:35:25] [Server thread/INFO]: [SuperiorSkyblock2] Enabling SuperiorSkyblock2 v2026.3
[06:35:26] [Server thread/INFO]: [SuperiorSkyblock2] Enabling the module AuroraWorldIslands...
[06:35:26] [Server thread/INFO]: [SuperiorSkyblock2-AuroraWorldIslands] Islands are stored in Aurora World Fabric worlds (file (a <folder>.awf directory beside each storage folder))
[06:35:50] [Server thread/INFO]: [SuperiorSkyblock2] Starting to load grid...
[06:35:50] [Server thread/INFO]: [SuperiorSkyblock2] Finished grid!
[06:35:52] [Server thread/INFO]: Done (56.446s)! For help, type "help"
```

Console (`cmd`, bracketed capture):

```
/plugins             Native / Bridged: 0 / 1   Failed / Disabled: 0 / 0   Bridged  SuperiorSkyblock2
/plugins SuperiorSkyblock2
  State: Bridged   Enabled: yes   Declares region support: no
  Scheduler redirects / owner handoffs: 31 / 1
  Rejected / fatal operations: 0 / 0
  Quarantined: no
  Sync body time (region / global): 1 runs, mean 0.70ms ... / 25 runs, mean 7.71ms, p50 0.024ms, p99 130.4ms, max 130.4ms
  Last failure: none
/perf plugins        SuperiorSkyblock2 redirects / handoffs / rejected / violations: 31 / 1 / 0 / 0
```

`logs/latest.log`: 0 lines with `ERROR` or `Exception`; no violation, quarantine or refusal line. Shutdown disabled
SSB2 and its modules (including AuroraWorldIslands) normally.

### Run `opus-harness-modules-2` (same, `sync-route = "global"`)

With `caller-region` a handoff could also come from a sync task scheduled on a ticking region thread. With `global`
an unanchored sync task always goes to the global region, so a REGION_OWNER route (counted as a handoff,
`BridgeRuntime` `telemetry.handoff`) needs an explicit target: `AuroraBridge.targetRegion` returns one only for a
sourbyapi `RegionTask` or the fingerprinted `SpawnIsland` callback (`SuperiorSpawnTaskOwner`). The SSB2 jar contains no
reference to `dev/iyanz/sourbycraft/api/scheduler` (byte search of the jar: 0 hits), so it cannot submit a `RegionTask`.
Slot 2, Done 23.9 s, `stop` exit 0, lock released.

```
/plugins SuperiorSkyblock2
  Scheduler redirects / owner handoffs: 31 / 1
  Rejected / fatal operations: 0 / 0
  Quarantined: no
  Sync body time (region / global): 1 runs, mean 1.20ms ... / 21 runs, mean 3.33ms, p50 0.020ms, p99 26.6ms, max 26.6ms
  Last failure: none
/perf plugins        SuperiorSkyblock2 redirects / handoffs / rejected / violations: 31 / 1 / 0 / 0
```

0 `ERROR`/`Exception` lines. Reading: the one handoff and the one region-side sync run are the `SpawnIsland` biome
callback (`BukkitExecutor.sync(() -> biome = getCenter(null).getBlock().getBiome())`, `SpawnIsland.java:182`), which the
adapter routed to the region owning chunk (-2, 2) of `world`; it ran without a guard violation. This is an inference
from counters, not a per-task trace: the bridge logs nothing on a successful adapter resolution, and the effective
`sync-route` value is not printed at boot (the installed `aurora.toml` says `global`). Neither run shows which biome
SSB2 stored; no console command exposes the spawn island's biome.

Raw: `/Volumes/Ext/Sourby/agent-testservers/opus-harness-modules-{1,2}/` (`logs/latest.log`, `run.json`, `commands.log`).

### Acceptance-case status after section 5

- **1** partly: exact 2026.3 artifact, non-origin spawn with negative X, startup callback routed to a region with no
  violation/quarantine increment (counter evidence). Not covered: the reported biome value, another world, an initially
  unloaded spawn chunk as a separate case (here the spawn chunk at (-2, 2) lies in the area prepared at startup, so it
  was probably loaded).
- **4-rest, 5, 6**: not run (need fault injection, cancel/disable timing, merges, shutdown races).
- **Not covered at all here:** island create / teleport / disband, AWF island-world save and reload, restart with
  islands, players, multi-player concurrency, the module's own region moves (paste, teleport, chunk reset). These need
  real players and are owner-only. No performance numbers (shared laptop, functional runs only).

The engine finding of section "Findings" 2 (plugin `Bukkit.shutdown()` during enable) did not reproduce, because the
plugin no longer fails to enable; it is still open.
